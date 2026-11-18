const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {browser, saveCoverage, form} = require('./support.cjs');
after(() => saveCoverage('matrix-cards'));

const rule = {name: 'matrix', check: 'matrix', creator: '<img src=x onerror=bad()>',
  permissions: ['agent.Build', 'agent.Configure'], contentMessage: 'Choose a permission.'};
const entry = '<div class="mas-card" name="[USER:__SID__]" data-sid="__SID__" data-type="USER">'
  + '<div class="mas-card__header" role="button" tabindex="0"><div class="mas-card__identity"><span class="mas-card__name">__SID__</span></div></div>'
  + '<input type="checkbox" name="[agent.Build]" tooltip="__SID__ __TYPE__">'
  + '<input type="checkbox" name="[agent.Configure]">'
  + '<input type="checkbox" name="[global.Admin]"></div>';
const matrix = entries => '<div class="mas-container" id="node-matrix"><div class="mas-cards">'
  + entries + '</div><button type="button" class="matrix-auth-add-button" data-type="USER">Add user</button></div>';
const template = '<template data-strategy-id="unrelated">' + entry + '</template>'
  + '<template data-strategy-id="node-matrix">' + entry + '</template>';
const policy = {rules: [rule], checkEdits: true};

test('Matrix 3.3 cards show the creator safely, grant only node permissions, and keep empty edits on the form', async () => {
  const b = await browser(form(matrix('') + template, {name: 'matrix', action: '/computer/doCreateItem'}), {policy});
  const card = b.document.querySelector('.mas-cards .mas-card');
  assert.equal(card.getAttribute('name'), '[USER:' + rule.creator + ']');
  assert.equal(card.dataset.sid, rule.creator);
  assert.equal(card.dataset.type, 'USER');
  assert.equal(card.querySelector('.mas-card__name').textContent, rule.creator);
  assert.equal(card.querySelector('img'), null);
  assert.equal(card.querySelector('[tooltip]').getAttribute('tooltip'), 'node creator User');
  assert.deepEqual([...card.querySelectorAll('input')].map(input => input.checked), [true, true, false]);
  assert.deepEqual(b.errors(), []);
  b.event(card, 'change');
  assert.equal(b.document.querySelectorAll('.mas-cards .mas-card').length, 1);
  assert.equal(b.applied.length, 1);
  for (const input of card.querySelectorAll('input')) input.checked = false;
  b.event(card, 'change');
  assert.deepEqual(b.errors(), ['Choose a permission.']);
  assert.equal(b.event(b.document.querySelector('form'), 'submit'), false);
  assert.equal(b.document.activeElement, card.querySelector('.mas-card__header'));
  card.querySelector('input').checked = true;
  b.event(card, 'change');
  assert.deepEqual(b.errors(), []);
  await b.close();
});

test('Matrix cards reuse an existing creator and do not invent a row without a matching provider template', async () => {
  const existingRule = {...rule, creator: 'owner'};
  const b = await browser(form(matrix(entry.replaceAll('__SID__', 'owner')) + template,
    {name: 'matrix', action: '/computer/doCreateItem'}), {policy: {rules: [existingRule], checkEdits: true}});
  assert.equal(b.document.querySelectorAll('.mas-cards .mas-card').length, 1);
  assert.equal(b.document.querySelector('.mas-cards input').checked, true);
  await b.close();
  const missing = await browser(form(matrix('') + '<template data-strategy-id="unrelated">' + entry + '</template>',
    {name: 'matrix', action: '/computer/doCreateItem'}), {policy});
  assert.equal(missing.document.querySelectorAll('.mas-cards .mas-card').length, 0);
  assert.deepEqual(missing.errors(), ['Choose a permission.']);
  assert.equal(missing.event(missing.document.querySelector('form'), 'submit'), false);
  await missing.close();
});
