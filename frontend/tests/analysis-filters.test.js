import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { analysisQuery, changeFilterOperation, changeFilterType } from '../src/analysis-filters.js'

test('constructs multiple typed filter conditions without retaining mutable input', () => {
  const groups = ['department']; const filters = [{ field: 'amount', operation: 'GTE', value: 12.5 }, { field: 'active', operation: 'EQ', value: false }, { field: 'removed', operation: 'IS_NULL', value: 'ignored' }]
  const query = analysisQuery({ groups, metricOperation: 'COUNT', metricField: '', filters, limit: 100, sortField: '', sortDirection: 'DESC' })
  assert.deepEqual(query.filters, [{ field: 'amount', operation: 'GTE', value: 12.5 }, { field: 'active', operation: 'EQ', value: false }, { field: 'removed', operation: 'IS_NULL', value: null }])
  groups.push('region'); filters[0].value = 99
  assert.deepEqual(query.groups, ['department']); assert.equal(query.filters[0].value, 12.5)
})
test('rejects incomplete, unsupported and unsafe filter values', () => {
  const base = { groups: [], metricOperation: 'COUNT', metricField: '', limit: 100, sortField: '', sortDirection: 'ASC' }
  assert.throws(() => analysisQuery({ ...base, filters: [{ field: '', operation: 'EQ', value: '' }] }), /字段/)
  assert.throws(() => analysisQuery({ ...base, filters: [{ field: 'x', operation: 'SQL', value: '' }] }), /筛选/)
  assert.throws(() => analysisQuery({ ...base, filters: [{ field: 'x', operation: 'EQ', value: NaN }] }), /筛选/)
  assert.throws(() => analysisQuery({ ...base, filters: Array.from({length:21}, () => ({field:'x',operation:'EQ',value:1})) }), /20/)
})
test('switching operation and types keeps valid explicit scalar values', () => {
  const filter = { field: 'x', operation: 'EQ', value: 'hello' }
  changeFilterType(filter, 'number'); assert.equal(filter.value, '0')
  changeFilterType(filter, 'boolean'); assert.equal(filter.value, false)
  changeFilterOperation(filter, 'IS_NULL'); assert.equal(filter.value, null)
  changeFilterOperation(filter, 'EQ'); assert.equal(filter.value, '')
})

test('numeric filters keep raw input and reject precision loss before executing', () => {
  const base = { groups: [], metricOperation: 'COUNT', metricField: '', limit: 100, sortField: '', sortDirection: 'ASC' }
  const filter = { field: 'id', operation: 'EQ', valueType: 'number', value: '9007199254740993' }
  assert.throws(() => analysisQuery({ ...base, filters: [filter] }), /精度/)
  assert.equal(filter.value, '9007199254740993')
  filter.value = '1.234567890123456789'; assert.throws(() => analysisQuery({ ...base, filters: [filter] }), /精度/)
  filter.value = '12.5'; assert.equal(analysisQuery({ ...base, filters: [filter] }).filters[0].value, 12.5)
})
