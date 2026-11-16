function createId(generator) {
  if (typeof generator?.randomUUID === 'function') return generator.randomUUID()
  if (typeof generator?.getRandomValues !== 'function') throw new Error('浏览器不支持安全随机数，无法创建任务')
  const bytes = generator.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export function addTask(state, name, generator = globalThis.crypto) {
  if (!name.trim()) throw new Error('请输入任务名称')
  return { ...state, tasks: [...state.tasks, { id: createId(generator), name: name.trim() }] }
}
export function addEdge(state, from, to) {
  if (from === to || !state.tasks.some(task => task.id === from) || !state.tasks.some(task => task.id === to)) throw new Error('请选择两个不同的任务')
  if (state.edges.some(edge => edge.from === from && edge.to === to)) throw new Error('依赖关系已存在')
  return { ...state, edges: [...state.edges, { from, to }] }
}
export function removeTask(state, id) {
  return { tasks: state.tasks.filter(task => task.id !== id), edges: state.edges.filter(edge => edge.from !== id && edge.to !== id) }
}

export function clearRemovedSelection(from, to, id) {
  return { from: from === id ? '' : from, to: to === id ? '' : to }
}
export function createValidationGuard() {
  let revision = 0
  return { start() { return ++revision }, changed() { revision++ }, isCurrent(token) { return token === revision } }
}
export function workflowDraftKey(workflow) { return JSON.stringify(workflow) }
export function workflowDraftChanged(workflow, baseline) { return workflowDraftKey(workflow) !== baseline }
