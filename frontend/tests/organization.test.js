import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { allowedDepartmentParents, departmentName } from '../src/organization.js'

const departments = [
  { id: 1, name: '总公司', parentId: null },
  { id: 2, name: '研发', parentId: 1 },
  { id: 3, name: '平台组', parentId: 2 },
  { id: 4, name: '财务', parentId: 1 }
]
test('moving a department excludes itself and all descendants from parent choices', () => {
  assert.deepEqual(allowedDepartmentParents(departments, 2).map(d => d.id), [1, 4])
})
test('parent choice filtering terminates even if a corrupt hierarchy contains a cycle', () => {
  assert.deepEqual(allowedDepartmentParents([{ id: 1, parentId: 2 }, { id: 2, parentId: 1 }, { id: 3, parentId: null }], 1).map(d => d.id), [3])
})
test('department labels handle root and missing records', () => {
  assert.equal(departmentName(departments, null), '无上级部门')
  assert.equal(departmentName(departments, 2), '研发')
  assert.equal(departmentName(departments, 99), '未知部门（99）')
})
