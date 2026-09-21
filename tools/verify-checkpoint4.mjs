#!/usr/bin/env node
/**
 * GETTING_STARTED.md checkpoint 4 - the MVP loop - driven through a real browser.
 *
 * "Create a product with a single Website source, click Run and watch the live execution view."
 * Every previous walk of this checkpoint did the equivalent over the API, which cannot answer the part
 * it is actually about: that a human sees specific progress rather than a spinner, and that a report
 * comes back rendered rather than as JSON. So this clicks the buttons.
 *
 * It also settles the Definition-of-Done clauses that were open for the same reason - the live
 * execution view showing which page is being crawled and which MCP tool is being called *as it
 * happens*, category badges on the rendered report, and an ad-hoc answer arriving progressively. Those
 * are judged from what is on screen mid-flight: the run view is sampled every 100ms while the run is
 * still going, so a detail string that only ever appeared in a final payload would not satisfy it.
 *
 * The strings and selectors here were read out of the app rather than guessed, because a walk that
 * asserts invented labels fails for reasons that have nothing to do with the app:
 *   - step labels come from `runEvents.ts` (`Fetching sources`, `Consulting MCP tools`, ...)
 *   - detail lines are the backend's own (`CrawlProgress`, `RunOrchestrator`, the provider classes)
 *   - the seven change categories come from `Badges.tsx`, rendered through `humaniseEnum`
 *   - `Regular` is `depthLabel('REGULAR')`, not the raw enum
 *
 *   node tools/verify-checkpoint4.mjs
 *
 * Needs: Vite on :5173, the backend on :8080 pointed at the stub provider
 * (ANTHROPIC_BASE_URL=http://127.0.0.1:8081), tools/mock-llm/anthropic-stub.mjs on :8081, and
 * tools/mock-llm/fake-product-site.mjs on :8082. Exits non-zero if any check fails.
 *
 * It also runs against the container, which is what makes checkpoint 5's "the same MVP loop as
 * checkpoints 3-4, just self-contained" a measured claim rather than an assumption - the container
 * serves the built frontend from inside the jar, so one base URL covers both the UI and the API:
 *
 *   CP4_BASE=http://127.0.0.1:18080 CP4_SITE=http://fake-site:8082 \
 *     CP4_SITE_LOCAL=http://127.0.0.1:8083 CP4_SHOTS=/tmp/cp5-shots node tools/verify-checkpoint4.mjs
 */

import { chromium } from 'playwright';
import fs from 'node:fs';

const BASE = process.env.CP4_BASE ?? 'http://localhost:5173';
/** Where the *app* reaches the fixture site from - this string is typed into the source URL field. */
const SITE = process.env.CP4_SITE ?? 'http://127.0.0.1:8082';
/**
 * Where *this script* reaches the fixture site, to advance its content between runs. Separate from
 * `SITE` for the same reason `verify-step10.mjs` takes two site flags: when the app runs in a
 * container it resolves the fixture by container name on a shared network, while this process is on
 * the host and must go through a published port. With everything on the host the two are identical,
 * which is why this defaults to `SITE` rather than to a fixed port.
 */
const SITE_LOCAL = process.env.CP4_SITE_LOCAL ?? SITE;
const SHOTS = process.env.CP4_SHOTS ?? '/tmp/cp4-shots';

/** The seven real change categories, as `humaniseEnum` renders them onto a pill. */
const CATEGORY_LABELS = ['Feature', 'Pricing', 'Policy', 'Bugfix', 'Documentation', 'Deprecation', 'Other'];

/** The step labels the run pipeline emits, from `runEvents.ts`. */
const RUN_STEPS = ['Fetching sources', 'Consulting MCP tools', 'Comparing', 'Summarizing'];

/**
 * The shapes a real `detail` line can take, from the backend that produces them. Every one of these
 * names something that actually happened - which is the clause being tested, so a generic
 * "Thinking..." would match none of them.
 */
