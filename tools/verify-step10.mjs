#!/usr/bin/env node
/**
 * BUILD ORDER step 10 - the end-to-end acceptance pass, as an executable checklist.
 *
 * Every clause of step 10 in docs/saas-product-investigator-BUILD-PROMPT.md is one numbered check
 * below, in the order the spec lists them, so the two can be read side by side. A check either
 * asserts something and passes, or fails loudly with what it saw - nothing is reported as verified
 * on the strength of "no exception was thrown".
 *
 * Run against a live instance. Both fixtures run as containers on a network of their own, and the
 * app refers to them by container name:
 *
 *   docker network create siv10net
 *   docker run -d --name mock-llm --network siv10net -v /tmp/mock-llm-out:/out \
 *     -e MOCK_LLM_LOG=/out/requests.jsonl -w /w -v "$PWD/tools/mock-llm:/w:ro" \
 *     node:22-alpine node anthropic-stub.mjs          # fake provider, no model, no spend
 *   docker run -d --name fake-site --network siv10net -p 8083:8082 \
 *     -w /w -v "$PWD/tools/mock-llm:/w:ro" \
 *     node:22-alpine node fake-product-site.mjs       # crawl target whose content can change
 *   docker run -d --name siv10 --network siv10net -p 18080:8080 \
 *     -e ANTHROPIC_API_KEY=stub-key-not-a-real-credential \
 *     -e ANTHROPIC_BASE_URL=http://mock-llm:8081 saas-investigator
 *   node tools/verify-step10.mjs --base http://127.0.0.1:18080 \
 *     --site http://fake-site:8082 --site-local http://127.0.0.1:8083
 *
 * Container names rather than --add-host=host.docker.internal:host-gateway, because that is not
 * portable: on Rancher Desktop host.docker.internal resolves to the Linux VM's bridge gateway, not to
 * the macOS host, so a listener started with `node ...&` on the host is simply unreachable and every
 * run fails with "connection refused" against a fixture that is plainly running. A user-defined
 * network has the same meaning everywhere.
 *
 * Two --site flags because the app and this script do not share a view of the fixture site: the app
 * reaches it by container name on the shared network, the script reaches it through a published port
 * to POST /_advance between runs.
 *
 * What a green run here does and does not mean. It means the pipeline works: crawl, robots, prompt
 * construction, provider call, structured-output parse, persistence, SSE narration, export
 * rendering, compare, RBAC, masking, auditing. It does NOT mean the analysis is any good - with the
 * stub provider there is no analysis. The depth checks verify that the depth instruction reaches the
 * provider and that the resulting reports differ, not that a model responded well to it.
 */

import zlib from 'node:zlib';

const BASE = process.argv.includes('--base')
  ? process.argv[process.argv.indexOf('--base') + 1]
  : 'http://127.0.0.1:18080';
/** Where the app can reach the fixture site from: a container name on the shared network. */
const SITE = process.argv.includes('--site')
  ? process.argv[process.argv.indexOf('--site') + 1]
  : 'http://fake-site:8082';
/** Where *this script* can reach the fixture site, to change its content between runs: a published port. */
const SITE_LOCAL = process.argv.includes('--site-local')
  ? process.argv[process.argv.indexOf('--site-local') + 1]
  : 'http://127.0.0.1:8083';

const results = [];
let failures = 0;

function check(id, name, passed, evidence) {
  results.push({ id, name, passed, evidence });
  if (!passed) failures++;
  const mark = passed ? 'PASS' : 'FAIL';
  process.stdout.write(`${mark}  ${id}  ${name}\n      ${evidence}\n`);
}

function fatal(message) {
  process.stdout.write(`\nFATAL: ${message}\n`);
  summarise();
  process.exit(2);
}

async function api(path, { token, method = 'GET', body, raw = false } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (raw) return res;
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: res.status, json, text, headers: res.headers };
}

/** Reads an SSE stream to its terminal event, collecting every event. */
async function readSse(res, { terminal = ['run_completed', 'run_failed'], limitMs = 180_000 } = {}) {
  const events = [];
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const deadline = Date.now() + limitMs;
  let buffer = '';
  while (Date.now() < deadline) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let split;
    while ((split = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
      if (!dataLine) continue;
      try {
        const event = JSON.parse(dataLine.slice(5).trim());
        events.push(event);
        if (terminal.includes(event.type)) {
          await reader.cancel().catch(() => {});
          return events;
        }
      } catch {
        // A keep-alive comment or a partial frame; nothing to collect.
      }
    }
  }
  await reader.cancel().catch(() => {});
  return events;
}

/** How many runs this harness has actually got a 202 for, so the stats check compares against fact. */
let runsTriggered = 0;

/** Triggers a run and follows its event stream to completion. */
async function runAndWatch(token, productId, depth) {
  const started = await api(`/api/saas-products/${productId}/run`, {
    token, method: 'POST', body: depth ? { analysisDepth: depth } : undefined,
  });
  if (started.status !== 202) return { error: `run returned ${started.status}: ${started.text.slice(0, 300)}` };
  runsTriggered++;
  const runId = started.json.runId;
  const stream = await fetch(`${BASE}/api/saas-products/${productId}/runs/${runId}/events`, {
    headers: { authorization: `Bearer ${token}` },
  });
  if (!stream.ok) return { error: `event stream returned ${stream.status}` };
  const events = await readSse(stream);
  const done = events.find((e) => e.type === 'run_completed');
  const failed = events.find((e) => e.type === 'run_failed');
  return { runId, events, report: done?.report ?? null, failed };
}

