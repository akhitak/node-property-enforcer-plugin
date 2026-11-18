const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {browser, response, saveCoverage} = require('./support.cjs');
after(() => saveCoverage('queueMessages'));
const metadata = '<div id="node-property-queue" data-status-url="/jenkins/status"></div>';
const row = id => `<div page-entry-id="${id}" class="app-builds-container__item--not-interactable"><a href="/jenkins/queue/cancelItem?id=${id}" tooltip="Cancel">Cancel</a><div class="app-builds-container__item__description">Original queue reason</div></div>`;
const history = `<div id="jenkins-build-history">${row(9)}</div>`;
const dashboard = '<table id="buildQueue"><tbody><tr><td><a tooltip="Original\\nqueue reason" href="/jenkins/job/test/">Job</a></td><td><a href="/jenkins/queue/cancelItem?id=9">Cancel</a></td></tr></tbody></table>';
const message = {text: 'Node <agent>: Job <script> does not match pattern <test>.', url: '/jenkins/computer/agent/configure'};
const state = {items: {'9': [message]}, refreshSeconds: 15};

test('queue scripts stay inactive without their metadata or fetch', async () => {
  for (const [html, fetch] of [[history, undefined], [metadata + history, false]]) {
    let calls = 0;
    const b = await browser(html, {script: 'queueMessages.js', fetch: fetch === false ? false : async () => { calls++; return response(state); }});
    assert.equal(calls, 0);
    await b.close();
  }
});

test('field-local queue text is escaped, linked, stable and survives Jenkins widget replacement', async () => {
  let current = state;
  let calls = 0;
  const b = await browser(metadata + history + dashboard, {script: 'queueMessages.js', fetch: async url => {
    calls++; assert.equal(url, '/jenkins/status?ids=9'); return response({data: current});
  }});
  const text = b.document.querySelector('.node-property-queue-message');
  assert.equal(b.document.querySelectorAll('.node-property-queue-message').length, 2);
  assert.ok(text.textContent.includes(message.text));
  assert.equal(text.querySelector('script'), null);
  assert.equal(text.querySelector('a').getAttribute('href'), message.url);
  assert.equal(b.document.querySelector('.app-builds-container__item__description').hidden, true);
  assert.equal(b.document.querySelector('#jenkins-build-history a').getAttribute('tooltip'), 'Cancel');
  await b.clock.tickAsync(15000);
  assert.equal(b.document.querySelector('.node-property-queue-message'), text);
  b.document.querySelector('#jenkins-build-history').innerHTML = row(9);
  await b.clock.tickAsync(0);
  assert.equal(b.document.querySelectorAll('.node-property-queue-message').length, 2);
  assert.equal(calls, 2, 'Widget replacement uses the last response, without an extra server request');
  current = {...state, items: {'9': [{text: 'A permitted reader sees no private pattern.'}]}};
  await b.clock.tickAsync(15000);
  assert.equal(b.document.querySelector('.node-property-queue-message a'), null);
  current = {items: {}, refreshSeconds: 15};
  await b.clock.tickAsync(15000);
  assert.equal(b.document.querySelector('.node-property-queue-message'), null);
  assert.equal(b.document.querySelector('.app-builds-container__item__description').hidden, false);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow'));
  await b.close();
});

test('polling sends at most eight visible IDs and ignores invalid rows', async () => {
  const rows = ['', 'bad', '9'.repeat(19), ...Array.from({length: 10}, (_, i) => String(i + 1))].map(row).join('');
  let requested;
  const b = await browser(metadata + `<div id="jenkins-build-history">${rows}</div><table id="buildQueue"><tr><td>No queued item</td></tr></table>`, {
    script: 'queueMessages.js', fetch: async url => { requested = url; return response({items: {}}); }
  });
  assert.equal(requested, '/jenkins/status?ids=1,2,3,4,5,6,7,8');
  await b.close();
});

test('dashboard hover text matches the explanation as plain text and restores on fallback', async () => {
  let current = {items: {}, refreshSeconds: 15};
  const b = await browser(metadata + dashboard, {script: 'queueMessages.js', fetch: async () => response(current)});
  const link = b.document.querySelector('a[tooltip]');
  const updates = [];
  link._tippy = {setProps: props => updates.push(props)};
  current = state;
  await b.clock.tickAsync(15000);
  assert.equal(link.title, message.text);
  assert.equal(link.getAttribute('tooltip'), message.text);
  assert.equal(updates.at(-1).content, message.text);
  assert.equal(updates.at(-1).allowHTML, false, 'Job names and patterns must never become tooltip HTML');
  assert.equal(link.querySelector('script'), null);
  current = {items: {}, refreshSeconds: 15};
  await b.clock.tickAsync(15000);
  assert.equal(link.getAttribute('tooltip'), 'Original\\nqueue reason');
  assert.equal(link.title, 'Original\nqueue reason');
  assert.equal(updates.at(-1).content, link.title);
  link._tippy = {};
  current = state;
  await b.clock.tickAsync(15000);
  assert.equal(link.title, message.text, 'The native fallback works without the optional tooltip method');
  await b.close();
});

