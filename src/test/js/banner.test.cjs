const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {browser, response, saveCoverage} = require('./support.cjs');
after(() => saveCoverage('banner'));
const html = '<main id="main-panel"><h1>Agent</h1><div id="node-property-banner" data-status-url="status" hidden></div></main>';
const problem = {node: '<agent>', message: '<script>bad()</script>', url: '/computer/agent/configure'};
const state = {problems: [problem], refreshSeconds: 15, blockBuilds: true, buildPage: false, building: false};

test('pages without a usable banner, fetch or an unguarded main panel do not poll', async () => {
  for (const [markup, fetch] of [['', undefined], ['<div id="node-property-banner"></div>', undefined], [html, false], [html + '<form data-node-policy-guard="true"></form>', undefined]]) {
    let calls = 0;
    const b = await browser(markup, {script: 'banner.js', fetch: fetch === false ? false : async () => { calls++; return response(state); }});
    assert.equal(calls, 0);
    await b.close();
  }
});

test('node and running/completed build wording is literal, compact and updates without rebuilding identical content', async () => {
  let current = state;
  const b = await browser(html, {script: 'banner.js', fetch: async () => response({data: current})});
  const banner = b.document.getElementById('node-property-banner');
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow'));
  assert.equal(banner.hidden, false);
  assert.match(banner.textContent, /before starting new builds/);
  assert.equal(banner.querySelector('script'), null);
  assert.equal(banner.querySelector('a').getAttribute('href'), problem.url);
  const first = banner.firstChild;
  await b.clock.tickAsync(15000);
  assert.equal(banner.firstChild, first);
  for (const building of [true, false]) {
    current = {...state, buildPage: true, building, blockBuilds: false, problems: [{...problem, url: ''}]};
    await b.clock.tickAsync(15000);
    assert.match(banner.textContent, building ? /will not interrupt this build/ : /result is unchanged/);
    assert.match(banner.textContent, /do not prevent new builds/);
    assert.match(banner.textContent, /configuration access is required/);
  }
  current = {...state, blockBuilds: false};
  await b.clock.tickAsync(15000);
  assert.match(banner.textContent, /can start new builds/);
  current = {...state, problems: []};
  await b.clock.tickAsync(15000);
  assert.equal(banner.hidden, true);
  assert.equal(banner.children.length, 0);
  await b.close();
});

test('polling bounds invalid intervals, retains warnings on failures and stops after detachment', async () => {
  let current = state;
  let ok = true;
  let calls = 0;
  const b = await browser(html.replace('<h1>Agent</h1>', ''), {script: 'banner.js', fetch: async () => { calls++; return response(current, ok); }});
  const banner = b.document.getElementById('node-property-banner');
  assert.equal(banner.parentElement.id, 'main-panel');
  ok = false;
  await b.clock.tickAsync(15000);
  assert.equal(banner.hidden, false);
  ok = true;
  current = {};
  await b.clock.tickAsync(15000);
  assert.equal(banner.hidden, false);
  current = {...state, refreshSeconds: -1};
  await b.clock.tickAsync(15000);
  const count = calls;
  await b.clock.tickAsync(14999);
  assert.equal(calls, count);
  await b.clock.tickAsync(1);
  current = {...state, refreshSeconds: 'invalid'};
  await b.clock.tickAsync(15000);
  const invalidCount = calls;
  await b.clock.tickAsync(29999);
  assert.equal(calls, invalidCount);
  current = {...state, refreshSeconds: 9999};
  await b.clock.tickAsync(1);
  const maximumCount = calls;
  await b.clock.tickAsync(299999);
  assert.equal(calls, maximumCount);
  banner.remove();
  await b.clock.tickAsync(300001);
  assert.equal(calls, maximumCount);
  await b.close();
});

test('hidden pages skip requests; navigation aborts work and history restoration resumes without overlap', async () => {
  let calls = 0;
  let active = 0;
  let maximum = 0;
  const b = await browser(html, {script: 'banner.js', fetch: (_url, options) => {
    calls++; maximum = Math.max(maximum, ++active);
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => {active--; reject(new Error('aborted'));}, {once: true}));
  }});
  await b.clock.tickAsync(10000);
  assert.equal(active, 0);
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: true});
  await b.clock.tickAsync(30000);
  assert.equal(calls, 1);
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: false});
  await b.clock.tickAsync(30000);
  assert.equal(active, 1);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow', {persisted: true}));
  await b.clock.tickAsync(0);
  await b.clock.tickAsync(30000);
  assert.equal(maximum, 1);
  assert.equal(active, 1);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  await b.clock.tickAsync(60000);
  assert.equal(active, 0);
  assert.equal(b.clock.countTimers(), 0);
  await b.close();
});

test('a form guard added later suppresses the banner', async () => {
  const b = await browser(html, {script: 'banner.js', fetch: async () => response(state)});
  const form = b.document.createElement('form');
  form.dataset.nodePolicyGuard = 'true';
  b.document.body.append(form);
  await b.clock.tickAsync(15000);
  assert.equal(b.document.getElementById('node-property-banner').hidden, true);
  await b.close();
});

test('a response arriving during navigation cannot change a hidden page', async () => {
  let finish;
  const b = await browser(html, {script: 'banner.js', fetch: () => new Promise(resolve => { finish = resolve; })});
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  finish(response(state));
  await b.clock.tickAsync(0);
  assert.equal(b.document.getElementById('node-property-banner').hidden, true);
  assert.equal(b.clock.countTimers(), 0);
  await b.close();
});

test('fallback navigation returns through browser history', async () => {
  const b = await browser('<button id="node-policy-back">Back</button>', {script: 'rejection.js'});
  let backs = 0;
  b.w.history.back = () => { backs++; };
  b.document.querySelector('button').click();
  assert.equal(backs, 1);
  await b.close();
});
