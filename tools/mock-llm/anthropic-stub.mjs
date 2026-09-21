#!/usr/bin/env node
/**
 * A fake Anthropic Messages API, for exercising this application's run pipeline without a model.
 *
 * Point the app at it with ANTHROPIC_BASE_URL=http://127.0.0.1:8081 (plus any non-empty
 * ANTHROPIC_API_KEY, since a run still has to resolve a credential to get this far) and every
 * crawl, prompt build, SSE stream, JSON parse, persist, export and compare runs for real against a
 * response that costs nothing and never varies.
 *
 * What this is for, and what it is not for:
 *
 *   - It IS for verifying the plumbing: that the depth instruction reaches the provider, that
 *     structured output parses, that the live view shows real per-source and per-tool detail, that
 *     two runs produce comparable reports.
 *   - It is NOT a model, and a report it produces says nothing about analysis quality. Do not read
 *     a green run here as evidence the prompts are good.
 *
 * The responses are deliberately derived from the request rather than canned: the depth is detected
 * from the prompt's own instruction text and the source names are parsed out of the prompt body, so
 * a report only comes back correctly attributed if the prompt genuinely contained what it should.
 * A canned reply would pass even if the app sent an empty prompt.
 *
 * Every request is appended to MOCK_LLM_LOG (default /tmp/mock-llm-requests.jsonl) so a run can be
 * checked afterwards for what was actually asked, which is the part a screenshot cannot show.
 *
 * Usage:  node tools/mock-llm/anthropic-stub.mjs [--port 8081] [--host 0.0.0.0]
 *                                                [--chunk 400] [--chunk-delay-ms 0]
 *
 * The two chunking options exist for one specific question a fast stub cannot answer: whether an
 * answer *arrives progressively* in the browser. With no delay the whole reply lands inside a
 * millisecond, so a UI that waited for the last token and a UI that renders each one look identical
 * from outside. Small chunks with a delay make the difference visible. Defaults are unchanged from
 * before the options existed, so earlier results stay reproducible.
 */

import http from 'node:http';
import { appendFileSync } from 'node:fs';

const PORT = Number(process.argv.includes('--port')
  ? process.argv[process.argv.indexOf('--port') + 1]
  : process.env.MOCK_LLM_PORT ?? 8081);
const LOG = process.env.MOCK_LLM_LOG ?? '/tmp/mock-llm-requests.jsonl';
/**
 * All interfaces, because the usual caller is an application in a container and loopback would not be
 * reachable from there. Safe for what this is: it serves invented text, holds no credentials, and
 * accepts any API key. Pass --host 127.0.0.1 to keep it off the network anyway.
 */
const HOST = process.argv.includes('--host')
  ? process.argv[process.argv.indexOf('--host') + 1]
  : process.env.MOCK_LLM_HOST ?? '0.0.0.0';

/** Reads a numeric flag, falling back to an env var and then a default. */
function numArg(flag, envVar, fallback) {
  const i = process.argv.indexOf(flag);
  return Number(i >= 0 ? process.argv[i + 1] : process.env[envVar] ?? fallback);
}

const CHUNK = numArg('--chunk', 'MOCK_LLM_CHUNK', 400);
const CHUNK_DELAY_MS = numArg('--chunk-delay-ms', 'MOCK_LLM_CHUNK_DELAY_MS', 0);

const CATEGORIES = ['feature', 'pricing', 'policy', 'bugfix', 'documentation', 'deprecation', 'other'];
const CONFIDENCES = ['high', 'medium', 'low'];

/** The three depth instructions in AnalysisDepth, keyed by a phrase unique to each. */
const DEPTH_MARKERS = [
  ['NUCLEAR', 'Be exhaustive'],
  ['SHORT', 'concise 2-4 sentence'],
  ['REGULAR', 'comprehensive but not exhaustive'],
];

/** How many changes and how much prose each depth produces. Scaled so the reports are obviously different. */
const DEPTH_SHAPE = {
  SHORT: { changes: 3, sentences: 1, summarySentences: 2, evidence: 1 },
  REGULAR: { changes: 9, sentences: 2, summarySentences: 5, evidence: 2 },
  NUCLEAR: { changes: 24, sentences: 5, summarySentences: 12, evidence: 6 },
};

function detectDepth(prompt, maxTokens) {
  for (const [depth, marker] of DEPTH_MARKERS) {
    if (prompt.includes(marker)) return depth;
  }
  // Fallback only: if this fires, the depth instruction did not reach the provider, which is itself
  // a finding. Recorded in the log line as depthFrom: "max_tokens".
  if (maxTokens >= 64_000) return 'NUCLEAR';
  if (maxTokens <= 4_096) return 'SHORT';
  return 'REGULAR';
}