function reportSize(report) {
  if (!report) return 0;
  return (report.overallSummary ?? '').length
    + (report.changes ?? []).reduce((n, c) => n
      + (c.description ?? '').length + (c.evidenceSnippet ?? '').length, 0);
}

/**
 * The SSE run_completed event carries the persisted ChangeReport entity, not the API's
 * ChangeReportResponse - so there is no changeCount here, only the list it is derived from.
 */
function changeCount(report) {
  return (report?.changes ?? []).length;
}

/**
 * Pulls the text out of a PDF by inflating its compressed streams.
 *
 * <p>Needed because the writer uses cross-reference and object streams, so the page tree and every
 * string are Flate-compressed: searching the raw bytes for "/Type /Page" finds nothing and looks
 * exactly like a PDF with no pages. Returns the decompressed object bytes separately from the text so
 * the structure and the content can each be asserted on.
 */
function extractPdfText(buf) {
  const objects = [];
  const strings = [];
  // The delimiters are ASCII, so latin1 keeps byte offsets and character offsets identical.
  const raw = buf.toString('latin1');
  for (const match of raw.matchAll(/stream\r?\n/g)) {
    const start = match.index + match[0].length;
    const end = raw.indexOf('endstream', start);
    if (end === -1) continue;
    try {
      const inflated = zlib.inflateSync(buf.subarray(start, end)).toString('latin1');
      objects.push(inflated);
      // PDF text-showing operators take their strings in parentheses.
      for (const s of inflated.matchAll(/\((?:[^()\\]|\\.)*\)/g)) {
        strings.push(s[0].slice(1, -1).replace(/\\([()\\])/g, '$1'));
      }
    } catch {
      // An image or already-raw stream. Not text, so not this function's business.
    }
  }
  return { objects: objects.join('\n'), text: strings.join('') };
}

/**
 * Reads one file out of a zip archive, via the central directory.
 *
 * <p>A DOCX is a zip, and there is no way to look inside one without unzipping it. The central
 * directory rather than the local headers because a local header's sizes may be zero when the writer
 * used a data descriptor, in which case the compressed length is only knowable from here.
 */
