/** 收集目录子树 id 集合（含根、防环）；rootId 为 null 时返回 null，表示不过滤（全部任务）。 */
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

/** 按目录子树、名称模糊（忽略大小写与首尾空白）、运行状态组合筛选任务。 */
export function filterTasks(tasks, { directories = [], directoryId = null, name = '', runState = '' } = {}) {
  const ids = subtreeIds(directories, directoryId)
  const keyword = String(name ?? '').trim().toLowerCase()
  return tasks.filter(task =>
    (ids === null || (task.directoryId != null && ids.has(task.directoryId))) &&
    (!keyword || String(task.name ?? '').toLowerCase().includes(keyword)) &&
    (!runState || task.runState === runState))
}

/** 汇总批量启停结果为中文提示；失败项无 error 时兜底“未知错误”，空结果提示无可操作任务。 */
export function batchSummary(results) {
  const succeeded = results.filter(result => result.ok).map(result => result.name)
  const failed = results.filter(result => !result.ok)
  const parts = []
  if (succeeded.length) parts.push(`成功：${succeeded.join('、')}`)
  if (failed.length) parts.push(`失败：${failed.map(result => `${result.name}（${result.error || '未知错误'}）`).join('、')}`)
  return parts.join('；') || '没有可操作的任务'
}

/** 先序扁平化目录树为 { id, parentId, sortOrder }，供 el-tree 拖拽后全量排序提交。 */
export function flattenTree(nodes, parentId = null) {
  const flat = []
  nodes.forEach((node, index) => {
    flat.push({ id: node.id, parentId, sortOrder: index })
    if (node.children?.length) flat.push(...flattenTree(node.children, node.id))
  })
  return flat
}
