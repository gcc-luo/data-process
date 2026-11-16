import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { chartColumns } from '../src/report.js'
test('aggregated charts use the metric rather than a numeric grouping column', () => {
  assert.deepEqual(chartColumns([{ department: 12, metric_0: 58 }]), { numeric: 'metric_0', category: 'department' })
})
test('charts without numeric data are rejected', () => {
  assert.deepEqual(chartColumns([{ name: 'a' }]), { numeric: undefined, category: 'name' })
})
