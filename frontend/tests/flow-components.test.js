import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { COMPONENTS, componentDef, defaultConfig, configSummary } from '../src/flow-components.js'

test('catalog covers seven components in three groups', () => {
  assert.equal(COMPONENTS.length, 7)
  assert.deepEqual([...new Set(COMPONENTS.map(c => c.group))].sort(), ['interceptor', 'sink', 'source'])
  for (const c of COMPONENTS) assert.ok(c.label && c.component)
})

test('default configs match backend required fields', () => {
  assert.equal(defaultConfig('SOURCE_TABLE').table, '')
  assert.equal(defaultConfig('SOURCE_SQL').sql, '')
  assert.deepEqual(defaultConfig('FIELD_MAP').mappings, [])
  assert.equal(defaultConfig('FIELD_FILTER').expression, '')
  assert.equal(defaultConfig('FIELD_MASK').field, '')
  assert.equal(defaultConfig('FIELD_CALC').expression, '')
  assert.equal(defaultConfig('SINK_TABLE').writeMode, 'APPEND')
  assert.equal(defaultConfig('SINK_TABLE').batchSize, 500)
})

test('componentDef throws on unknown', () => {
  assert.throws(() => componentDef('KAFKA'))
  assert.equal(componentDef('FIELD_MASK').group, 'interceptor')
})

test('defaultConfig throws on unknown', () => {
  assert.throws(() => defaultConfig('KAFKA'))
})

test('batchSize schema declares bounds', () => {
  const field = componentDef('SINK_TABLE').fields.find(f => f.key === 'batchSize')
  assert.equal(field.min, 1)
  assert.equal(field.max, 10000)
})

test('configSummary renders per-component summaries', () => {
  assert.equal(configSummary('SOURCE_TABLE', { table: 'orders' }), 'orders')
  assert.equal(configSummary('SINK_TABLE', { table: 'copy' }), 'copy')
  assert.equal(configSummary('SOURCE_SQL', { sql: 'select 1' }), 'select 1')
  assert.equal(configSummary('FIELD_MAP', { mappings: [{ from: 'a', to: 'b' }] }), '1 条映射')
  assert.equal(configSummary('FIELD_FILTER', { expression: 'amount > 0' }), 'amount > 0')
  assert.equal(configSummary('FIELD_MASK', { field: 'phone' }), 'phone')
  assert.equal(configSummary('FIELD_CALC', { field: 'total' }), 'total')
})

test('configSummary tolerates undefined config', () => {
  assert.equal(configSummary('SOURCE_TABLE', undefined), '未配置表')
  assert.equal(configSummary('SINK_TABLE'), '未配置表')
  assert.equal(configSummary('SOURCE_SQL'), '未配置 SQL')
  assert.equal(configSummary('FIELD_MAP', undefined), '0 条映射')
  assert.equal(configSummary('FIELD_FILTER', undefined), '未配置条件')
  assert.equal(configSummary('FIELD_MASK'), '未配置字段')
  assert.equal(configSummary('FIELD_CALC', undefined), '未配置字段')
})

test('configSummary throws on unknown component', () => {
  assert.throws(() => configSummary('KAFKA', {}))
})

test('configSummary truncates long text with ellipsis', () => {
  const long = 'x'.repeat(30)
  assert.equal(configSummary('SOURCE_SQL', { sql: long }), 'x'.repeat(24) + '…')
  assert.equal(configSummary('FIELD_FILTER', { expression: long }), 'x'.repeat(24) + '…')
  const exact = 'y'.repeat(24)
  assert.equal(configSummary('SOURCE_SQL', { sql: exact }), exact)
})
