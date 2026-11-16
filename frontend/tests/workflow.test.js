import test from 'node:test'
import assert from 'node:assert/strict'
import { addTask, addEdge, removeTask } from '../src/workflow.js'

test('task IDs are unique and removal deletes connected edges', () => {
  let state = { tasks: [], edges: [] }
  state = addTask(state, '导入')
  state = addTask(state, '加工')
  assert.notEqual(state.tasks[0].id, state.tasks[1].id)
  state = addEdge(state, state.tasks[0].id, state.tasks[1].id)
  state = removeTask(state, state.tasks[0].id)
  assert.equal(state.edges.length, 0)
  assert.equal(state.tasks.length, 1)
})

test('rejects self, duplicate and missing dependencies', () => {
  let state = addTask(addTask({tasks: [], edges: []}, 'A'), 'B')
  let [a, b] = state.tasks.map(task => task.id)
  assert.throws(() => addEdge(state, a, a))
  assert.throws(() => addEdge(state, a, 'missing'))
  state = addEdge(state, a, b)
  assert.throws(() => addEdge(state, a, b))
})

test('task creation works without randomUUID when secure random bytes are available', () => {
  const generator = { getRandomValues(bytes) { bytes.fill(5); return bytes } }
  const state = addTask({ tasks: [], edges: [] }, 'A', generator)
  assert.equal(state.tasks[0].id, '05050505-0505-4505-8505-050505050505')
})

test('task removal clears selected dependencies that refer to it', async () => {
  const { clearRemovedSelection } = await import('../src/workflow.js')
  assert.deepEqual(clearRemovedSelection('a', 'b', 'a'), { from: '', to: 'b' })
  assert.deepEqual(clearRemovedSelection('a', 'b', 'b'), { from: 'a', to: '' })
})

test('edits invalidate an in-flight validation result', async () => {
  const { createValidationGuard } = await import('../src/workflow.js')
  const guard = createValidationGuard()
  const token = guard.start()
  assert.equal(guard.isCurrent(token), true)
  guard.changed()
  assert.equal(guard.isCurrent(token), false)
  const next = guard.start()
  assert.equal(guard.isCurrent(next), true)
})

test('detects unsaved workflow name, dependency and layout changes', async () => {
  const { workflowDraftKey, workflowDraftChanged } = await import('../src/workflow.js')
  const initial = { name: '日报', graph: { tasks: [{ id: 'a', name: '抽取', x: 10, y: 20 }], edges: [] } }
  const baseline = workflowDraftKey(initial)
  assert.equal(workflowDraftChanged(initial, baseline), false)
  assert.equal(workflowDraftChanged({ ...initial, name: '日报新版本' }, baseline), true)
  assert.equal(workflowDraftChanged({ ...initial, graph: { ...initial.graph, tasks: [{ ...initial.graph.tasks[0], x: 30 }] } }, baseline), true)
})
