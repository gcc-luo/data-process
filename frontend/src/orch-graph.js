/** 编排图客户端逻辑：节点 ID、默认配置、端口/连线守卫、自动布局、VueFlow 适配、历史栈。
 * 组件目录来自后端注册表（/api/orch/components），后端校验仍是权威。 */

export const CATEGORY_LABELS = { SOURCE: '来源组件', TRANSFORM: '处理组件', SINK: '目的组件', CONTROL: '控制组件', JOB: '作业组件' }
export const CATEGORY_ORDER = ['SOURCE', 'TRANSFORM', 'SINK', 'CONTROL', 'JOB']

export function indexComponents(components) {
  const map = new Map()
  for (const c of components || []) map.set(c.type, c)
  return map
}

/** 节点生效输出端口（镜像后端 activeOutputPorts：条件端口 + 分支动态端口）。 */
export function activeOutputPorts(component, config = {}) {
  const result = []
  for (const p of component?.outputPorts || []) {
    if (p.when) {
      const actual = config[p.when.field]
      const expected = p.when.equals
      const match = typeof expected === 'boolean' ? Boolean(actual) === expected : String(expected) === String(actual ?? '')
      if (!match) continue
    }
    if (p.dynamic) {
      for (const r of config.rules || []) if (r.key) result.push({ id: r.key, kind: 'DATA', label: r.label || r.key })
      if (config.defaultKey) result.push({ id: config.defaultKey, kind: 'DATA', label: '默认' })
      continue
    }
    result.push(p)
  }
  return result
}

export function inputPort(component, portId) {
  return (component?.inputPorts || []).find(p => p.id === portId) || null
}

/** 由注册表字段默认值生成节点初始配置。 */
export function defaultConfig(component) {
  const config = {}
  for (const f of component?.fields || []) {
    if (f.default !== undefined) config[f.key] = f.default
    else if (f.type === 'mappings') config[f.key] = []
    else if (f.type === 'params') config[f.key] = []
    else if (f.type === 'operations') config[f.key] = []
    else if (f.type === 'rules') config[f.key] = []
    else if (f.type === 'headers') config[f.key] = []
    else if (f.type === 'columns') config[f.key] = []
    else if (f.type === 'switch') config[f.key] = false
    else if (f.type === 'number') config[f.key] = null
    else if (f.type === 'source') config[f.key] = null
    else config[f.key] = ''
  }
  return config
}

export const DEFAULT_RETRY = { enabled: false, maxAttempts: 3, intervalSeconds: 30, backoff: 'FIXED', maxIntervalSeconds: 300, acknowledgeDuplicateRisk: false }

/** 补全节点默认字段（草稿 JSON 可能缺 retry/enabled/timeout 等），保证编辑器模型形状完整。 */
export function normalizeNode(n) {
  return {
    description: '',
    enabled: true,
    timeoutSeconds: 300,
    position: { x: 0, y: 0 },
    config: {},
    ...n,
    retry: { ...DEFAULT_RETRY, ...(n.retry || {}) },
    config: n.config || {},
    position: n.position || { x: 0, y: 0 }
  }
}

export function newNodeId(component, existing) {
  const prefix = 'n_' + String(component).split('.').pop().replace(/[^A-Za-z0-9]/g, '')
  const ids = new Set((existing || []).map(n => n.id))
  let i = 1
  while (ids.has(`${prefix}_${i}`)) i++
  return `${prefix}_${i}`
}

export function newNode(componentType, byType, existing, position) {
  const component = byType.get(componentType)
  return {
    id: newNodeId(componentType, existing),
    component: componentType,
    name: component?.name || componentType,
    description: '',
    enabled: true,
    position: position || { x: 80 + (existing || []).length * 40, y: 80 + (existing || []).length * 30 },
    timeoutSeconds: 300,
    retry: { enabled: false, maxAttempts: 3, intervalSeconds: 30, backoff: 'FIXED', maxIntervalSeconds: 300, acknowledgeDuplicateRisk: false },
    config: defaultConfig(component)
  }
}

/** 加入 from→to 是否形成环（沿现有边从 to 能否回到 from）。 */
export function wouldCycle(edges, from, to) {
  const next = new Map()
  for (const e of edges) {
    if (!next.has(e.from)) next.set(e.from, [])
    next.get(e.from).push(e.to)
  }
  const stack = [to]
  const seen = new Set()
  while (stack.length) {
    const k = stack.pop()
    if (k === from) return true
    if (seen.has(k)) continue
    seen.add(k)
    for (const n of next.get(k) || []) stack.push(n)
  }
  return false
}

