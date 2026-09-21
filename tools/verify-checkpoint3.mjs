#!/usr/bin/env node
/**
 * GETTING_STARTED.md checkpoint 3 - "full stack talks to itself" - in a real browser.
 *
 * Exists because every previous walk of this checkpoint was done over the API, and the three things
 * it actually claims are all visual: that you land on an *empty state* rather than an error, that a
 * deep link while signed out returns you where you were going, and that the sidebar becomes a drawer
 * rather than a squeezed rail. None of those are answerable with curl.
 *
 * Also settles the two Definition-of-Done clauses that were open for the same reason: dark mode
 * being navy-based rather than pure black, and the shell being usable at phone width. Those are
 * judged here by measuring the rendered pixels - the actual computed background colour, the actual
 * offscreen position of the sidebar - rather than by reading the CSS that is supposed to produce
 * them. Screenshots are written alongside so a human can disagree with the measurements.
 *
 *   node tools/verify-checkpoint3.mjs            # needs Vite on :5173 and the backend on :8080
 *
 * Exits non-zero if any check fails.
 */

import { chromium } from 'playwright';
import fs from 'node:fs';

const BASE = process.env.CP3_BASE ?? 'http://localhost:5173';
const SHOTS = process.env.CP3_SHOTS ?? '/tmp/cp3-shots';

const results = [];
let failures = 0;

