import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { COMPONENTS, componentDef, defaultConfig } from '../src/flow-components.js'

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
