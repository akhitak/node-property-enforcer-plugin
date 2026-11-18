const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const FakeTimers = require('@sinonjs/fake-timers');
const {createInstrumenter} = require('istanbul-lib-instrument');
const {createCoverageMap} = require('istanbul-lib-coverage');

const directory = 'src/main';
const coverage = createCoverageMap({});
const scripts = new Map();
for (const file of fs.readdirSync(directory, {recursive: true}).filter(file => file.endsWith('.js'))) {
  const name = directory + '/' + file.replaceAll('\\', '/');
  const instrumenter = createInstrumenter({compact: false});
  scripts.set(path.basename(file), instrumenter.instrumentSync(fs.readFileSync(name, 'utf8'), name));
  coverage.addFileCoverage(instrumenter.lastFileCoverage());
}

async function browser(html, {script = 'formGuard.js', policy, fetch, ready = 'complete'} = {}) {
  const dom = new JSDOM(html, {url: 'http://localhost/jenkins/', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window;
  const clock = FakeTimers.withGlobal(w).install({toFake: ['setTimeout', 'clearTimeout']});
  const scrolled = [];
  const applied = [];
  w.HTMLElement.prototype.scrollIntoView = function () { scrolled.push(this); };
  w.Behaviour = {applySubtree: element => applied.push(element)};
  w.crumb = {wrap: headers => ({...headers, 'Jenkins-Crumb': 'test-crumb'})};
  w.fetch = fetch === false ? undefined : fetch || (async () => response({data: {message: ''}}));
  Object.defineProperty(w.document, 'readyState', {configurable: true, value: ready});
  if (policy) {
    const metadata = w.document.createElement('div');
    metadata.id = 'node-property-form-policy';
    metadata.dataset.policy = JSON.stringify(policy);
    metadata.dataset.valueUrl = '/jenkins/descriptorByName/Requirement/validateRestrictionValue';
    w.document.body.append(metadata);
  }
  w.eval(scripts.get(script));
  await clock.tickAsync(0);
  return {
    w, document: w.document, clock, scrolled, applied,
    run: () => w.eval(scripts.get(script)),
    event: (target, type, options = {}) => target.dispatchEvent(new w.Event(type, {bubbles: true, cancelable: true, ...options})),
    errors: () => [...w.document.querySelectorAll('.node-property-field-error:not([hidden])')].map(e => e.textContent),
    async close() {
      w.dispatchEvent(new w.PageTransitionEvent('pagehide'));
      await clock.tickAsync(0);
      coverage.merge(w.__coverage__ || {});
      clock.uninstall();
      w.close();
    }
  };
}

function response(payload, ok = true) { return {ok, json: async () => payload}; }
function saveCoverage(name) {
  const destination = process.env.PLUGIN_COVERAGE_RAW || 'target/js-coverage/manual';
  fs.mkdirSync(destination, {recursive: true});
  fs.writeFileSync(path.join(destination, name + '.json'), JSON.stringify(coverage.toJSON()));
}
const kinds = ['any', 'binary', 'not', 'multiple', 'expression', 'classes', 'users', 'groups', 'custom'];
const jobs = {
  name: 'jobs', label: 'Restrict jobs execution at node', check: 'jobs',
  restrictionLabels: kinds, restrictionTypes: kinds.map(k => 'r.' + k),
  restrictionKinds: Object.fromEntries(kinds.map(k => ['r.' + k, k])),
  contentMessage: 'Choose a job restriction.', classMessage: 'Enter a full class name.',
  listMessage: 'Add a complete restriction.', userMessage: 'Enter a user ID.', groupMessage: 'Enter a group ID.',
  limitMessage: 'Use at most 32 levels and 100 entries.'
};
function job(kind, content = '', {wrap = true} = {}) {
  const select = '<select class="dropdownList">' + kinds.map((k, i) => `<option value="${i}" ${k === kind ? 'selected' : ''}>${k}</option>`).join('') + '</select>';
  return '<div class="jenkins-form-item">' + (wrap ? '<div class="jenkins-select">' + select + '</div>' : select)
    + '</div><div>' + content + '</div>';
}
function input(name, value = '', wrap = true) {
  const field = `<input name="_.${name}" value="${value}">`;
  return wrap ? '<div class="setting-main">' + field + '</div>' : field;
}
function form(content = '', {action = '/computer/agent/configSubmit', control = true, checked = true, disabled = false, section = true, row = true, name = 'jobs'} = {}) {
  const checkbox = control ? `<input type="checkbox" class="optional-block-control" name="${name}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}>` : '';
  const body = '<div class="optionalBlock-container">' + (row ? '<div class="optional-block-start">' + checkbox + '</div>' : checkbox) + content + '</div>';
  return `<div id="main-panel"><div id="node-property-banner"></div><form action="${action}">`
    + (section ? '<div id="properties" name="nodeProperties"></div><section nameref="properties">' + body + '</section>' : body)
    + '<button name="Submit" type="submit">Save</button><button type="button" class="apply-button">Apply</button><button type="button" id="add">Add</button></form></div>';
}
module.exports = {browser, response, saveCoverage, jobs, job, input, form};