/** 连线守卫：返回错误文案或 null。params: {source, sourceHandle, target, targetHandle}。 */
export function canConnect(definition, byType, params) {
  const nodes = definition.nodes || []
  const edges = definition.edges || []
  const { source, target, sourceHandle, targetHandle } = params
  if (!source || !target || source === target) return '节点不能连接自身'
  const sourceNode = nodes.find(n => n.id === source)
  const targetNode = nodes.find(n => n.id === target)
  if (!sourceNode || !targetNode) return '连线端点不存在'
  const sourceComp = byType.get(sourceNode.component)
  const targetComp = byType.get(targetNode.component)
  if (!sourceComp || !targetComp) return '未知组件'
  const outPort = activeOutputPorts(sourceComp, sourceNode.config).find(p => p.id === (sourceHandle || 'out'))
  if (!outPort) return '来源节点没有该输出端口'
  const inPort = inputPort(targetComp, targetHandle || 'in')
  if (!inPort) return '目标节点没有该输入端口'
  if (edges.some(e => e.from === source && e.fromPort === (sourceHandle || 'out') && e.to === target && e.toPort === (targetHandle || 'in'))) return '连线已存在'
  if (inPort.kind === 'DATA') {
    if (targetComp.category === 'SOURCE') return '来源节点不能有数据入边'
    const dataIn = edges.filter(e => e.to === target && e.kind === 'DATA').length
    const max = inPort.maxConnections ?? 1
    if (max >= 0 && dataIn >= max) return targetComp.type === 'control.merge' ? '汇聚端口连接数已达上限' : '该节点数据入边已达上限（多入边请使用数据汇聚）'
  }
  if (sourceComp.category === 'SINK') return '目的节点不能有出边'
  if (wouldCycle(edges, source, target)) return '连线会形成环，编排必须是有向无环图'
  return null
}

export function edgeKind(targetComp, targetHandle) {
  const p = inputPort(targetComp, targetHandle || 'in')
  return p?.kind === 'CONTROL' ? 'CONTROL' : 'DATA'
}

export function newEdgeId(existing) {
  const ids = new Set((existing || []).map(e => e.id))
  let i = 1
  while (ids.has(`e_${i}`)) i++
  return `e_${i}`
}

/** 从左到右的分层 DAG 布局：最长路径分层 + 同层按上游平均位置排序，仅改坐标。 */
export function autoLayout(definition, { xGap = 260, yGap = 130, x0 = 80, y0 = 80 } = {}) {
  const nodes = definition.nodes || []
  const edges = definition.edges || []
  if (!nodes.length) return definition
  const layer = new Map(nodes.map(n => [n.id, 0]))
  const indeg = new Map(nodes.map(n => [n.id, 0]))
  const next = new Map()
  for (const e of edges) {
    if (!layer.has(e.from) || !layer.has(e.to)) continue
    if (!next.has(e.from)) next.set(e.from, [])
    next.get(e.from).push(e.to)
    indeg.set(e.to, (indeg.get(e.to) || 0) + 1)
  }
  const queue = nodes.filter(n => (indeg.get(n.id) || 0) === 0).map(n => n.id)
  const order = []
  const remaining = new Map(indeg)
  while (queue.length) {
    const k = queue.shift()
    order.push(k)
    for (const n of next.get(k) || []) {
      layer.set(n, Math.max(layer.get(n), layer.get(k) + 1))
      remaining.set(n, remaining.get(n) - 1)
      if (remaining.get(n) === 0) queue.push(n)
    }
  }
  for (const n of nodes) if (!order.includes(n.id)) order.push(n.id)
  const groups = new Map()
  for (const id of order) {
    const l = layer.get(id)
    if (!groups.has(l)) groups.set(l, [])
    groups.get(l).push(id)
  }
  const posY = new Map()
  const sortedLayers = [...groups.keys()].sort((a, b) => a - b)
  for (const l of sortedLayers) {
    const ids = groups.get(l)
    ids.sort((a, b) => {
      const upA = edges.filter(e => e.to === a).map(e => posY.get(e.from) ?? 0)
      const upB = edges.filter(e => e.to === b).map(e => posY.get(e.from) ?? 0)
      const avgA = upA.length ? upA.reduce((s, v) => s + v, 0) / upA.length : 0
      const avgB = upB.length ? upB.reduce((s, v) => s + v, 0) / upB.length : 0
      return avgA - avgB
    })
    ids.forEach((id, i) => posY.set(id, y0 + i * yGap))
  }
  const nodesById = new Map(nodes.map(n => [n.id, n]))
  for (const id of order) {
    const n = nodesById.get(id)
    if (n) n.position = { x: x0 + layer.get(id) * xGap, y: posY.get(id) }
  }
  return definition
}