test('failure, empty response and disabling messages restore the original explanation', async () => {
  let current = state;
  let ok = true;
  const b = await browser(metadata + history, {script: 'queueMessages.js', fetch: async () => response(current, ok)});
  ok = false;
  await b.clock.tickAsync(15000);
  assert.equal(b.document.querySelector('.node-property-queue-message'), null);
  assert.equal(b.document.querySelector('.app-builds-container__item__description').hidden, false);
  ok = true;
  for (const refreshSeconds of [-1, 999, 'invalid']) {
    current = {items: {'9': [message]}, refreshSeconds};
    await b.clock.tickAsync(300000);
    assert.ok(b.document.querySelector('.node-property-queue-message'));
  }
  current = {};
  await b.clock.tickAsync(300000);
  assert.equal(b.document.querySelector('.node-property-queue-message'), null);
  await b.close();
});

test('pages with no pending rows and hidden tabs make no unnecessary requests', async () => {
  let calls = 0;
  const b = await browser(metadata, {script: 'queueMessages.js', fetch: async () => { calls++; return response(state); }});
  await b.clock.tickAsync(30000);
  assert.equal(calls, 0);
  b.document.body.insertAdjacentHTML('beforeend', history);
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: true});
  await b.clock.tickAsync(30000);
  assert.equal(calls, 0);
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: false});
  await b.clock.tickAsync(30000);
  assert.equal(calls, 1);
  await b.close();
});

test('timeouts and navigation cancel work; history restoration never overlaps requests', async () => {
  let active = 0;
  let maximum = 0;
  const b = await browser(metadata + history, {script: 'queueMessages.js', fetch: (_url, options) => {
    maximum = Math.max(maximum, ++active);
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => { active--; reject(new Error('aborted')); }, {once: true}));
  }});
  await b.clock.tickAsync(10000);
  assert.equal(active, 0);
  await b.clock.tickAsync(30000);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow', {persisted: true}));
  await b.clock.tickAsync(30000);
  assert.equal(maximum, 1);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  await b.clock.tickAsync(60000);
  assert.equal(active, 0);
  assert.equal(b.clock.countTimers(), 0);
  await b.close();
});

test('a response arriving after navigation never updates the old page', async () => {
  let finish;
  const b = await browser(metadata + history, {script: 'queueMessages.js', fetch: () => new Promise(resolve => { finish = resolve; })});
  const before = b.document.querySelector('.node-property-queue-message');
  assert.match(before.textContent, /Checking this build's node requirements/);
  assert.equal(b.document.querySelector('#jenkins-build-history a').getAttribute('tooltip'), 'Cancel');
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  finish(response(state));
  await b.clock.tickAsync(0);
  assert.equal(b.document.querySelector('.node-property-queue-message'), before);
  assert.equal(b.clock.countTimers(), 0);
  await b.close();
});

test('asynchronously loaded queue rows refresh promptly and show every problem without an unrelated native reason', async () => {
  const calls = [];
  let finish;
  const b = await browser(metadata, {script: 'queueMessages.js', fetch: url => {
    calls.push(url); return new Promise(resolve => { finish = resolve; });
  }});
  b.document.body.insertAdjacentHTML('beforeend', history);
  await b.clock.tickAsync(0);
  assert.match(b.document.querySelector('.node-property-queue-message').textContent, /Checking/);
  assert.equal(b.document.querySelector('.app-builds-container__item__description').hidden, true);
  await b.clock.tickAsync(100);
  assert.deepEqual(calls, ['/jenkins/status?ids=9']);
  b.document.querySelector('#jenkins-build-history').innerHTML = row(10);
  await b.clock.tickAsync(100);
  assert.equal(calls.length, 1, 'A changed ID cannot overlap an in-flight request');
  finish(response(state));
  await b.clock.tickAsync(100);
  assert.deepEqual(calls, ['/jenkins/status?ids=9', '/jenkins/status?ids=10']);
  finish(response({items: {'10': [{text: 'Node “target”: Turn on security. Turn on job restrictions.', url: '/computer/target/configure'}]}}));
  await b.clock.tickAsync(0);
  assert.match(b.document.querySelector('.node-property-queue-message').textContent, /Turn on security\. Turn on job restrictions/);
  assert.equal(b.document.querySelectorAll('.node-property-queue-message').length, 1);
  await b.close();
});

test('returning to a visible tab checks newly discovered rows promptly and a failed check restores native details', async () => {
  let calls = 0;
  const b = await browser(metadata, {script: 'queueMessages.js', fetch: async () => { calls++; throw new Error('offline'); }});
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: true});
  b.document.body.insertAdjacentHTML('beforeend', history);
  await b.clock.tickAsync(100);
  assert.equal(calls, 0);
  Object.defineProperty(b.document, 'hidden', {configurable: true, value: false});
  b.document.dispatchEvent(new b.w.Event('visibilitychange'));
  await b.clock.tickAsync(100);
  assert.equal(calls, 1);
  assert.equal(b.document.querySelector('.node-property-queue-message'), null);
  assert.equal(b.document.querySelector('.app-builds-container__item__description').hidden, false);
  await b.clock.tickAsync(1000);
  assert.equal(calls, 1, 'Failure must not create a fast retry loop');
  await b.close();
});
