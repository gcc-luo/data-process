/** FLOW 图客户端结构校验（后端保存期仍权威校验）与 VueFlow 适配。 */
import { COMPONENTS, defaultConfig } from './flow-components.js'
export const SOURCES = COMPONENTS.filter(c => c.group === 'source').map(c => c.component)
export const SINKS = COMPONENTS.filter(c => c.group === 'sink').map(c => c.component)
const KNOWN = new Set(COMPONENTS.map(c => c.component))
const IDENT = /^[A-Za-z_][A-Za-z0-9_$]*$/
const TABLE = /^[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)?$/
/** 单节点配置校验（与后端 validateConfig 镜像）。 */
export function validateNodeConfig(n) {
  const errors = []
  const c = n.config || {}
  if (SOURCES.includes(n.component) || SINKS.includes(n.component)) {
    if (!(Number.isInteger(c.sourceId) && c.sourceId >= 1)) errors.push(`节点 ${n.key} 数据源无效`)
    if (!c.table || !TABLE.test(c.table)) errors.push(`节点 ${n.key} 表名无效`)
  }
  if (n.component === 'SOURCE_SQL' && !c.sql) errors.push(`节点 ${n.key} 未配置 SQL`)
  if (n.component === 'FIELD_MAP' && !(c.mappings || []).length) errors.push(`节点 ${n.key} 未配置映射`)
  if ((n.component === 'FIELD_FILTER' || n.component === 'FIELD_CALC') && !c.expression) errors.push(`节点 ${n.key} 未配置表达式`)
  if (n.component === 'FIELD_MASK' && !c.field) errors.push(`节点 ${n.key} 未配置脱敏字段`)
  if (n.component === 'FIELD_MASK' && c.field && !IDENT.test(c.field)) errors.push(`节点 ${n.key} 脱敏字段名无效`)
  if (n.component === 'FIELD_CALC' && !IDENT.test(c.field || '')) errors.push(`节点 ${n.key} 新字段名无效`)
  if (n.component === 'SINK_TABLE' && c.writeMode === 'UPSERT' && !(c.keyColumns || []).length) errors.push(`节点 ${n.key} UPSERT 需唯一键`)
  return errors
}
export function validateFlow(graph) {
  const errors = []
  const nodes = graph.nodes || []
  const edges = graph.edges || []
  if (!nodes.length) return ['流程至少需要一个节点']
  const keys = new Set()
  for (const n of nodes) {
    if (!n.key || keys.has(n.key)) errors.push(`节点 key 重复或缺失: ${n.key}`)
    keys.add(n.key)
  }
  const indeg = new Map(), outdeg = new Map()
  const legalEdges = []
  for (const e of edges) {
    if (!keys.has(e.from) || !keys.has(e.to) || e.from === e.to) { errors.push(`非法连线: ${e.from} → ${e.to}`); continue }
    legalEdges.push(e)
    outdeg.set(e.from, (outdeg.get(e.from) || 0) + 1)
    indeg.set(e.to, (indeg.get(e.to) || 0) + 1)
  }
  let hasSource = false, hasSink = false
  for (const n of nodes) {
    if (!KNOWN.has(n.component)) errors.push(`未知组件: ${n.key}`)
    const i = indeg.get(n.key) || 0, o = outdeg.get(n.key) || 0
    if (SOURCES.includes(n.component)) { hasSource = true; if (i) errors.push(`来源节点不能有入边: ${n.key}`) }
    else if (SINKS.includes(n.component)) { hasSink = true; if (o) errors.push(`目标节点不能有出边: ${n.key}`); if (i !== 1) errors.push(`目标节点需恰一个入边: ${n.key}`) }
    else if (i !== 1) errors.push(`预处理节点需恰一个入边: ${n.key}`)
    if (!SINKS.includes(n.component) && o > 1) errors.push(`节点只能有一条出边: ${n.key}`)
    errors.push(...validateNodeConfig(n))
  }
  if (!hasSource) errors.push('流程需至少一个来源组件')
  if (!hasSink) errors.push('流程需至少一个目标组件')
  const remaining = new Map(nodes.map(n => [n.key, indeg.get(n.key) || 0]))
  const queue = nodes.filter(n => (indeg.get(n.key) || 0) === 0).map(n => n.key)
  let visited = 0
  while (queue.length) {
    const k = queue.shift(); visited++
    for (const e of legalEdges) if (e.from === k) { remaining.set(e.to, remaining.get(e.to) - 1); if (remaining.get(e.to) === 0) queue.push(e.to) }
  }
  if (visited !== nodes.length) errors.push('流程存在环')
  const reach = new Set(nodes.filter(n => SINKS.includes(n.component)).map(n => n.key))
  const stack = [...reach]
  while (stack.length) { const k = stack.pop(); for (const e of legalEdges) if (e.to === k && !reach.has(e.from)) { reach.add(e.from); stack.push(e.from) } }
  for (const n of nodes) if (!reach.has(n.key)) errors.push(`节点未连接到目标: ${n.key}`)
  return errors
}
export function toVueNodes(graph) {
  return (graph.nodes || []).map(n => ({ id: n.key, type: 'flowNode', position: n.position || { x: 0, y: 0 }, data: { key: n.key, component: n.component, config: n.config } }))
}
export function toVueEdges(graph) {
  return (graph.edges || []).map(e => ({ id: `${e.from}->${e.to}`, source: e.from, target: e.to, type: 'smoothstep', markerEnd: { type: 'arrowclosed', color: '#728a88' } }))
}
/** 连线守卫：返回错误文案或 null（与 validateFlow 结构规则一致）。 */
export function canConnect(graph, params) {
  const nodes = graph.nodes || [], edges = graph.edges || []
  if (!params.source || !params.target || params.source === params.target) return '节点不能连接自身'
  if (edges.some(e => e.from === params.source && e.to === params.target)) return '连线已存在'
  const target = nodes.find(n => n.key === params.target)
  const source = nodes.find(n => n.key === params.source)
  if (target && SOURCES.includes(target.component)) return '来源节点不能有入边'
  if (source && SINKS.includes(source.component)) return '目标节点不能有出边'
  if (target && edges.some(e => e.to === params.target)) return '该节点已有入边（预处理/目标仅允许单入边）'
  if (source && edges.some(e => e.from === params.source)) return '该节点已有出边（来源/预处理仅允许单出边）'
  return null
}
/** 预览转表格：列 prop 用索引，重名列不互相覆盖。 */
export function previewTable(preview) {
  const columns = (preview?.columns || []).map((c, j) => ({ prop: `col_${j}`, label: c }))
  const rows = (preview?.rows || []).map(r => Object.fromEntries(columns.map((c, j) => [c.prop, r[j]])))
  return { columns, rows }
}
export function newNode(component, existing, position) {
  const prefix = component.toLowerCase().replace(/_/g, '')
  let i = 1
  const keys = new Set((existing || []).map(n => n.key))
  while (keys.has(`${prefix}${i}`)) i++
  return { key: `${prefix}${i}`, component, config: defaultConfig(component), position: position || { x: 40 + (existing || []).length * 40, y: 40 + (existing || []).length * 30 } }
}