/** 节点摘要（画布卡片副标题）。 */
export function configSummary(component, config = {}) {
  const c = config || {}
  const brief = (t, fb) => { const s = t || fb; return s.length > 26 ? s.slice(0, 26) + '…' : s }
  switch (component?.type) {
    case 'source.mysql': case 'source.postgresql': case 'source.oracle':
      return c.readMode === 'SQL' ? brief(c.sql, '未配置 SQL') : (c.table || '未配置表')
    case 'sink.mysql': case 'sink.postgresql': case 'sink.oracle':
      return `${c.table || '未配置表'} · ${c.writeMode === 'UPSERT' ? 'UPSERT' : '追加'}`
    case 'transform.sql': return brief(c.sql, '未配置 SQL')
    case 'transform.field-map': return `${(c.mappings || []).length} 条映射`
    case 'transform.filter': return brief(c.expression, '未配置条件')
    case 'transform.clean': return `${(c.operations || []).length} 个清洗步骤`
    case 'transform.mask': return c.field || '未配置字段'
    case 'transform.calc': return c.field || '未配置字段'
    case 'control.branch': return `${(c.rules || []).length} 个分支 + 默认`
    case 'control.merge': return 'UNION 汇聚'
    case 'source.http': return brief(c.url, '未配置 URL')
    case 'sink.http': return brief(c.url, '未配置 URL')
    default: return component?.description || ''
  }
}

/** 节点配置是否完整（用于画布“已配置/待配置”标记，镜像后端必填规则的核心项）。 */
export function isConfigured(component, config = {}) {
  if (!component) return false
  for (const f of component.fields || []) {
    if (!f.required) continue
    if (f.when) {
      const actual = config[f.when.field]
      const expected = f.when.equals
      const match = typeof expected === 'boolean' ? Boolean(actual) === expected : String(expected) === String(actual ?? '')
      if (!match) continue
    }
    const v = config[f.key]
    if (v == null || v === '') return false
    if (Array.isArray(v) && v.length === 0) return false
  }
  return true
}

/** VueFlow 节点适配。nodeStates: Map nodeId→state；issues: Set nodeId 有校验错误。 */
export function toVueNodes(definition, byType, { nodeStates = new Map(), selectedId = null, issueIds = new Set(), running = false } = {}) {
  return (definition.nodes || []).map(n => {
    const component = byType.get(n.component)
    return {
      id: n.id,
      type: 'orchNode',
      position: n.position || { x: 0, y: 0 },
      selected: n.id === selectedId,
      data: {
        node: n, component,
        category: component?.category || 'TRANSFORM',
        summary: configSummary(component, n.config),
        configured: isConfigured(component, n.config),
        enabled: n.enabled !== false,
        state: nodeStates.get(n.id) || null,
        hasIssue: issueIds.has(n.id),
        readonly: running
      }
    }
  })
}

export function toVueEdges(definition, { selectedEdgeId = null } = {}) {
  return (definition.edges || []).map(e => ({
    id: e.id,
    source: e.from,
    target: e.to,
    sourceHandle: e.fromPort,
    targetHandle: e.toPort,
    type: 'smoothstep',
    animated: e.kind === 'DATA',
    style: { stroke: e.kind === 'CONTROL' ? '#e6a23c' : '#7aa7d6', strokeWidth: 1.6, strokeDasharray: e.kind === 'CONTROL' ? '5 4' : undefined },
    markerEnd: { type: 'arrowclosed', color: e.kind === 'CONTROL' ? '#e6a23c' : '#7aa7d6' },
    label: e.kind === 'CONTROL' ? '依赖' : undefined,
    selected: e.id === selectedEdgeId
  }))
}

/** 预览转表格：列 prop 用索引避免重名覆盖。 */
export function previewTable(preview) {
  const columns = (preview?.columns || []).map((c, j) => ({ prop: `col_${j}`, label: c }))
  const rows = (preview?.rows || []).map(r => Object.fromEntries(columns.map((c, j) => [c.prop, r[j]])))
  return { columns, rows }
}

