const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const {createCoverageMap} = require('istanbul-lib-coverage');
const {createContext} = require('istanbul-lib-report');
const reports = require('istanbul-reports');
const destination = path.resolve('target/js-coverage');
const raw = path.join(destination, 'raw-' + randomUUID());
const tests = fs.readdirSync('src/test/js').filter(f => f.endsWith('.test.cjs')).map(f => 'src/test/js/' + f);
const result = spawnSync(process.execPath, ['--test', '--test-concurrency=1', ...tests], {
  stdio: 'inherit', env: {...process.env, PLUGIN_COVERAGE_RAW: raw}
});
const coverage = createCoverageMap({});
for (const file of fs.readdirSync(raw)) coverage.merge(JSON.parse(fs.readFileSync(path.join(raw, file), 'utf8')));
const context = createContext({dir: destination, coverageMap: coverage});
for (const reporter of ['text', 'lcovonly', 'json', 'json-summary', 'html']) reports.create(reporter).execute(context);
const missing = coverage.files().map(file => {
  const data = coverage.fileCoverageFor(file).data;
  return {file, lines: coverage.fileCoverageFor(file).getUncoveredLines(),
    branches: Object.entries(data.b).filter(([, hits]) => hits.some(n => n === 0)).map(([id, hits]) => ({line: data.branchMap[id].loc.start.line, type: data.branchMap[id].type, hits})),
    functions: Object.entries(data.f).filter(([, n]) => n === 0).map(([id]) => data.fnMap[id].loc.start.line)};
});
fs.writeFileSync(path.join(destination, 'uncovered.json'), JSON.stringify(missing, null, 2));
const summary = coverage.getCoverageSummary();
const complete = ['lines', 'branches', 'statements', 'functions'].every(kind => summary[kind].pct === 100);
if (!complete) console.error('All production JavaScript must reach 100% lines, branches, statements and functions.');
process.exitCode = result.status || (complete ? 0 : 1);
