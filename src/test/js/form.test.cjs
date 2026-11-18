const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {browser, response, saveCoverage, jobs, job, input, form} = require('./support.cjs');
after(() => saveCoverage('form'));
const policy = (rule = jobs, checkEdits = true) => ({rules: [rule], checkEdits});
const field = (b, name) => b.document.querySelector(`input[name="_.${name}"]`);
async function edit(b, element, value) {
  element.value = value;
  b.event(element, 'input');
  await b.clock.tickAsync(300);
}
function submit(b, type = 'submit') {
  return b.event(b.document.querySelector('form'), type);
}
function rows(kind, values = [], options = {}) {
  const names = {classes: 'selectedClass', users: 'selectedUserId', groups: 'selectedGroupId'};
  const entries = values.map(value => `<div class="repeated-chunk">${input(names[kind], value, options.wrap !== false)}</div>`).join('');
  return `<div class="repeated-container">${entries}<button type="button" class="repeatable-add">Add row</button></div>`;
}

test('guard is limited to node forms and enabled policies, and installs once at load', async () => {
  for (const options of [{}, {policy: {rules: [], checkEdits: true}}, {policy: policy(jobs, false)}]) {
    const b = await browser(form(), options);
    assert.equal(b.document.querySelector('form').dataset.nodePolicyGuard, undefined);
    await b.close();
  }
  for (const action of ['/configure', '/computer/node/other', '/computer/doCreateItem/']) {
    const b = await browser(form('', {action, checked: false}), {policy: policy()});
    assert.equal(b.errors().length, action.endsWith('doCreateItem/') ? 1 : 0);
    await b.close();
  }
  const b = await browser(form('', {checked: false}), {policy: policy(), ready: 'loading'});
  b.w.dispatchEvent(new b.w.Event('load'));
  assert.equal(b.errors().length, 1);
  b.run();
  b.w.dispatchEvent(new b.w.Event('load'));
  assert.equal(b.errors().length, 1);
  await b.close();
});

test('missing and disabled options block Save, Apply and Enter while preserving user input and existing accessibility descriptions', async () => {
  const b = await browser(form('<input id="description" value="keep this">', {checked: false}), {policy: policy({name: 'jobs', label: 'Jobs'})});
  const control = b.document.querySelector('.optional-block-control');
  control.setAttribute('aria-describedby', 'original-help');
  const save = b.document.querySelector('[name="Submit"]');
  let accepted = 0;
  b.document.querySelector('form').addEventListener('submit', event => { accepted++; event.preventDefault(); });
  save.click();
  b.document.querySelector('.apply-button').click();
  assert.equal(submit(b), false);
  assert.equal(accepted, 0);
  assert.equal(b.document.querySelector('#description').value, 'keep this');
  assert.ok(b.scrolled.length >= 3);
  assert.equal(b.document.activeElement, control);
  control.checked = true;
  b.event(control, 'change');
  assert.deepEqual(b.errors(), []);
  assert.equal(control.getAttribute('aria-describedby'), 'original-help');
  save.click();
  assert.equal(accepted, 1);
  b.document.querySelector('#add').click();
  await b.clock.tickAsync(0);
  assert.equal(accepted, 1);
  await b.close();
  for (const options of [{disabled: true}, {control: false}, {section: false}, {row: false, disabled: true}]) {
    const other = await browser(form('', options), {policy: policy({name: 'jobs', label: 'Jobs'})});
    assert.equal(submit(other), false);
    assert.match(other.errors()[0], /unavailable/);
    assert.equal(other.document.activeElement.getAttribute('role'), 'alert');
    await other.close();
  }
});