/** 客户端 SQL 命名占位符解析（镜像后端 SqlParameters，尊重引号/注释，跳过 :: casts）。 */
export function sqlPlaceholderNames(sql) {
  const names = []
  if (!sql) return names
  let i = 0
  while (i < sql.length) {
    const c = sql[i]
    if (c === "'" || c === '"' || c === '`') { const end = sql.indexOf(c, i + 1); if (end < 0) break; i = end + 1; continue }
    if (c === '-' && sql[i + 1] === '-') { const end = sql.indexOf('\n', i); if (end < 0) break; i = end; continue }
    if (c === '/' && sql[i + 1] === '*') { const end = sql.indexOf('*/', i + 2); if (end < 0) break; i = end + 2; continue }
    if (c === ':' && sql[i + 1] !== ':' && (i === 0 || sql[i - 1] !== ':') && /[A-Za-z_]/.test(sql[i + 1] || '')) {
      let j = i + 1
      while (j < sql.length && /[A-Za-z0-9_]/.test(sql[j])) j++
      const name = sql.slice(i + 1, j)
      if (!names.includes(name)) names.push(name)
      i = j; continue
    }
    i++
  }
  return names
}

/** 简易撤销/重做栈（快照 JSON），上限 30。 */
export function createHistory(limit = 30) {
  const undo = []
  const redo = []
  return {
    push(snapshot) { undo.push(JSON.stringify(snapshot)); if (undo.length > limit) undo.shift(); redo.length = 0 },
    undo(current) { if (!undo.length) return null; redo.push(JSON.stringify(current)); return JSON.parse(undo.pop()) },
    redo(current) { if (!redo.length) return null; undo.push(JSON.stringify(current)); return JSON.parse(redo.pop()) },
    canUndo: () => undo.length > 0,
    canRedo: () => redo.length > 0
  }
}

/** 客户端基础校验（结构），返回 issue 数组；后端 /validate 仍权威。 */
export function clientValidate(definition, byType) {
  const issues = []
  const nodes = definition.nodes || []
  const edges = definition.edges || []
  if (!nodes.length) issues.push({ code: 'WORKFLOW_EMPTY', targetType: 'workflow', targetId: '', message: '编排至少需要一个节点' })
  const ids = new Set()
  for (const n of nodes) {
    if (ids.has(n.id)) issues.push({ code: 'NODE_DUPLICATE_ID', targetType: 'node', targetId: n.id, message: '节点 ID 重复' })
    ids.add(n.id)
    const comp = byType.get(n.component)
    if (!comp) issues.push({ code: 'COMPONENT_NOT_SUPPORTED', targetType: 'node', targetId: n.id, message: '未知组件 ' + n.component })
    else if (!comp.enabled) issues.push({ code: 'COMPONENT_NOT_SUPPORTED', targetType: 'node', targetId: n.id, message: comp.name + ' 即将支持' })
    else if (!isConfigured(comp, n.config)) issues.push({ code: 'NODE_REQUIRED_FIELD', targetType: 'node', targetId: n.id, message: '节点配置不完整' })
  }
  for (const e of edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) issues.push({ code: 'EDGE_UNKNOWN_NODE', targetType: 'edge', targetId: e.id, message: '连线端点不存在' })
    if (e.from === e.to) issues.push({ code: 'EDGE_SELF_LOOP', targetType: 'edge', targetId: e.id, message: '节点不能连接自身' })
  }
  // cycle detection
  const indeg = new Map(nodes.map(n => [n.id, 0]))
  const next = new Map()
  for (const e of edges) if (ids.has(e.from) && ids.has(e.to) && e.from !== e.to) { next.set(e.from, [...(next.get(e.from) || []), e.to]); indeg.set(e.to, indeg.get(e.to) + 1) }
  const queue = [...indeg.entries()].filter(([, v]) => v === 0).map(([k]) => k)
  let visited = 0
  while (queue.length) { const k = queue.shift(); visited++; for (const n of next.get(k) || []) { indeg.set(n, indeg.get(n) - 1); if (indeg.get(n) === 0) queue.push(n) } }
  if (nodes.length && visited !== nodes.length) issues.push({ code: 'WORKFLOW_CYCLE_DETECTED', targetType: 'workflow', targetId: '', message: '编排存在环' })
  return issues
}