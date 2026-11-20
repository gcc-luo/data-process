export function subtreeIds(directories, rootId) {
  if (rootId == null) return null
  const ids = new Set()
  const queue = [rootId]
  while (queue.length) {
    const id = queue.shift()
    if (ids.has(id)) continue
    ids.add(id)
    for (const directory of directories) if (directory.parentId === id) queue.push(directory.id)
  }
  return ids
}

export function filterTasks(tasks, { directories = [], directoryId = null, name = '', runState = '' } = {}) {
  const ids = subtreeIds(directories, directoryId)
  const keyword = name.trim().toLowerCase()
  return tasks.filter(task =>
    (ids === null || (task.directoryId != null && ids.has(task.directoryId))) &&
    (!keyword || task.name.toLowerCase().includes(keyword)) &&
    (!runState || task.runState === runState))
}

export function batchSummary(results) {
  const succeeded = results.filter(result => result.ok).map(result => result.name)
  const failed = results.filter(result => !result.ok)
  const parts = []
  if (succeeded.length) parts.push(`成功：${succeeded.join('、')}`)
  if (failed.length) parts.push(`失败：${failed.map(result => `${result.name}（${result.error}）`).join('、')}`)
  return parts.join('；') || '没有可操作的任务'
}

export function flattenTree(nodes, parentId = null) {
  const flat = []
  nodes.forEach((node, index) => {
    flat.push({ id: node.id, parentId, sortOrder: index })
    if (node.children?.length) flat.push(...flattenTree(node.children, node.id))
  })
  return flat
}