test('native inline errors follow nested choices and ignore inactive or unrelated controls', async () => {
  const html = form(job('binary', job('any') + job('any', '', {wrap: false}))
    + '<div field-disabled="true">' + job('any') + '</div><div class="form-container--hidden">' + job('any') + '</div>'
    + '<select class="dropdownList"><option>different</option></select>');
  const b = await browser(html, {policy: policy()});
  assert.equal(b.errors().length, 2);
  assert.equal(submit(b), false);
  const selectors = [...b.document.querySelectorAll('select.dropdownList')].slice(1, 3);
  for (const select of selectors) {
    select.value = '8';
    b.event(select, 'change');
  }
  assert.deepEqual(b.errors(), []);
  assert.equal(submit(b), true);
  selectors[0].value = '-1';
  b.event(selectors[0], 'change');
  assert.equal(b.errors().length, 1);
  selectors[0].closest('.jenkins-form-item').nextElementSibling.innerHTML = '<span class="render-on-demand"></span>';
  selectors[0].value = '4';
  b.event(selectors[0], 'change');
  assert.ok(b.errors().length > 0);
  assert.ok(b.errors().every(message => message === 'Loading restriction settings…'));
  selectors[0].closest('.jenkins-form-item').nextElementSibling.replaceChildren();
  await b.clock.tickAsync(1);
  assert.deepEqual(b.errors(), ['Enter a job-name expression.']);
  await b.close();
});

test('list rows require actual restrictions, nonblank identities and valid classes', async () => {
  for (const kind of ['multiple', 'classes', 'users', 'groups']) {
    const contents = kind === 'multiple' ? '<div class="hetero-list-container"><button type="button" class="hetero-list-add">Add</button></div>' : rows(kind);
    const b = await browser(form(job(kind, contents)), {policy: policy()});
    assert.equal(b.errors().length, 1);
    assert.equal(submit(b), false);
    const list = b.document.querySelector('.hetero-list-container,.repeated-container');
    const row = b.document.createElement('div');
    row.className = 'repeated-chunk';
    if (kind === 'multiple') {
      row.setAttribute('name', 'restrictions');
      row.setAttribute('descriptorid', 'r.any');
      row.innerHTML = '<div class="repeated-chunk__header">No restriction</div>';
    } else row.innerHTML = input({classes: 'selectedClass', users: 'selectedUserId', groups: 'selectedGroupId'}[kind], '', false);
    list.prepend(row);
    await b.clock.tickAsync(1);
    assert.equal(b.errors().length, 1);
    assert.equal(submit(b), false);
    if (kind === 'multiple') {
      assert.ok(row.querySelector('.node-property-field-error'));
      row.setAttribute('descriptorid', 'r.custom');
      b.event(row, 'change');
    } else await edit(b, row.querySelector('input'), kind === 'classes' ? 'hudson.model.FreeStyleProject' : 'DOMAIN\\user');
    assert.deepEqual(b.errors(), []);
    row.remove();
    await b.clock.tickAsync(1);
    assert.equal(b.errors().length, 1);
    await b.close();
  }
});

test('oversized lists and oversized input values do not start validation requests', async () => {
  let calls = 0;
  const b = await browser(form(job('users', rows('users', Array(101).fill('valid')))), {policy: policy(), fetch: async () => {calls++; return response({data: {message: ''}});}});
  assert.deepEqual(b.errors(), [jobs.limitMessage]);
  assert.equal(submit(b), false);
  assert.equal(calls, 0);
  await b.close();
  for (const kind of ['expression', 'classes']) {
    const contents = kind === 'expression' ? input('regexExpression') : rows('classes', ['']);
    const other = await browser(form(job(kind, contents)), {policy: policy(), fetch: async () => {calls++; return response({data: {message: ''}});}});
    assert.equal(submit(other), false);
    const limit = kind === 'classes' ? 1024 : 4096;
    await edit(other, other.document.querySelector('input[name^="_."]'), 'x'.repeat(limit + 1));
    assert.equal(other.errors()[0], `Keep the ${kind === 'classes' ? 'job class name' : 'expression'} within ${limit} characters.`);
    assert.equal(calls, 0);
    await other.close();
  }
});