/** Source names as the prompt itself labels them, so attribution can only succeed if the prompt was real. */
function sourceNames(prompt) {
  const names = new Set();
  for (const m of prompt.matchAll(/^=== SOURCE: (.+?)(?: ===|$)/gm)) names.add(m[1].trim());
  for (const m of prompt.matchAll(/^- "(.+?)" \(/gm)) names.add(m[1].trim());
  return [...names];
}

function sentence(i, sourceName, depth) {
  return `At ${depth} depth the stub reports item ${i} for "${sourceName}", with enough prose to make `
    + `the rendered report a realistic length rather than a single line.`;
}

function buildReport(prompt, maxTokens) {
  const depth = detectDepth(prompt, maxTokens);
  const shape = DEPTH_SHAPE[depth];
  const names = sourceNames(prompt);
  // No sources parsed means the prompt was not what this app is supposed to send. Say so in the
  // report rather than inventing a source name that would be silently discarded downstream.
  const attribution = names.length > 0 ? names : ['UNPARSEABLE-PROMPT-NO-SOURCES-FOUND'];

  const changes = [];
  for (let i = 1; i <= shape.changes; i++) {
    const sourceName = attribution[(i - 1) % attribution.length];
    changes.push({
      sourceName,
      category: CATEGORIES[(i - 1) % CATEGORIES.length],
      description: Array.from({ length: shape.sentences }, (_, s) => sentence(i + s, sourceName, depth)).join(' '),
      confidence: CONFIDENCES[(i - 1) % CONFIDENCES.length],
      // Every third change reports a removal, which is the documented case for a null snippet.
      evidenceSnippet: i % 3 === 0
        ? null
        : Array.from({ length: shape.evidence }, (_, s) => `evidence line ${s + 1} for item ${i}`).join(' / '),
    });
  }

  const summary = Array.from({ length: shape.summarySentences },
    (_, s) => `${s === 0 ? `This is a ${depth} stub report covering ${attribution.length} source(s).` : ''}`
      + `Summary sentence ${s + 1}: the stub describes what a real model would summarise here.`).join(' ');

  return { depth, names, report: { overallSummary: summary, changes } };
}

function sse(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

async function streamText(res, text, model, extraBlocks) {
  sse(res, 'message_start', {
    type: 'message_start',
    message: {
      id: `msg_stub_${Date.now()}`,
      type: 'message',
      role: 'assistant',
      model,
      content: [],
      stop_reason: null,
      stop_sequence: null,
      usage: { input_tokens: 1000, output_tokens: 1 },
    },
  });

  let index = 0;
  // MCP tool activity, when the request declared MCP servers. This is what makes the live execution
  // view show "Calling <server> MCP tool: <name>" instead of a generic progress string, so it is
  // worth emitting rather than skipping: without it that requirement cannot be verified at all.
  for (const block of extraBlocks) {
    sse(res, 'content_block_start', { type: 'content_block_start', index, content_block: block });
    sse(res, 'content_block_stop', { type: 'content_block_stop', index });
    index++;
  }

  sse(res, 'content_block_start', {
    type: 'content_block_start',
    index,
    content_block: { type: 'text', text: '' },
  });
  // Chunked rather than sent whole, so the consumer's delta accumulation is genuinely exercised.
  for (let i = 0; i < text.length; i += CHUNK) {
    sse(res, 'content_block_delta', {
      type: 'content_block_delta',
      index,
      delta: { type: 'text_delta', text: text.slice(i, i + CHUNK) },
    });
    if (CHUNK_DELAY_MS > 0) await new Promise((r) => setTimeout(r, CHUNK_DELAY_MS));
  }
  sse(res, 'content_block_stop', { type: 'content_block_stop', index });
  sse(res, 'message_delta', {
    type: 'message_delta',
    delta: { stop_reason: 'end_turn', stop_sequence: null },
    usage: { output_tokens: Math.ceil(text.length / 4) },
  });
  sse(res, 'message_stop', { type: 'message_stop' });
  res.end();
}

const server = http.createServer((req, res) => {
  if (!req.url.includes('/messages') || req.method !== 'POST') {
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ type: 'error', error: { type: 'not_found_error', message: req.url } }));
    return;
  }

  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    let body = {};
    try {
      body = JSON.parse(raw);
    } catch {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'error', error: { type: 'invalid_request_error', message: 'bad json' } }));
      return;
    }

    const system = typeof body.system === 'string'
      ? body.system
      : (body.system ?? []).map((b) => b.text ?? '').join('\n');
    const userText = (body.messages ?? [])
      .flatMap((m) => (typeof m.content === 'string' ? [m.content] : (m.content ?? []).map((c) => c.text ?? '')))
      .join('\n');
    const prompt = `${system}\n${userText}`;
    const isReport = Boolean(body.output_config);
    const mcpServers = (body.mcp_servers ?? []).map((s) => s.name);

    let text;
    let logExtra;
    if (isReport) {
      const { depth, names, report } = buildReport(prompt, body.max_tokens ?? 0);
      text = JSON.stringify(report);
      logExtra = {
        kind: 'report',
        depth,
        depthFrom: DEPTH_MARKERS.some(([, marker]) => prompt.includes(marker)) ? 'prompt' : 'max_tokens',
        sourcesInPrompt: names,
        changesReturned: report.changes.length,
      };
    } else {
      text = `This is a stub answer. The question was asked with ${prompt.length} characters of context, `
        + `covering ${sourceNames(prompt).length} source(s). A real provider would answer from that context; `
        + `this stub only proves the ask path streams an answer back to the browser token by token.`;
      logExtra = { kind: 'ask', sourcesInPrompt: sourceNames(prompt) };
    }

    appendFileSync(LOG, `${JSON.stringify({
      at: new Date().toISOString(),
      path: req.url,
      model: body.model,
      maxTokens: body.max_tokens,
      promptChars: prompt.length,
      mcpServers,
      betas: body.betas ?? [],
      hasOutputConfig: isReport,
      responseChars: text.length,
      ...logExtra,
    })}\n`);

    const blocks = mcpServers.map((name, i) => ({
      type: 'mcp_tool_use',
      id: `mcptoolu_stub_${i}`,
      name: 'list_recent_changes',
      server_name: name,
      input: { since: '2026-09-01' },
    }));

    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    void streamText(res, text, body.model ?? 'stub-model', isReport ? blocks : []);
  });
});

server.listen(PORT, HOST, () => {
  process.stdout.write(`mock-llm listening on http://${HOST}:${PORT} (log: ${LOG})\n`);
});
