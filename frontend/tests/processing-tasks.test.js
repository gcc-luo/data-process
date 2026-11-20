import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { subtreeIds, filterTasks, batchSummary, batchParts, flattenTree } from '../src/processing-tasks.js'

const directories = [
  { id: 1, parentId: null, name: '订单域', sortOrder: 0 },
  { id: 2, parentId: 1, name: '明细', sortOrder: 0 },
  { id: 3, parentId: 2, name: '日明细', sortOrder: 0 },
  { id: 4, parentId: null, name: '用户域', sortOrder: 1 }
]
const tasks = [
  { id: 10, name: '订单加工', directoryId: 2, runState: 'STOPPED' },
  { id: 11, name: '日汇总', directoryId: 3, runState: 'RUNNING' },
  { id: 12, name: '用户同步', directoryId: 4, runState: 'STOPPED' },
  { id: 13, name: '未分类任务', directoryId: null, runState: 'STOPPED' }
]

test('subtree includes descendants and root filter means all', () => {
  assert.deepEqual([...subtreeIds(directories, 1)].sort((a, b) => a - b), [1, 2, 3])
  assert.equal(subtreeIds(directories, null), null)
})

test('subtree terminates on cyclic directories and includes cycle members', () => {
  const cyclic = [
    { id: 1, parentId: 2 },
    { id: 2, parentId: 1 },
    { id: 3, parentId: 2 }
  ]
  assert.deepEqual([...subtreeIds(cyclic, 1)].sort((a, b) => a - b), [1, 2, 3])
})

test('directory filter includes subtree and uncategorized only under root', () => {
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 1 }).map(t => t.id), [10, 11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null }).map(t => t.id), [10, 11, 12, 13])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 4 }).map(t => t.id), [12])
})

test('name and run state filters combine with directory', () => {
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: 1, name: '日' }).map(t => t.id), [11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null, runState: 'RUNNING' }).map(t => t.id), [11])
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null, name: '订单', runState: 'RUNNING' }), [])
})

test('name filter tolerates null and matches case-insensitively with whitespace', () => {
  assert.deepEqual(filterTasks(tasks, { directories, directoryId: null, name: null }).map(t => t.id), [10, 11, 12, 13])
  const named = [...tasks, { id: 14, name: 'order加工', directoryId: null, runState: 'STOPPED' }, { id: 15, name: null, directoryId: null, runState: 'STOPPED' }]
  assert.deepEqual(filterTasks(named, { directories, directoryId: null, name: '  ORD  ' }).map(t => t.id), [14])
})

test('filterTasks works without options', () => {
  assert.deepEqual(filterTasks(tasks).map(t => t.id), [10, 11, 12, 13])
})

test('batch summary lists successes and failures', () => {
  const message = batchSummary([
    { name: '甲', ok: true },
    { name: '乙', ok: false, error: '任务已在运行' },
    { name: '丙', ok: true }
  ])
  assert.equal(message, '成功：甲、丙；失败：乙（任务已在运行）')
  assert.equal(batchSummary([]), '没有可操作的任务')
})

test('batch summary falls back to unknown error and handles single branches', () => {
  assert.equal(batchSummary([{ name: '丁', ok: false }]), '失败：丁（未知错误）')
  assert.equal(batchSummary([{ name: '甲', ok: true }, { name: '乙', ok: true }]), '成功：甲、乙')
  assert.equal(batchSummary([{ name: '甲', ok: false, error: '超时' }, { name: '乙', ok: false, error: '已在运行' }]), '失败：甲（超时）、乙（已在运行）')
})

test('batch parts splits successes and failures for separate alerts', () => {
  assert.deepEqual(batchParts([
    { name: '甲', ok: true },
    { name: '乙', ok: false, error: '任务已在运行' },
    { name: '丙', ok: true }
  ]), { okText: '成功：甲、丙', failText: '失败：乙（任务已在运行）' })
})

test('batch parts handles all-success, all-failure and empty results', () => {
  assert.deepEqual(batchParts([{ name: '甲', ok: true }, { name: '乙', ok: true }]), { okText: '成功：甲、乙', failText: '' })
  assert.deepEqual(batchParts([{ name: '甲', ok: false, error: '超时' }, { name: '丁', ok: false }]), { okText: '', failText: '失败：甲（超时）、丁（未知错误）' })
  assert.deepEqual(batchParts([]), { okText: '', failText: '' })
})

test('flatten tree produces parent links and sibling order', () => {
  const tree = [
    { id: 1, children: [{ id: 2, children: [{ id: 3 }] }] },
    { id: 4 }
  ]
  assert.deepEqual(flattenTree(tree), [
    { id: 1, parentId: null, sortOrder: 0 },
    { id: 2, parentId: 1, sortOrder: 0 },
    { id: 3, parentId: 2, sortOrder: 0 },
    { id: 4, parentId: null, sortOrder: 1 }
  ])
  assert.deepEqual(flattenTree([]), [])
})