test('successful, invalid and failed value checks preserve the form and do not refetch unchanged fields', async () => {
  let calls = 0;
  let payload = {data: {message: 'Invalid expression'}};
  let ok = true;
  const b = await browser(form(job('expression', input('regexExpression'))), {policy: policy(), fetch: async (_url, options) => {
    calls++;
    assert.equal(options.method, 'POST');
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.headers['Jenkins-Crumb'], 'test-crumb');
    return response(payload, ok);
  }});
  const expression = field(b, 'regexExpression');
  await edit(b, expression, '[');
  assert.deepEqual(b.errors(), ['Invalid expression']);
  assert.equal(submit(b), false);
  assert.equal(calls, 1);
  payload = {data: {message: ''}};
  await edit(b, expression, 'approved-.*');
  assert.deepEqual(b.errors(), []);
  assert.equal(submit(b), true);
  for (const bad of [{}, {data: {message: 3}}]) {
    payload = bad;
    await edit(b, expression, 'value-' + calls);
    assert.match(b.errors()[0], /Could not check/);
    assert.equal(submit(b), false);
  }
  ok = false;
  await edit(b, expression, 'http-failure');
  assert.match(b.errors()[0], /Could not check/);
  await b.close();
});

test('validation uses at most four requests, aborts superseded work and cleans up on navigation', async () => {
  const controls = Array.from({length: 70}, (_, i) => job('expression', input('regexExpression', 'job' + i))).join('');
  const tasks = [];
  let active = 0;
  let maximum = 0;
  const b = await browser(form(job('binary', controls)), {policy: policy(), fetch: (_url, options) => {
    maximum = Math.max(maximum, ++active);
    return new Promise((resolve, reject) => {
      const done = () => {active--; resolve(response({data: {message: ''}}));};
      tasks.push(done);
      options.signal.addEventListener('abort', () => {active--; reject(new Error('aborted'));}, {once: true});
    });
  }});
  assert.equal(active, 4);
  while (tasks.length) { tasks.shift()(); await b.clock.tickAsync(0); }
  assert.equal(maximum, 4);
  assert.equal(active, 0);
  assert.deepEqual(b.errors(), []);
  const expression = field(b, 'regexExpression');
  await edit(b, expression, 'new');
  await edit(b, expression, 'newer');
  assert.equal(active, 1);
  await edit(b, expression, '');
  assert.equal(active, 0);
  await edit(b, expression, 'timeout');
  await b.clock.tickAsync(10000);
  assert.match(b.errors()[0], /Could not check/);
  await edit(b, expression, 'navigate');
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pagehide'));
  await b.clock.tickAsync(0);
  assert.equal(active, 0);
  assert.equal(b.clock.countTimers(), 0);
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow'));
  b.w.dispatchEvent(new b.w.PageTransitionEvent('pageshow', {persisted: true}));
  await b.clock.tickAsync(0);
  assert.equal(active, 4);
  await b.close();
});

test('creator identity is literal text and only node-scoped grants are selected', async () => {
  const permissions = ['agent.Build', 'agent.Configure'];
  const creator = '<img src=x onerror=bad()>';
  const matrix = {name: 'matrix', label: 'Permissions', check: 'matrix', contentMessage: 'Choose a permission.', creator, permissions};
  const row = '<tr><td>__SID__</td><td><input type="checkbox" name="[agent.Build]" tooltip="__SID__ __TYPE__"></td><td><input type="checkbox" name="[global.Admin]"></td></tr>';
  const table = '<table class="global-matrix-authorization-strategy-table"><tbody></tbody></table>';
  const template = '<button class="matrix-auth-add-button" data-type="USER" data-template-id="matrix-template" type="button">Add user</button><template id="matrix-template">' + row + '</template>';
  const b = await browser(form(table + template, {name: 'matrix', action: '/computer/doCreateItem'}), {policy: policy(matrix)});
  assert.deepEqual(b.errors(), []);
  const created = b.document.querySelector('tbody tr');
  assert.equal(created.firstChild.textContent, creator);
  assert.equal(created.querySelector('img'), null);
  assert.equal(created.querySelector('[tooltip]').getAttribute('tooltip'), 'node creator User');
  assert.equal(created.querySelector('[name="[agent.Build]"]').checked, true);
  assert.equal(created.querySelector('[name="[global.Admin]"]').checked, false);
  assert.equal(b.applied.length, 1);
  b.event(created, 'change');
  assert.equal(b.applied.length, 1);
  created.querySelector('input').checked = false;
  b.event(created, 'change');
  assert.equal(submit(b), false);
  assert.equal(b.document.activeElement, created.querySelector('input'));
  await b.close();
  for (const markup of [table, table + '<button class="matrix-auth-add-button" data-type="USER" data-template-id="missing"></button>', '']) {
    const other = await browser(form(markup, {name: 'matrix', action: '/computer/doCreateItem'}), {policy: policy(matrix)});
    assert.equal(other.applied.length, 0);
    await other.close();
  }
});

