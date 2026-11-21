import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { configToForm, formToConfig } from '../src/flow-config.js'

test('keyColumns round-trips between array and comma text', () => {
  const config = { sourceId: 2, table: 'copy', writeMode: 'UPSERT', keyColumns: ['a', 'b'], batchSize: 500 }
  const form = configToForm('SINK_TABLE', config)
  assert.equal(form.keyColumns, 'a, b')
  assert.deepEqual(formToConfig('SINK_TABLE', form), config)
})

test('formToConfig splits trims and drops empty keyColumns entries', () => {
  const config = formToConfig('SINK_TABLE', { keyColumns: ' a , b ,, ' })
  assert.deepEqual(config.keyColumns, ['a', 'b'])
  assert.deepEqual(formToConfig('SINK_TABLE', { keyColumns: '' }).keyColumns, [])
  assert.deepEqual(formToConfig('SINK_TABLE', {}).keyColumns, [])
})

test('formToConfig drops null and half-empty mappings and trims values', () => {
  const config = formToConfig('FIELD_MAP', { mappings: [null, { from: ' a ', to: ' b ' }, { from: 'x', to: '' }, { from: '', to: 'y' }, { from: '  ', to: 'z' }, { to: 'w' }] })
  assert.deepEqual(config.mappings, [{ from: 'a', to: 'b' }])
  assert.deepEqual(formToConfig('FIELD_MAP', {}).mappings, [])
})

test('configToForm defaults FIELD_MAP mappings to empty array', () => {
  assert.deepEqual(configToForm('FIELD_MAP', {}).mappings, [])
  assert.deepEqual(configToForm('FIELD_MAP', undefined).mappings, [])
})

test('configToForm deep-copies so form edits do not mutate node config', () => {
  const config = { mappings: [{ from: 'a', to: 'b' }] }
  const form = configToForm('FIELD_MAP', config)
  form.mappings[0].from = 'changed'
  form.mappings.push({ from: 'c', to: 'd' })
  assert.deepEqual(config.mappings, [{ from: 'a', to: 'b' }])
})

test('clean configs round-trip unchanged', () => {
  const map = { mappings: [{ from: 'a', to: 'b' }] }
  assert.deepEqual(formToConfig('FIELD_MAP', configToForm('FIELD_MAP', map)), map)
  const sink = { sourceId: 1, table: 't', writeMode: 'APPEND', keyColumns: [], batchSize: 500 }
  assert.deepEqual(formToConfig('SINK_TABLE', configToForm('SINK_TABLE', sink)), sink)
  const filter = { expression: 'amount > 0' }
  assert.deepEqual(formToConfig('FIELD_FILTER', configToForm('FIELD_FILTER', filter)), filter)
})
