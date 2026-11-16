import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { ref, nextTick } from 'vue'
import { usePagination } from '../src/pagination.js'
test('paginates loaded rows and clamps the current page when rows shrink', async () => {
  const rows = ref(Array.from({length: 23}, (_, i) => i + 1))
  const p = usePagination(rows, 10)
  assert.deepEqual(p.visible.value, [1,2,3,4,5,6,7,8,9,10])
  p.page.value = 3
  assert.deepEqual(p.visible.value, [21,22,23])
  rows.value = rows.value.slice(0, 9)
  await nextTick()
  assert.equal(p.page.value, 1)
  assert.deepEqual(p.visible.value, [1,2,3,4,5,6,7,8,9])
})