test('partially rendered controls retain local errors and existing accessibility state', async () => {
  const cases = [
    [job('expression').replace(/<div><\/div>$/, ''), /Enter a job-name expression/],
    [job('users'), /Add at least one user ID/],
    [job('users', rows('users', Array(101).fill('user')).replace(/<button[^>]*>Add row<\/button>/, '')), /100 entries/],
    [job('multiple', '<div class="hetero-list-container"><div name="restrictions" descriptorid="r.any" class="repeated-chunk"></div></div>'), /Remove this/]
  ];
  for (const [markup, error] of cases) {
    const b = await browser(form(markup).replace('<div id="node-property-banner"></div>', ''), {policy: policy()});
    assert.match(b.errors()[0], error);
    assert.equal(submit(b), false);
    await b.close();
  }
  const markup = form('', {checked: false}).replace('optionalBlock-container', 'provider-loading')
    .replace('class="optional-block-control"', 'aria-invalid="false" class="optional-block-control"');
  const b = await browser(markup, {policy: policy()});
  const control = b.document.querySelector('.optional-block-control');
  assert.equal(control.getAttribute('aria-invalid'), 'true');
  control.checked = true;
  b.event(control, 'change');
  assert.equal(control.getAttribute('aria-invalid'), 'false');
  assert.deepEqual(b.errors(), []);
  await b.close();
});

test('an existing creator row is reused without duplicate entries or global grants', async () => {
  const matrix = {name: 'matrix', check: 'matrix', creator: 'already', permissions: ['agent.Build']};
  const table = '<table class="global-matrix-authorization-strategy-table"><tbody><tr name="[USER:someone]"><td>Other user</td></tr>'
    + '<tr name="[USER:already]"><td>Already</td><td><input type="checkbox" name="[agent.Build]"></td></tr></tbody></table>';
  const b = await browser(form(table, {name: 'matrix', action: '/computer/doCreateItem'}), {policy: policy(matrix)});
  assert.equal(b.document.querySelectorAll('tbody tr').length, 2);
  assert.equal(b.document.querySelector('tbody input').checked, true);
  assert.equal(b.applied.length, 1);
  await b.close();
});

test('large forms have bounded queued requests and skip detached controls', async () => {
  const huge = job('binary', job('custom').repeat(1025));
  let calls = 0;
  const limited = await browser(form(huge), {policy: policy(), fetch: async () => { calls++; return response({data: {message: ''}}); }});
  assert.deepEqual(limited.errors(), ['Use fewer nested restrictions or list entries.']);
  assert.equal(submit(limited), false);
  assert.equal(calls, 0);
  await limited.close();

  const tasks = [];
  const fragment = job('expression', input('regexExpression', 'approved-.*')).repeat(520);
  const markup = form(fragment) + form(fragment).replaceAll('id="properties"', 'id="properties-two"').replaceAll('nameref="properties"', 'nameref="properties-two"');
  const b = await browser(markup, {policy: policy(), fetch: (_url, options) => {
    calls++;
    return new Promise((resolve, reject) => {
      tasks.push(() => resolve(response({data: {message: ''}})));
      options.signal.addEventListener('abort', () => reject(new Error('navigation')), {once: true});
    });
  }});
  assert.equal(calls, 4);
  assert.ok(b.errors().includes('Use fewer nested restrictions or list entries.'));
  b.document.querySelectorAll('input[name="_.regexExpression"]')[4].remove();
  tasks.shift()();
  await b.clock.tickAsync(0);
  assert.equal(calls, 5);
  await b.close();
  assert.equal(b.clock.countTimers(), 0);
});