function check(id, name, passed, evidence) {
  results.push({ id, name, passed, evidence });
  if (!passed) failures++;
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${id}  ${name}\n      ${evidence}\n`);
}

/** Parses `rgb(r, g, b)` / `rgba(...)` into three numbers so colours can be reasoned about numerically. */
function rgb(value) {
  const m = /rgba?\(([^)]+)\)/.exec(value ?? '');
  if (!m) return null;
  const parts = m[1].split(',').map((n) => parseFloat(n.trim()));
  return { r: parts[0], g: parts[1], b: parts[2] };
}

/**
 * WCAG relative luminance, and from it a contrast ratio.
 *
 * <p>Included because "confirm dark mode" is not answerable by looking at a screenshot - a theme can
 * look fine to one person and be unreadable to another. A ratio is a claim anyone can re-check.
 */
function luminance({ r, g, b }) {
  const f = (c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function contrast(fg, bg) {
  const a = luminance(fg);
  const b = luminance(bg);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

async function login(page) {
  await page.fill('input[name="username"], input#username', 'admin');
  await page.fill('input[name="password"], input#password', 'admin');
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 15_000 });
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));

  // --- 3.1 the login page renders at all -------------------------------------------------------
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  const onLogin = /login/i.test(page.url()) || (await page.locator('input[type="password"]').count()) > 0;
  await page.screenshot({ path: `${SHOTS}/01-login.png` });
  check('3.1', 'signed out, the app sends you to a login form', onLogin, `url=${page.url()}`);

  // --- 3.2 deep link while signed out returns you there afterwards ------------------------------
  // The checkpoint's own words: "paste a URL like /account while signed out and confirm you are sent
  // to the login page and then returned to /account after signing in."
  await page.goto(`${BASE}/account`, { waitUntil: 'networkidle' });
  const bouncedToLogin = (await page.locator('input[type="password"]').count()) > 0;
  await login(page);
  await page.waitForTimeout(1200);
  const returnedToAccount = page.url().includes('/account');
  await page.screenshot({ path: `${SHOTS}/02-after-deeplink-login.png` });
  check('3.2', 'a deep link while signed out bounces to login, then returns you to it',
    bouncedToLogin && returnedToAccount,
    `bounced=${bouncedToLogin}, landed on ${page.url()}`);

  // --- 3.3 the Dashboard is an empty state, not an error ----------------------------------------
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const bodyText = await page.locator('body').innerText();
  const looksLikeError = /something went wrong|unexpected error|failed to load|stack trace/i.test(bodyText);
  await page.screenshot({ path: `${SHOTS}/03-dashboard-empty.png`, fullPage: true });
  check('3.3', 'lands on a Dashboard that is an empty state rather than an error',
    !looksLikeError && bodyText.length > 0,
    `${bodyText.replace(/\s+/g, ' ').slice(0, 120)}…`);

  // --- 3.4 dark mode: measured, not eyeballed ---------------------------------------------------
  // The DoD asks for "a navy-based theme (not pure black)". Both halves are measured: that the
  // background actually changed, that it is not #000, and that it is blue-dominant.
  const lightBg = rgb(await page.evaluate(() => getComputedStyle(document.body).backgroundColor));
  // Matched on "Switch to" rather than on "dark"/"light": the label names the *target* mode, so it
  // flips every time it is pressed, and a selector naming either mode stops matching after one click.
  const toggle = page.locator('button[aria-label^="Switch to" i]').first();
  const haveToggle = (await toggle.count()) > 0;
  if (haveToggle) await toggle.click();
  await page.waitForTimeout(700);
  const themeAttr = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  const darkBg = rgb(await page.evaluate(() => getComputedStyle(document.body).backgroundColor));
  const darkFg = rgb(await page.evaluate(() => getComputedStyle(document.body).color));
  await page.screenshot({ path: `${SHOTS}/04-dashboard-dark.png`, fullPage: true });

  const changed = lightBg && darkBg && JSON.stringify(lightBg) !== JSON.stringify(darkBg);
  const notPureBlack = darkBg && (darkBg.r + darkBg.g + darkBg.b) > 0;
  const navy = darkBg && darkBg.b > darkBg.r && darkBg.b > darkBg.g;
  check('3.4a', 'the theme toggle actually changes the rendered background',
    Boolean(changed) && themeAttr === 'dark',
    `data-theme=${themeAttr}, light=rgb(${lightBg?.r},${lightBg?.g},${lightBg?.b}) -> dark=rgb(${darkBg?.r},${darkBg?.g},${darkBg?.b})`);
  check('3.4b', 'dark mode is navy-based, not pure black (blue channel dominates, not #000)',
    Boolean(notPureBlack && navy),
    `dark bg = rgb(${darkBg?.r},${darkBg?.g},${darkBg?.b}); b>r=${darkBg?.b > darkBg?.r}, b>g=${darkBg?.b > darkBg?.g}`);

  const ratio = darkFg && darkBg ? contrast(darkFg, darkBg) : 0;
  check('3.4c', 'body text on the dark background clears WCAG AA (4.5:1)',
    ratio >= 4.5, `contrast ratio ${ratio.toFixed(2)}:1`);

  // Every colour token must resolve to something in dark mode - an unset token renders as
  // transparent or as the light value, which is exactly the bug token-parity tests cannot see.
  const unresolved = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement);
    const names = ['--bg', '--surface', '--primary', '--text', '--text-muted', '--border', '--error',
      '--warning', '--accent', '--on-primary', '--overlay', '--skeleton'];
    return names.filter((n) => !style.getPropertyValue(n).trim());
  });
  check('3.4d', 'every colour token resolves to a real value with the dark theme active',
    unresolved.length === 0, unresolved.length ? `unresolved: ${unresolved.join(', ')}` : 'all 12 sampled tokens resolve');

  if (haveToggle) await toggle.click();
  await page.waitForTimeout(500);

  // --- 3.5 responsive: the sidebar becomes a drawer, measured by position ----------------------
  const sidebarSel = 'aside, nav[class*="sidebar" i], [class*="sidebar" i]';
  const desktop = await page.locator(sidebarSel).first().boundingBox().catch(() => null);
  await page.setViewportSize({ width: 390, height: 844 });   // iPhone-ish
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${SHOTS}/05-phone-dashboard.png`, fullPage: true });

  const phone = await page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x, width: r.width, vw: window.innerWidth };
  }, sidebarSel);
  const offscreen = phone ? phone.x + phone.width <= 1 || phone.width === 0 : false;
  check('3.5a', 'at phone width the sidebar is off-canvas rather than a squeezed rail',
    offscreen,
    phone ? `sidebar x=${Math.round(phone.x)} w=${Math.round(phone.width)} in vw=${phone.vw} (desktop w=${Math.round(desktop?.width ?? 0)})` : 'no sidebar element found');

  // Exactly "Open navigation": the drawer contains a "Close navigation" button too, and a looser
  // match would find whichever happens to come first in the DOM.
  const hamburger = page.locator('button[aria-label="Open navigation" i]').first();
  const haveHamburger = (await hamburger.count()) > 0;
  let drawerOpened = false;
  if (haveHamburger) {
    await hamburger.click();
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${SHOTS}/06-phone-drawer-open.png` });
    const after = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.x, width: r.width };
    }, sidebarSel);
    drawerOpened = Boolean(after && after.x >= -1 && after.width > 100);
    await hamburger.click().catch(() => {});
  }
  check('3.5b', 'a hamburger exists at phone width and opens the drawer on screen',
    haveHamburger && drawerOpened, `hamburger=${haveHamburger}, drawer slid on screen=${drawerOpened}`);

  // No horizontal scroll is the difference between "responsive" and "shrunk".
  const overflow = await page.evaluate(() => ({
    scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth,
  }));
  check('3.5c', 'no horizontal overflow at 390px - the page fits rather than being panned',
    overflow.scrollW <= overflow.clientW + 1, `scrollWidth=${overflow.scrollW} vs clientWidth=${overflow.clientW}`);

  // --- 3.6 nothing screamed in the console -----------------------------------------------------
  const real = consoleErrors.filter((e) => !/favicon|DevTools|Download the React DevTools/i.test(e));
  check('3.6', 'no uncaught errors in the browser console during the whole walk',
    real.length === 0, real.length ? real.slice(0, 3).join(' | ') : 'clean');

  await browser.close();

  process.stdout.write(`\n${'='.repeat(78)}\ncheckpoint 3: ${results.length - failures}/${results.length} checks passed\n`);
  process.stdout.write(`screenshots in ${SHOTS}\n${'='.repeat(78)}\n`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { process.stdout.write(`\nFATAL: ${e.stack}\n`); process.exit(2); });
