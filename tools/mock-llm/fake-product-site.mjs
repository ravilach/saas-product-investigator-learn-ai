#!/usr/bin/env node
/**
 * A tiny two-page product site for the crawler to crawl, whose content can be changed between runs.
 *
 * Why this exists rather than pointing the verification at a real SaaS product's docs: a Compare
 * between two runs is only meaningful if something actually changed in between, and nothing on a
 * real site changes on demand. Here, POST /_advance rewrites the changelog, so run 1 and run 2 see
 * genuinely different source text and the diff in the report is a real diff rather than a hope.
 *
 * Also serves robots.txt, so the crawler's robots handling is exercised rather than bypassed:
 * /internal/ is disallowed and linked from the index, and a compliant crawler must skip it.
 *
 * Usage:  node tools/mock-llm/fake-product-site.mjs [--port 8082]
 *         curl -X POST http://127.0.0.1:8082/_advance    # move to the next revision
 */

import http from 'node:http';

const PORT = Number(process.argv.includes('--port')
  ? process.argv[process.argv.indexOf('--port') + 1]
  : process.env.FAKE_SITE_PORT ?? 8082);

let revision = 1;

const CHANGELOG = {
  1: [
    ['2026-09-01', 'Added bulk CSV export to the Reports area.'],
    ['2026-08-24', 'Fixed a rounding error on invoices in currencies with no minor unit.'],
    ['2026-08-11', 'Documented the webhook retry schedule.'],
  ],
  2: [
    ['2026-09-19', 'Introduced Teams tier at $49/seat/month, replacing the old Pro tier.'],
    ['2026-09-18', 'Removed the legacy /v1/exports endpoint. Use /v2/exports instead.'],
    ['2026-09-15', 'Added SSO via SAML for Teams and Enterprise.'],
    ['2026-09-12', 'Retention policy for audit events changed from 90 days to 400 days.'],
    ['2026-09-01', 'Added bulk CSV export to the Reports area.'],
    ['2026-08-24', 'Fixed a rounding error on invoices in currencies with no minor unit.'],
  ],
};

const page = (title, body) => `<!doctype html><html><head><title>${title}</title></head><body>
<h1>${title}</h1>
${body}
<hr><p>Acme Analytics &mdash; revision ${revision} of this fixture.</p>
</body></html>`;

const index = () => page('Acme Analytics', `
<p>Acme Analytics is a fixture product used to verify a crawler. It is not real.</p>
<ul>
  <li><a href="/changelog">Changelog</a></li>
  <li><a href="/pricing">Pricing</a></li>
  <li><a href="/internal/secret-roadmap">Internal roadmap</a> (disallowed by robots.txt)</li>
</ul>`);

const changelog = () => page('Changelog', `<dl>${CHANGELOG[revision]
  .map(([date, text]) => `<dt>${date}</dt><dd>${text}</dd>`).join('\n')}</dl>`);

const pricing = () => page('Pricing', revision === 1
  ? '<p>Free, then Pro at $29/seat/month. Enterprise on request.</p>'
  : '<p>Free, then <strong>Teams at $49/seat/month</strong>. The Pro tier is withdrawn for new '
    + 'customers. Enterprise on request. Annual billing now gets two months free.</p>');

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];

  if (req.method === 'POST' && url === '/_advance') {
    revision = revision === 1 ? 2 : 1;
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end(`revision is now ${revision}\n`);
    return;
  }

  if (url === '/robots.txt') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('User-agent: *\nDisallow: /internal/\n');
    return;
  }

  // Served, but only reachable by a crawler that ignores robots.txt - which is the point.
  if (url.startsWith('/internal/')) {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(page('Internal roadmap', '<p>ROBOTS-VIOLATION-MARKER: a compliant crawler never reads this.</p>'));
    return;
  }

  const body = url === '/' ? index() : url === '/changelog' ? changelog() : url === '/pricing' ? pricing() : null;
  if (body === null) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found\n');
    return;
  }
  res.writeHead(200, { 'content-type': 'text/html' });
  res.end(body);
});

server.listen(PORT, '0.0.0.0', () => {
  process.stdout.write(`fake-product-site listening on http://0.0.0.0:${PORT} (revision ${revision})\n`);
});
