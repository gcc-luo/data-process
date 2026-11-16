import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { assetPage } from '../src/asset-pagination.js'
test('asset pages filter and clamp after deletion', () => {
  const values = [{name:'订单库'}, {name:'用户库'}, {name:'订单明细'}, {name:'仓库'}]
  assert.deepEqual(assetPage(values, '订单', 2, 1), { rows: [values[2]], total: 2, page: 2 })
  assert.deepEqual(assetPage(values.slice(0, 1), '', 3, 1), { rows: [values[0]], total: 1, page: 1 })
})
test('empty results remain on first page and case-insensitive search matches connection host', () => {
  assert.deepEqual(assetPage([{ name:'A', host:'DB.EXAMPLE' }], 'db.example', 1, 10).total, 1)
  assert.deepEqual(assetPage([], '', 8, 10), { rows: [], total: 0, page: 1 })
})
