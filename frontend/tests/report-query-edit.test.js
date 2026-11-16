import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { reportEditor, reportUpdate, canSaveQuery } from '../src/report-query-edit.js'

test('metadata edits preserve the original query exactly', () => {
  const report = { id: 1, datasetId: 9, kind: 'ANALYSIS', name: '报表', chartType: 'TABLE', configuration: '{"groups":[],"metrics":[{"operation":"COUNT","field":null}],"filters":[],"limit":10,"extra":true}' }
  const editor = reportEditor(report)
  const body = reportUpdate(report, editor, '新名称', 'BAR')
  assert.deepEqual(body.configuration, JSON.parse(report.configuration))
  assert.equal(body.name, '新名称'); assert.equal(body.datasetId, 9)
})
test('changed query requires a test of the exact current configuration', () => {
  const report = { datasetId: 2, kind: 'PREVIEW', configuration: '{"fields":["name"],"limit":10}' }
  const editor = reportEditor(report)
  assert.equal(canSaveQuery(editor, null), true)
  editor.query.fields.push('id')
  assert.equal(canSaveQuery(editor, null), false)
  const tested = JSON.stringify(editor.query)
  assert.equal(canSaveQuery(editor, tested), true)
  editor.query.limit = 20
  assert.equal(canSaveQuery(editor, tested), false)
})
test('analysis query edits preserve multiple metrics, typed filters and sorts', () => {
  const query = { groups:['category'], metrics:[{operation:'SUM',field:'amount'},{operation:'COUNT',field:null}], filters:[{field:'amount',operation:'GTE',value:10}], sort:[{field:'metric_0',direction:'DESC'}], limit:50 }
  const report = {datasetId:3, kind:'ANALYSIS',configuration:JSON.stringify(query)}
  const editor = reportEditor(report); editor.query.limit = 40
  const body = reportUpdate(report, editor, 'test', 'TABLE')
  assert.deepEqual(body.configuration, { ...query, limit:40 }); assert.equal(typeof body.configuration.filters[0].value,'number')
  assert.equal(JSON.parse(report.configuration).limit,50)
})
test('refuses unsupported or malformed query config', () => {
  assert.throws(() => reportEditor({kind:'PREVIEW', configuration:'{"fields":"oops","limit":5}'}), /配置/)
  assert.throws(() => reportEditor({kind:'UNSUPPORTED',configuration:'{}'}), /配置/)
})

test('report editor refuses already imprecise numeric snapshots and unsafe changed input', async () => {
  const { serializeEditedQuery } = await import('../src/report-query-edit.js')
  const bad = '{"groups":[],"metrics":[{"operation":"COUNT","field":null}],"filters":[{"field":"id","operation":"EQ","value":9007199254740993}],"limit":10}'
  assert.throws(() => reportEditor({ kind: 'ANALYSIS', configuration: bad }), /精度/)
  const report = { kind:'ANALYSIS', datasetId:1, configuration:'{"groups":[],"metrics":[{"operation":"COUNT","field":null}],"filters":[{"field":"id","operation":"EQ","value":12}],"limit":10}' }
  const editor = reportEditor(report)
  editor.query.filters[0].value = '9007199254740993'
  assert.throws(() => serializeEditedQuery(editor), /精度/)
  editor.query.filters[0].value = '12.5'
  assert.equal(serializeEditedQuery(editor).filters[0].value, 12.5)
})