const DETAIL_PATTERNS = [
  /Crawling https?:\/\/[^\s]+ \(page \d+ of ~\d+\)/,   // CrawlProgress
  /Crawling \d+ sources?/,                              // SourceFetcher
  /Analyzing \d+ sources? (?:against prior snapshots|as a first baseline)/, // RunOrchestrator
  /Calling .+ MCP tool: \S+/,                           // the provider classes
  /Loading .+ snapshot for /,                            // HistoryLoader
];

const results = [];
let failures = 0;

function check(id, name, passed, evidence) {
  results.push({ id, name, passed, evidence });
  if (!passed) failures++;
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${id}  ${name}\n      ${evidence}\n`);
}

/** Reads the text of every global `.pill` badge on screen - how categories and depths are rendered. */
async function pillTexts(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('span.pill')].map((e) => e.textContent.trim()).filter(Boolean));
}

/**
 * Records every detail line the live view ever renders, via a MutationObserver installed before any
 * app code runs.
 *
 * Polling cannot do this job honestly. Against a local fixture and a stub provider a crawl step is
 * alive for a few tens of milliseconds, so a 100ms sampler sees one or two of the strings and misses
 * the rest - and then reports the app as not having rendered them. An observer fires on every React
 * commit, so what it collects is the actual sequence a human would have watched go past.
 */
async function installDetailRecorder(context) {
  await context.addInitScript(() => {
    window.__details = [];
    const scan = () => {
      for (const el of document.querySelectorAll('p[class*="detail" i]')) {
        const text = el.textContent?.trim();
        if (text && !window.__details.includes(text)) window.__details.push(text);
      }
    };
    new MutationObserver(scan).observe(document, { subtree: true, childList: true, characterData: true });
    scan();
  });
}

// The login inputs are addressed by `name`: their ids come from React's `useId()`, so they are
// `_r_0_`-style and change with the render tree.
async function login(page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('input[name="username"]', 'admin');
  await page.fill('input[name="password"]', 'admin');
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 15_000 });
}

/**
 * Clicks Run and samples the live view until the run settles.
 *
 * Sampling is deliberately fast and starts immediately: against a local fixture site and a stub
 * provider the whole run takes a couple of seconds, so a 1s poll could see an idle page, then a
 * finished one, and conclude the live view never rendered anything.
 *
 * @returns what was observed while it was running
 */
async function runAndWatch(page, { shotPrefix }) {
  await page.click('button[role="tab"]:has-text("Run")').catch(() => {});
  await page.waitForTimeout(300);
  await page.locator('button:has-text("Run")').last().click();

  await page.evaluate(() => { window.__details = []; });
  const steps = new Set();
  const phases = new Set();
  let shots = 0;

  for (let i = 0; i < 400; i++) {
    const text = await page.locator('body').innerText().catch(() => '');
    for (const s of RUN_STEPS) if (text.includes(s)) steps.add(s);
    for (const ph of ['Starting', 'Running', 'Completed', 'Failed']) {
      if (new RegExp(`^${ph}$`, 'm').test(text)) phases.add(ph);
    }
    if (shots < 2 && /Running|Starting/.test(text)) {
      await page.screenshot({ path: `${SHOTS}/${shotPrefix}-live-${++shots}.png`, fullPage: true });
    }
    if (phases.has('Completed') || phases.has('Failed')) break;
    await page.waitForTimeout(100);
  }
  await page.screenshot({ path: `${SHOTS}/${shotPrefix}-complete.png`, fullPage: true });

  const rendered = await page.evaluate(() => window.__details ?? []);
  const details = new Set(rendered.filter((d) => DETAIL_PATTERNS.some((p) => p.test(d))));
  return { details, rendered, steps, phases };
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await installDetailRecorder(context);
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));

  await login(page);

  // --- 4.1 create a product with a single Website source, through the form ----------------------
  await page.goto(`${BASE}/products/new`, { waitUntil: 'networkidle' });
  const productName = `Checkpoint 4 ${new Date().toISOString().slice(11, 19)}`;
  await page.fill('#product-name', productName);
  await page.fill('#product-description', 'A fixture changelog, used to walk the MVP loop in a browser.');
  await page.click('button:has-text("Add source")');
  await page.waitForTimeout(400);

  // Scoped to the card holding the source's own fields, not matched across the page. The source ids
  // are `useId()`-generated (`_r_0_-name`), so they can only be addressed by suffix - and
  // `input[id$="-name"]` on its own also matches the *product's* `#product-name`, which silently
  // overwrites the product name and leaves the source unnamed. The form then refuses to save, for a
  // reason that looks nothing like the cause.
  const source = page.locator('div.card').filter({ has: page.locator('select[id$="-type"]') }).first();
  await source.locator('select[id$="-type"]').selectOption('WEBSITE');
  await source.locator('input[id$="-name"]').fill('Fixture Changelog');
  await source.locator('input[id$="-url"]').fill(`${SITE}/changelog`);
  await page.screenshot({ path: `${SHOTS}/01-product-form.png`, fullPage: true });
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/products\/[0-9a-f]{8,}/, { timeout: 15_000 }).catch(() => {});
  const created = /\/products\/[0-9a-f]{8,}/.test(page.url());
  check('4.1', 'a product with one Website source can be created through the form', created,
    created ? `landed on ${page.url()}`
      // On failure, report the form's own validation text - the useful half of a form that did not submit.
      : `still on ${page.url()}: ${(await page.locator('body').innerText()).match(/[^\n]*(?:needs|attention|must|invalid)[^\n]*/gi)?.slice(0, 3).join(' | ') ?? 'no validation message'}`);
  if (!created) { await browser.close(); process.exit(1); }

  // --- 4.2 first run: watch the live execution view while it is running -------------------------
  const first = await runAndWatch(page, { shotPrefix: '02-run1' });

  check('4.2a', 'the run reaches a terminal phase pill rather than hanging',
    first.phases.has('Completed'), `phases seen: ${[...first.phases].join(' -> ') || 'none'}`);
  check('4.2b', 'the live view renders the backend\'s own specific detail lines, not a generic "Thinking…"',
    first.details.size > 0,
    first.details.size
      ? `${first.details.size} of ${first.rendered.length} rendered lines matched a real backend detail shape: ${[...first.details].map((d) => `"${d}"`).join(' | ')}`
      : `no real detail line was ever rendered; observed: ${first.rendered.map((d) => `"${d}"`).join(' | ') || 'nothing'}`);
  check('4.2c', 'named pipeline steps are rendered as the run progresses',
    first.steps.size >= 3, `steps on screen: ${[...first.steps].join(', ') || 'none'}`);

  // --- 4.3 a real report, rendered ---------------------------------------------------------------
  // `p[class*="summary"]` is ReportView's own overallSummary paragraph - the CSS module hashes the
  // class but keeps the name in it, so this matches the element rather than any text saying "summary".
  const summaryText = await page.locator('p[class*="summary"]').first().innerText().catch(() => '');
  check('4.3', 'a change report with a real overallSummary is rendered on the page',
    summaryText.trim().length > 40,
    summaryText ? `${summaryText.replace(/\s+/g, ' ').slice(0, 120)}…` : 'no summary paragraph found');

  // --- 4.4 second run, against changed content, is the one with a real comparison ---------------
  // The checkpoint's own caveat: "The first run has nothing to compare against ... run it a second
  // time". The fixture site is advanced first so the second run has a genuine diff to categorise.
  const advanced = await fetch(`${SITE_LOCAL}/_advance`, { method: 'POST' })
    .then((r) => r.status).catch((e) => `failed: ${e.message}`);
  const second = await runAndWatch(page, { shotPrefix: '03-run2' });
  check('4.4a', 'a second run against changed fixture content completes',
    second.phases.has('Completed'), `POST /_advance -> ${advanced}; phases: ${[...second.phases].join(' -> ')}`);

  const pills = await pillTexts(page);
  const categories = CATEGORY_LABELS.filter((c) => pills.includes(c));
  check('4.4b', 'the comparison\'s changes are rendered with real category badges',
    categories.length > 0,
    categories.length ? `category pills on screen: ${categories.join(', ')}` : `no category pill among: ${pills.join(', ')}`);

  // --- 4.5 the report is in History, where the checkpoint says to look -------------------------
  await page.click('button[role="tab"]:has-text("History")');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${SHOTS}/04-history.png`, fullPage: true });
  // `aria-controls="report-<id>"` is HistoryTab's own panel id. A looser `button[aria-expanded]`
  // matches sidebar nav buttons as well, and clicking one of those navigates off the product page -
  // which then looks like every later check failing.
  const entryButtons = page.locator('button[aria-controls^="report-"]');
  const entries = await entryButtons.count();
  const historyPills = await pillTexts(page);
  check('4.5a', 'both runs appear in the History timeline, badged with the depth they ran at',
    entries >= 2 && historyPills.includes('Regular'),
    `${entries} report entries; "Regular" depth badge present: ${historyPills.includes('Regular')}`);

  // Expanding is the assertion that History renders a report rather than a list of links - the body
  // is only mounted while open, so a summary paragraph appearing after the click is the evidence.
  await entryButtons.first().click().catch(() => {});
  await page.waitForTimeout(900);
  const expanded = await page.locator('p[class*="summary"]').first().innerText().catch(() => '');
  await page.screenshot({ path: `${SHOTS}/05-history-expanded.png`, fullPage: true });
  check('4.5b', 'a History entry expands into the full rendered report',
    expanded.trim().length > 40, expanded ? `${expanded.replace(/\s+/g, ' ').slice(0, 100)}…` : 'no report body after expanding');

  // --- 4.6 ask a question and watch the answer arrive progressively ---------------------------
  // `#ask-question` is an input, not a textarea, and its submit button says "Ask" - the same word as
  // the tab - so the button is taken from inside the ask form rather than by text.
  await page.click('button[role="tab"]:has-text("Ask")');
  await page.locator('#ask-question').waitFor({ timeout: 10_000 });
  await page.fill('#ask-question', 'What changed in the pricing page recently?');
  const askForm = page.locator('form').filter({ has: page.locator('#ask-question') }).first();
  await askForm.locator('button[type="submit"]').click();

  // Streaming is only observable as growth over time: one final length is a result, a series of
  // increasing lengths is a stream. Measured on the transcript alone, so an unrelated re-render
  // elsewhere on the page cannot be mistaken for tokens arriving. Sampled fast, as with the run view.
  const lengths = [];
  for (let i = 0; i < 200; i++) {
    const len = await page.evaluate(() =>
      document.querySelector('div[class*="transcript" i]')?.innerText.length ?? 0);
    lengths.push(len);
    if (i === 4) await page.screenshot({ path: `${SHOTS}/06-ask-streaming.png`, fullPage: true });
    await page.waitForTimeout(100);
    if (lengths.length > 12 && new Set(lengths.slice(-10)).size === 1 && lengths.at(-1) > 0) break;
  }
  await page.screenshot({ path: `${SHOTS}/07-ask-done.png`, fullPage: true });
  const distinct = new Set(lengths).size;
  const growth = Math.max(...lengths) - Math.min(...lengths);
  check('4.6', 'an ad-hoc answer arrives progressively rather than appearing all at once',
    growth > 40 && distinct >= 3,
    `transcript grew by ${growth} chars to ${Math.max(...lengths)}, across ${distinct} distinct lengths in ${lengths.length} samples`);

  const real = consoleErrors.filter((e) => !/favicon|DevTools|React DevTools/i.test(e));
  check('4.7', 'no uncaught browser errors across the whole MVP loop',
    real.length === 0, real.length ? real.slice(0, 3).join(' | ') : 'clean');

  await browser.close();
  process.stdout.write(`\n${'='.repeat(78)}\ncheckpoint 4: ${results.length - failures}/${results.length} checks passed\n`);
  process.stdout.write(`screenshots in ${SHOTS}\n${'='.repeat(78)}\n`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { process.stdout.write(`\nFATAL: ${e.stack}\n`); process.exit(2); });