async function unzipEntry(buf, wanted) {
  const eocd = buf.lastIndexOf(Buffer.from('PK\x05\x06', 'latin1'));
  if (eocd === -1) return '';
  let offset = buf.readUInt32LE(eocd + 16);
  const entries = buf.readUInt16LE(eocd + 10);
  for (let i = 0; i < entries; i++) {
    if (buf.readUInt32LE(offset) !== 0x02014b50) break;
    const method = buf.readUInt16LE(offset + 10);
    const compressedSize = buf.readUInt32LE(offset + 20);
    const nameLength = buf.readUInt16LE(offset + 28);
    const extraLength = buf.readUInt16LE(offset + 30);
    const commentLength = buf.readUInt16LE(offset + 32);
    const localOffset = buf.readUInt32LE(offset + 42);
    const name = buf.subarray(offset + 46, offset + 46 + nameLength).toString('latin1');
    if (name === wanted) {
      const localNameLength = buf.readUInt16LE(localOffset + 26);
      const localExtraLength = buf.readUInt16LE(localOffset + 28);
      const dataStart = localOffset + 30 + localNameLength + localExtraLength;
      const data = buf.subarray(dataStart, dataStart + compressedSize);
      return (method === 0 ? data : zlib.inflateRawSync(data)).toString('utf8');
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return '';
}

function summarise() {
  const passed = results.filter((r) => r.passed).length;
  process.stdout.write(`\n${'='.repeat(78)}\nstep 10: ${passed}/${results.length} checks passed`
    + `${failures ? `, ${failures} FAILED` : ''}\n${'='.repeat(78)}\n`);
  if (failures) {
    process.stdout.write('\nFailed checks:\n');
    for (const r of results.filter((x) => !x.passed)) {
      process.stdout.write(`  ${r.id}  ${r.name}\n      ${r.evidence}\n`);
    }
  }
}

// ---------------------------------------------------------------------------------------------

async function main() {
  process.stdout.write(`step 10 acceptance pass against ${BASE}\n${'='.repeat(78)}\n`);

  // --- 0. boot state, before anything is created -----------------------------------------------
  const health = await api('/actuator/health');
  if (health.status !== 200) fatal(`/actuator/health returned ${health.status}; is the app up?`);

  const login = await api('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
  if (login.status !== 200) fatal(`admin login returned ${login.status}: ${login.text.slice(0, 300)}`);
  let adminToken = login.json.token;
  check('0.1', 'admin/admin login works and returns a bearer token',
    Boolean(adminToken) && login.json.tokenType === 'Bearer', `token ${adminToken.slice(0, 12)}…, role ${login.json.user.role}`);

  // The app boots with no JWT_SECRET set (nothing was passed to the container), and says so.
  const jwtBefore = await api('/api/admin/jwt-secret', { token: adminToken });
  check('10.31', 'boots with no JWT_SECRET set, using an auto-generated or persisted secret',
    jwtBefore.status === 200 && ['AUTO_GENERATED', 'ADMIN_OVERRIDE', 'ENV_VAR'].includes(jwtBefore.json.source),
    `source=${jwtBefore.json?.source}`);

  // --- 1. a READ_ONLY user, so every flow can be exercised as both roles -----------------------
  const readerCreds = { username: `reader-${Date.now()}`, password: 'reader-pass' };
  const createUser = await api('/api/users', {
    token: adminToken, method: 'POST',
    body: {
      firstName: 'Reed', lastName: 'Only', username: readerCreds.username,
      email: `${readerCreds.username}@example.com`, password: readerCreds.password, role: 'READ_ONLY',
    },
  });
  if (createUser.status !== 201) fatal(`creating a READ_ONLY user returned ${createUser.status}: ${createUser.text.slice(0, 300)}`);
  const readerId = createUser.json.id;
  const readerLogin = await api('/api/auth/login', { method: 'POST', body: readerCreds });
  const readerToken = readerLogin.json?.token;
  check('10.2a', 'a READ_ONLY user can be created and can log in',
    readerLogin.status === 200 && Boolean(readerToken), `${readerCreds.username} role=${createUser.json.role}`);

  // --- 2. the seeded product: a website source plus an MCP source with an authToken ------------
  const seeded = await api('/api/saas-products', {
    token: adminToken, method: 'POST',
    body: {
      name: `Acme Analytics ${Date.now()}`,
      description: 'Seeded by verify-step10.mjs',
      sources: [
        { type: 'WEBSITE', name: 'Acme Docs', endpointUrl: `${SITE}/`, maxDepth: 2, maxPages: 10 },
        {
          type: 'ATLASSIAN_MCP', name: 'Acme Jira',
          endpointUrl: 'https://acme.atlassian.net/mcp', authToken: 'super-secret-mcp-token-abcd',
        },
      ],
    },
  });
  if (seeded.status !== 201) fatal(`creating the seeded product returned ${seeded.status}: ${seeded.text.slice(0, 400)}`);
  const productId = seeded.json.id;

  // 10.24: an MCP authToken comes back masked, never in full.
  const fetched = await api(`/api/saas-products/${productId}`, { token: adminToken });
  const mcpSource = fetched.json.sources.find((s) => s.type === 'ATLASSIAN_MCP');
  const bodyHasToken = fetched.text.includes('super-secret-mcp-token-abcd');
  check('10.24', 'an MCP authToken is never returned in full, only configured + last4',
    !bodyHasToken && mcpSource.authTokenConfigured === true && mcpSource.authTokenLast4 === 'abcd'
      && mcpSource.authToken === undefined,
    `authTokenConfigured=${mcpSource.authTokenConfigured} last4=${mcpSource.authTokenLast4} rawTokenInBody=${bodyHasToken}`);

  // --- 3. a full run, watched over SSE, at REGULAR depth ---------------------------------------
  const regular = await runAndWatch(adminToken, productId, 'REGULAR');
  if (regular.error) fatal(`REGULAR run: ${regular.error}`);
  check('10.1a', 'a full run completes end to end and returns a report over SSE',
    Boolean(regular.report) && !regular.failed,
    regular.report
      ? `runId=${regular.runId}, ${changeCount(regular.report)} changes, ${regular.events.length} SSE events`
      : `no report; failed=${JSON.stringify(regular.failed).slice(0, 200)}`);

  // 10.1b: the live execution view's detail strings are real, not a generic "Thinking...".
  const details = regular.events.filter((e) => e.detail).map((e) => `${e.step}: ${e.detail}`);
  const steps = [...new Set(regular.events.map((e) => e.step).filter(Boolean))];
  const hasPageDetail = details.some((d) => /\d+\s*(page|pages)/i.test(d));
  const hasToolDetail = details.some((d) => /MCP tool|Calling/i.test(d));
  const genericOnly = details.every((d) => /thinking|working|please wait/i.test(d));
  check('10.1b', 'SSE detail strings are real per-page/per-tool text, not a generic placeholder',
    hasPageDetail && hasToolDetail && !genericOnly,
    `steps=[${steps.join(', ')}]; per-page=${hasPageDetail}, per-tool=${hasToolDetail}; e.g. ${
      JSON.stringify(details.slice(0, 4))}`);

  // 10.1c: robots.txt was respected - the disallowed page must not be in the crawl.
  const crawled = details.join(' | ');
  check('10.1c', 'robots.txt is respected: the disallowed /internal/ page was not crawled',
    !crawled.includes('/internal/') && !JSON.stringify(regular.report).includes('ROBOTS-VIOLATION-MARKER'),
    `no /internal/ in crawl detail and no violation marker in the report`);

  // 10.1d: the same run, as the READ_ONLY role.
  const asReader = await runAndWatch(readerToken, productId, 'SHORT');
  check('10.2b', 'a READ_ONLY user can run an analysis and receives the report',
    Boolean(asReader.report) && !asReader.error,
    asReader.report ? `runId=${asReader.runId}, ${changeCount(asReader.report)} changes` : `error=${asReader.error}`);

  // 10.2c: and is refused the admin-only writes.
  const readerWrite = await api('/api/saas-products', {
    token: readerToken, method: 'POST', body: { name: 'should-not-exist', sources: [] },
  });
  const readerAdmin = await api('/api/admin/stats', { token: readerToken });
  check('10.2c', 'a READ_ONLY user is refused product creation and the admin endpoints',
    readerWrite.status === 403 && readerAdmin.status === 403,
    `POST /api/saas-products → ${readerWrite.status}, GET /api/admin/stats → ${readerAdmin.status}`);

  // --- 4. ask, as both roles -------------------------------------------------------------------
  for (const [role, token] of [['ADMIN', adminToken], ['READ_ONLY', readerToken]]) {
    const askRes = await fetch(`${BASE}/api/saas-products/${productId}/ask`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ question: 'What changed in pricing?' }),
    });
    const askEvents = askRes.ok ? await readSse(askRes, { terminal: ['done', 'error'], limitMs: 60_000 }) : [];
    const chunks = askEvents.filter((e) => e.type === 'chunk');
    const done = askEvents.find((e) => e.type === 'done');
    check(`10.1e-${role}`, `ask streams an answer token by token as ${role}`,
      askRes.ok && chunks.length > 0 && Boolean(done?.answer),
      `status=${askRes.status}, ${chunks.length} chunk events, answer ${done?.answer?.length ?? 0} chars`);
  }

  // --- 5. the three depths must produce genuinely different reports ----------------------------
  const nuclear = await runAndWatch(adminToken, productId, 'NUCLEAR');
  const sizes = {
    SHORT: reportSize(asReader.report),
    REGULAR: reportSize(regular.report),
    NUCLEAR: reportSize(nuclear.report),
  };
  const counts = {
    SHORT: changeCount(asReader.report),
    REGULAR: changeCount(regular.report),
    NUCLEAR: changeCount(nuclear.report),
  };
  check('10.3', 'SHORT < REGULAR < NUCLEAR in both report length and change count',
    sizes.SHORT < sizes.REGULAR && sizes.REGULAR < sizes.NUCLEAR
      && counts.SHORT < counts.REGULAR && counts.REGULAR < counts.NUCLEAR,
    `chars ${sizes.SHORT}/${sizes.REGULAR}/${sizes.NUCLEAR}, changes ${counts.SHORT}/${counts.REGULAR}/${counts.NUCLEAR}`);
  check('10.3b', 'each depth is recorded on its own report',
    asReader.report?.analysisDepth === 'SHORT' && regular.report?.analysisDepth === 'REGULAR'
      && nuclear.report?.analysisDepth === 'NUCLEAR',
    `${asReader.report?.analysisDepth}/${regular.report?.analysisDepth}/${nuclear.report?.analysisDepth}`);

  // --- 6. exports: PDF and DOCX must be real, parseable documents containing the report --------
  // "Open both and confirm they are legible and correctly formatted" is the requirement, so these
  // checks read the text back out of each document rather than weighing the file. A byte count proves
  // only that something was written; the summary appearing under a "Summary" heading proves the
  // renderer laid the report out. Both files are also written to /tmp so they can be opened by eye.
  const reportId = nuclear.report.id;
  const summarySample = (nuclear.report.overallSummary ?? '').slice(0, 40);
  const firstChange = nuclear.report.changes?.[0]?.description?.slice(0, 40) ?? '';

  const pdfRes = await api(`/api/saas-products/${productId}/reports/${reportId}/export?format=pdf`,
    { token: adminToken, raw: true });
  const pdfBuf = Buffer.from(await pdfRes.arrayBuffer());
  const pdfText = extractPdfText(pdfBuf);
  const pdfPages = (pdfText.objects.match(/\/Type\s*\/Page[^s]/g) ?? []).length;
  const pdfHeadings = ['Run at', 'Analysis depth', 'Triggered by', 'Changes found', 'Summary']
    .filter((h) => pdfText.text.includes(h));
  check('10.4a', 'PDF export renders the report as a laid-out document, not just bytes',
    pdfRes.status === 200 && pdfBuf.subarray(0, 5).toString('latin1') === '%PDF-'
      && pdfPages >= 1 && pdfHeadings.length === 5
      && pdfText.text.includes(summarySample) && pdfText.text.includes(firstChange)
      && /filename="[^"]+\.pdf"/.test(pdfRes.headers.get('content-disposition') ?? ''),
    `${pdfBuf.length} bytes, ${pdfPages} page object(s), headings [${pdfHeadings.join(', ')}], `
      + `summary text present=${pdfText.text.includes(summarySample)}, `
      + `first change present=${pdfText.text.includes(firstChange)}, ${
        pdfRes.headers.get('content-disposition')}`);

  const docxRes = await api(`/api/saas-products/${productId}/reports/${reportId}/export?format=docx`,
    { token: adminToken, raw: true });
  const docxBuf = Buffer.from(await docxRes.arrayBuffer());
  const docXml = await unzipEntry(docxBuf, 'word/document.xml');
  const docText = docXml.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  // One row per change plus a header row: the changes are a table, not a run-on paragraph.
  const rows = (docXml.match(/<w:tr[ >]/g) ?? []).length;
  check('10.4b', 'DOCX export is a real OOXML package whose text and change table are intact',
    docxRes.status === 200 && docxBuf.subarray(0, 2).toString('latin1') === 'PK'
      && docXml.length > 10_000 && rows === changeCount(nuclear.report) + 1
      && docText.includes(summarySample) && docText.includes(firstChange)
      && /filename="[^"]+\.docx"/.test(docxRes.headers.get('content-disposition') ?? ''),
    `${docxBuf.length} bytes zipped / ${docXml.length} bytes of document.xml, ${rows} table rows for ${
      changeCount(nuclear.report)} changes, summary present=${docText.includes(summarySample)}, ${
      docxRes.headers.get('content-disposition')}`);

  const badFormat = await api(`/api/saas-products/${productId}/reports/${reportId}/export?format=rtf`,
    { token: adminToken });
  check('10.4c', 'an unsupported export format is a 400 naming the two that work',
    badFormat.status === 400 && /pdf/i.test(badFormat.text) && /docx/i.test(badFormat.text),
    `status=${badFormat.status}, message=${(badFormat.json?.message ?? badFormat.text).slice(0, 120)}`);

  const { writeFileSync } = await import('node:fs');
  writeFileSync('/tmp/step10-report.pdf', pdfBuf);
  writeFileSync('/tmp/step10-report.docx', docxBuf);
  process.stdout.write(`      (the same bytes are at /tmp/step10-report.pdf and .docx - open them to judge `
    + `by eye what no assertion can)\n`);

  // --- 7. a second run against changed content, then a custom-range compare -------------------
  const advance = await fetch(`${SITE_LOCAL}/_advance`, { method: 'POST' });
  const advanceText = await advance.text();
  const second = await runAndWatch(adminToken, productId, 'REGULAR');
  check('10.5a', 'a second run over genuinely changed source content completes',
    Boolean(second.report) && advance.ok, `fixture ${advanceText.trim()}, runId=${second.runId}`);

  // Today to today, because every snapshot this product has was taken in the last few minutes. A range
  // starting yesterday is correctly refused - there is no data from before the product existed - and
  // the window still spans the two runs above, which is what makes the comparison a real one.
  const today = new Date();
  const iso = (d) => d.toISOString().slice(0, 10);
  const yesterday = new Date(today.getTime() - 86_400_000);
  const compare = await api(`/api/saas-products/${productId}/compare`, {
    token: adminToken, method: 'POST',
    body: { fromDate: iso(today), toDate: iso(today), analysisDepth: 'REGULAR' },
  });
  let compareReport = null;
  if (compare.status === 202) {
    const stream = await fetch(`${BASE}/api/saas-products/${productId}/runs/${compare.json.runId}/events`,
      { headers: { authorization: `Bearer ${adminToken}` } });
    const events = await readSse(stream);
    compareReport = events.find((e) => e.type === 'run_completed')?.report ?? null;
  }
  check('10.5b', 'a custom-range Compare over two real runs produces a report',
    compare.status === 202 && Boolean(compareReport) && compareReport?.runType === 'CUSTOM_RANGE',
    `status=${compare.status}, range ${iso(today)}..${iso(today)}, runType=${compareReport?.runType}, `
      + `${changeCount(compareReport)} changes`);

  // The other half of the same rule: a window that genuinely predates the data is still refused, and
  // the message names the date to move to. Without this, the check above could pass on a compare that
  // accepts anything.
  const tooEarly = await api(`/api/saas-products/${productId}/compare`, {
    token: adminToken, method: 'POST', body: { fromDate: iso(yesterday), toDate: iso(today) },
  });
  check('10.5c', 'a range starting before any stored data is refused, naming the earliest date available',
    tooEarly.status === 400 && /earliest data available is from/.test(tooEarly.json?.message ?? ''),
    `status=${tooEarly.status}, message=${(tooEarly.json?.message ?? tooEarly.text).slice(0, 160)}`);

  check('10.6', 'the mcpHistoryLimited caveat is set on a Compare with an MCP source and not on a normal run',
    compareReport?.mcpHistoryLimited === true && regular.report?.mcpHistoryLimited === false,
    `compare=${compareReport?.mcpHistoryLimited}, standard run=${regular.report?.mcpHistoryLimited}`);

  const badRange = await api(`/api/saas-products/${productId}/compare`, {
    token: adminToken, method: 'POST', body: { fromDate: iso(today), toDate: iso(yesterday) },
  });
  check('10.29d', 'an inverted Compare range fails gracefully with an explanatory 400',
    badRange.status === 400 && (badRange.json?.message ?? '').length > 10,
    `status=${badRange.status}, message=${(badRange.json?.message ?? badRange.text).slice(0, 160)}`);

  // --- 8. checkpoint 4 again, with a single fresh product/source/run ---------------------------
  const fresh = await api('/api/saas-products', {
    token: adminToken, method: 'POST',
    body: {
      name: `Fresh Single Source ${Date.now()}`,
      sources: [{ type: 'WEBSITE', name: 'Just the changelog', endpointUrl: `${SITE}/changelog` }],
    },
  });
  const freshRun = fresh.status === 201 ? await runAndWatch(adminToken, fresh.json.id, null) : { error: 'not created' };
  check('10.1f', 'checkpoint 4 holds for a fresh product with one source and no depth specified',
    Boolean(freshRun.report) && freshRun.report?.analysisDepth === 'REGULAR',
    freshRun.report
      ? `depth defaulted to ${freshRun.report.analysisDepth}, ${changeCount(freshRun.report)} changes`
      : `error=${freshRun.error}`);

  // --- 9. admin console: stats and health ------------------------------------------------------
  const stats = await api('/api/admin/stats', { token: adminToken });
  // Compared against what this harness actually triggered, not a number written by hand: a hand-written
  // expectation is a second thing that can be wrong, and when it is, it accuses the application.
  const expectedRuns = runsTriggered + (compareReport ? 1 : 0);
  check('10.7', 'Admin Overview stats reflect the work just done',
    stats.status === 200 && stats.json.totalProducts >= 2 && stats.json.totalUsers >= 2
      && stats.json.runsLast24h >= expectedRuns && stats.json.runSuccessRate7d > 0
      && Array.isArray(stats.json.recentRuns) && stats.json.recentRuns.length > 0,
    `products=${stats.json?.totalProducts}, users=${stats.json?.totalUsers}, runs24h=${
      stats.json?.runsLast24h} (harness triggered ${expectedRuns}), success7d=${
      stats.json?.runSuccessRate7d}, recentRuns=${stats.json?.recentRuns?.length}`);

  const adminHealth = await api('/api/admin/health', { token: adminToken });
  // Matched loosely because the row name is Boot's contributor name, which is not this app's to fix.
  const mongo = adminHealth.json?.components?.find((c) => /mongo/i.test(c.name));
  const provider = adminHealth.json?.providers?.find((p) => p.provider === 'ANTHROPIC');
  check('10.8', 'Admin health reports Mongo UP, a resolvable provider, and the last successful run',
    adminHealth.status === 200 && adminHealth.json.status === 'UP' && mongo?.status === 'UP'
      && provider?.configured === true && Boolean(adminHealth.json.lastSuccessfulRun),
    `status=${adminHealth.json?.status}, components=[${
      (adminHealth.json?.components ?? []).map((c) => `${c.name}:${c.status}`).join(' ')}], `
      + `provider source=${provider?.source}, lastRun=${adminHealth.json?.lastSuccessfulRun?.productName}`);

  // --- 10. settings: a changed default must change what a new source without overrides uses ----
  const settingsBefore = await api('/api/admin/settings', { token: adminToken });
  const newDepth = settingsBefore.json.defaultMaxDepth === 3 ? 2 : 3;
  const newPages = settingsBefore.json.defaultMaxPages === 17 ? 18 : 17;
  const putSettings = await api('/api/admin/settings', {
    token: adminToken, method: 'PUT', body: { defaultMaxDepth: newDepth, defaultMaxPages: newPages },
  });
  const afterSettings = await api('/api/saas-products', {
    token: adminToken, method: 'POST',
    body: {
      name: `Inherits Defaults ${Date.now()}`,
      sources: [{ type: 'WEBSITE', name: 'No overrides', endpointUrl: `${SITE}/pricing` }],
    },
  });
  const inherited = afterSettings.json?.sources?.[0];
  check('10.9', 'changing crawl defaults changes the effective bounds of a new source with no overrides',
    putSettings.status === 200 && inherited?.effectiveMaxDepth === newDepth
      && inherited?.effectiveMaxPages === newPages && inherited?.maxDepth === null,
    `defaults now ${newDepth}/${newPages}; new source effective ${inherited?.effectiveMaxDepth}/${
      inherited?.effectiveMaxPages}, own overrides ${inherited?.maxDepth}/${inherited?.maxPages}`);

  const badSettings = await api('/api/admin/settings', {
    token: adminToken, method: 'PUT', body: { defaultMaxDepth: 999, defaultMaxPages: 999_999 },
  });
  check('10.9b', 'a default above the configured ceiling is rejected rather than silently clamped',
    badSettings.status === 400, `status=${badSettings.status}, message=${
      (badSettings.json?.message ?? badSettings.text).slice(0, 140)}`);

  // --- 11. data explorer: browse, edit a plain field, be refused a masked one ------------------
  const collections = await api('/api/admin/data-explorer/collections', { token: adminToken });
  const names = (collections.json ?? []).map((c) => c.name);
  check('10.10a', 'Data Explorer lists collections with their document counts and secret fields',
    collections.status === 200 && names.includes('users') && names.includes('saas_products')
      && (collections.json.find((c) => c.name === 'users')?.secretFields ?? []).includes('passwordHash'),
    `${names.length} collections: ${names.slice(0, 8).join(', ')}`);

  let browsed = 0;
  const masked = {};
  for (const name of ['users', 'saas_products', 'change_reports', 'audit_logs']) {
    const page = await api(`/api/admin/data-explorer/collections/${name}/documents?pageSize=3`, { token: adminToken });
    if (page.status === 200) browsed++;
    if (name === 'users') masked.users = page.text.includes('[encrypted]');
    if (name === 'saas_products') masked.products = page.text.includes('[encrypted]');
  }
  check('10.10b', 'several collections browse successfully with secrets masked on the way out',
    browsed === 4 && masked.users === true && masked.products === true,
    `${browsed}/4 collections paged; users masked=${masked.users}, saas_products (nested) masked=${masked.products}`);

  const editable = await api(`/api/admin/data-explorer/collections/saas_products/documents/${productId}`,
    { token: adminToken });
  const editPlain = await api(`/api/admin/data-explorer/collections/saas_products/documents/${productId}`, {
    token: adminToken, method: 'PUT', body: { description: 'Edited by verify-step10' },
  });
  const reread = await api(`/api/admin/data-explorer/collections/saas_products/documents/${productId}`,
    { token: adminToken });
  check('10.10c', 'editing a non-secret field through Data Explorer saves',
    editable.status === 200 && editPlain.status === 200 && reread.json?.description === 'Edited by verify-step10',
    `PUT → ${editPlain.status}; description now "${reread.json?.description}"`);

  const users = await api('/api/admin/data-explorer/collections/users/documents?pageSize=5', { token: adminToken });
  const someUserId = users.json?.content?.[0]?._id;
  const editMasked = await api(`/api/admin/data-explorer/collections/users/documents/${someUserId}`, {
    token: adminToken, method: 'PUT', body: { passwordHash: 'not-a-real-hash-attempt' },
  });
  const nestedMasked = await api(`/api/admin/data-explorer/collections/saas_products/documents/${productId}`, {
    token: adminToken, method: 'PUT', body: { sources: [{ authTokenEncrypted: 'sneaky' }] },
  });
  check('10.10d', 'editing a masked field is rejected, including one buried in a nested array',
    editMasked.status === 400 && /passwordHash/.test(editMasked.text) && nestedMasked.status === 400,
    `passwordHash → ${editMasked.status} (${(editMasked.json?.message ?? '').slice(0, 90)}); `
      + `nested authTokenEncrypted → ${nestedMasked.status}`);

  // --- 12. forced failures must degrade gracefully ---------------------------------------------
  const badMcp = await api('/api/saas-products', {
    token: adminToken, method: 'POST',
    body: {
      name: `Broken Sources ${Date.now()}`,
      sources: [
        { type: 'GENERIC_MCP', name: 'Nowhere MCP', endpointUrl: 'https://127.0.0.1:9/mcp' },
        { type: 'WEBSITE', name: 'Unreachable site', endpointUrl: 'http://127.0.0.1:9/nothing' },
      ],
    },
  });
  const brokenRun = badMcp.status === 201 ? await runAndWatch(adminToken, badMcp.json.id, 'SHORT') : {};
  const stepFailures = (brokenRun.events ?? []).filter((e) => e.type === 'step_failed');
  check('10.29ab', 'a bad MCP URL and an unreachable crawl target fail per-source without killing the run',
    Boolean(brokenRun.report) && stepFailures.length > 0
      && stepFailures.every((e) => (e.detail ?? '').length > 5),
    `${stepFailures.length} step_failed event(s), run still completed=${Boolean(brokenRun.report)}; e.g. ${
      JSON.stringify(stepFailures.slice(0, 2).map((e) => e.detail))}`);

  // --- 13. prometheus metric names -------------------------------------------------------------
  const prom = await api('/actuator/prometheus');
  const wanted = ['saas_run_total', 'saas_source_fetch_errors_total'];
  const present = wanted.filter((m) => prom.text.includes(m));
  const tagged = /saas_run_total\{[^}]*runType=/.test(prom.text) || /saas_run_total\{[^}]*run_type=/.test(prom.text);
  check('10.26', '/actuator/prometheus exposes the expected business metrics, tagged',
    prom.status === 200 && present.length === wanted.length,
    `present: ${present.join(', ') || 'none'}; runType tag seen=${tagged}`);

  // --- 14. audit log: every action the spec names ----------------------------------------------
  // The password reset happens first so one query can cover the whole list. Names are AuditAction's
  // own constants, not guesses - a wrong name here would report a missing entry that does exist.
  const resetPw = await api(`/api/users/${readerId}/password`, {
    token: adminToken, method: 'PUT', body: { newPassword: 'rotated-pass' },
  });
  const audit = await api('/api/audit-logs?size=200', { token: adminToken });
  const actions = new Set((audit.json?.content ?? []).map((e) => e.action));
  const expected = [
    'AUTH_LOGIN_SUCCESS',
    'PRODUCT_RUN_TRIGGERED',
    'PRODUCT_COMPARE_TRIGGERED',
    'PRODUCT_ASK_SUBMITTED',
    'USER_PASSWORD_RESET',
    'SYSTEM_SETTINGS_UPDATED',
    'DATA_EXPLORER_DOCUMENT_UPDATED',
  ];
  const missing = expected.filter((a) => !actions.has(a));
  check('10.28', 'audit entries exist for every action step 10 names',
    resetPw.status === 200 && audit.status === 200 && missing.length === 0,
    `reset → ${resetPw.status}; ${expected.length - missing.length}/${expected.length} present${
      missing.length ? `; MISSING ${missing.join(', ')}` : ''}; ${actions.size} distinct actions in the log`);

  // The entries have to identify who did what, or the trail is not one.
  const anEntry = (audit.json?.content ?? []).find((e) => e.action === 'PRODUCT_RUN_TRIGGERED');
  check('10.28b', 'an audit entry names the actor, the target and the time',
    Boolean(anEntry?.actorUsername) && Boolean(anEntry?.timestamp) && Boolean(anEntry?.targetType),
    `${anEntry?.action} by ${anEntry?.actorUsername} on ${anEntry?.targetType}/${
      anEntry?.targetId} at ${anEntry?.timestamp}`);

  // --- 15. the JWT override: signs everyone out, twice -----------------------------------------
  const override = await api('/api/admin/jwt-secret', {
    token: adminToken, method: 'PUT', body: { value: 'a-verification-override-secret-of-sufficient-length' },
  });
  const adminAfterOverride = await api('/api/auth/me', { token: adminToken });
  const readerAfterOverride = await api('/api/auth/me', { token: readerToken });
  check('10.11a', 'setting a JWT override immediately signs out everyone, including the admin who set it',
    override.status === 200 && override.json.sessionsInvalidated === true
      && adminAfterOverride.status === 401 && readerAfterOverride.status === 401,
    `PUT → ${override.status} (source=${override.json?.source}), admin's own token → ${
      adminAfterOverride.status}, reader's token → ${readerAfterOverride.status}`);

  const reLogin = await api('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
  adminToken = reLogin.json?.token;
  const clear = await api('/api/admin/jwt-secret', { token: adminToken, method: 'DELETE' });
  const adminAfterClear = await api('/api/auth/me', { token: adminToken });
  check('10.11b', 'clearing the override signs everyone out again, and the source reverts',
    reLogin.status === 200 && clear.status === 204 && adminAfterClear.status === 401,
    `re-login → ${reLogin.status}, DELETE → ${clear.status}, token after clear → ${adminAfterClear.status}`);

  const finalLogin = await api('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
  adminToken = finalLogin.json?.token;
  const jwtAfter = await api('/api/admin/jwt-secret', { token: adminToken });
  check('10.11c', 'after clearing, logging in still works and the secret source is no longer an override',
    finalLogin.status === 200 && jwtAfter.json?.source !== 'ADMIN_OVERRIDE',
    `login → ${finalLogin.status}, jwt source=${jwtAfter.json?.source}`);

  // --- 16. the restart check's first half ------------------------------------------------------
  // The second half is in --restart-mode below, run after the container is restarted.
  process.stdout.write(`\nFor the restart check (10.30), note the current admin token and re-run with\n`
    + `  node tools/verify-step10.mjs --restart-mode --token ${adminToken}\n`
    + `after 'docker restart' - a persisted secret means that token still works.\n`);

  summarise();
  process.stdout.write(`\nWhat the mock provider was actually asked (the part no screenshot shows):\n`
    + `  ${process.env.MOCK_LLM_LOG ?? '/tmp/mock-llm-requests.jsonl'}\n`);
  process.exit(failures ? 1 : 0);
}

/**
 * The "no LLM provider configured" clause of step 10.
 *
 * <p>Cannot run in the main pass: while ANTHROPIC_API_KEY is in the container's environment a
 * credential always resolves, so the NONE branch is unreachable. Run this against a second container
 * started with no provider key at all - which is also the state a first-time user is in.
 */
async function noProviderMode() {
  process.stdout.write(`no-provider check against ${BASE}\n${'='.repeat(78)}\n`);
  const login = await api('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
  if (login.status !== 200) fatal(`admin login returned ${login.status}`);
  const token = login.json.token;

  const health = await api('/api/admin/health', { token });
  const anyConfigured = (health.json?.providers ?? []).some((p) => p.configured);
  check('10.29c-pre', 'this instance genuinely has no provider configured, so the check means something',
    anyConfigured === false,
    `providers: ${(health.json?.providers ?? []).map((p) => `${p.provider}:${p.source}`).join(', ')}`);

  const product = await api('/api/saas-products', {
    token, method: 'POST',
    body: { name: `No Provider ${Date.now()}`, sources: [{ type: 'WEBSITE', name: 'Site', endpointUrl: `${SITE}/` }] },
  });
  const run = await runAndWatch(token, product.json.id, 'SHORT');
  const message = run.failed?.detail ?? '';
  check('10.29c', 'a run with no provider configured fails with an explanatory message, not a stack trace',
    Boolean(run.failed) && message.length > 20 && /provider|credential|API key/i.test(message)
      && !/Exception|at com\./.test(message),
    `run_failed detail: ${JSON.stringify(message)}`);

  summarise();
  process.exit(failures ? 1 : 0);
}

/**
 * The second half of the persisted-secret check: a token minted before a restart must still work.
 *
 * <p>If the secret were regenerated on every boot this would 401, and every user would be silently
 * logged out by a routine restart - the failure mode the persistence exists to prevent.
 */
async function restartMode() {
  const token = process.argv[process.argv.indexOf('--token') + 1];
  const me = await api('/api/auth/me', { token });
  check('10.30', 'a token minted before the restart still works, so the secret was persisted not regenerated',
    me.status === 200, `GET /api/auth/me → ${me.status}${me.json?.username ? ` as ${me.json.username}` : ''}`);
  summarise();
  process.exit(failures ? 1 : 0);
}

const entry = process.argv.includes('--no-provider-mode')
  ? noProviderMode
  : process.argv.includes('--restart-mode') ? restartMode : main;

entry().catch((e) => {
  process.stdout.write(`\nUNCAUGHT: ${e.stack}\n`);
  summarise();
  process.exit(3);
});
