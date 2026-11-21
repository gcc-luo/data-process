/** FLOW 图客户端结构校验（后端保存期仍权威校验）与 VueFlow 适配。 */
import { defaultConfig } from './flow-components.js'
const SOURCES = ['SOURCE_TABLE', 'SOURCE_SQL']
const SINKS = ['SINK_TABLE']
const IDENT = /^[A-Za-z_][A-Za-z0-9_$]*$/
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
  for (const e of edges) {
    if (!keys.has(e.from) || !keys.has(e.to) || e.from === e.to) { errors.push(`非法连线: ${e.from} → ${e.to}`); continue }
    outdeg.set(e.from, (outdeg.get(e.from) || 0) + 1)
    indeg.set(e.to, (indeg.get(e.to) || 0) + 1)
  }
  let hasSource = false, hasSink = false
  for (const n of nodes) {
    const i = indeg.get(n.key) || 0, o = outdeg.get(n.key) || 0
    if (SOURCES.includes(n.component)) { hasSource = true; if (i) errors.push(`来源节点不能有入边: ${n.key}`) }
    else if (SINKS.includes(n.component)) { hasSink = true; if (o) errors.push(`目标节点不能有出边: ${n.key}`); if (i !== 1) errors.push(`目标节点需恰一个入边: ${n.key}`) }
    else if (i !== 1) errors.push(`预处理节点需恰一个入边: ${n.key}`)
    if (!SINKS.includes(n.component) && o > 1) errors.push(`节点只能有一条出边: ${n.key}`)
    const c = n.config || {}
    if (SOURCES.includes(n.component) || SINKS.includes(n.component)) {
      if (!c.sourceId) errors.push(`节点 ${n.key} 未选择数据源`)
      if (!c.table || !/^[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)?$/.test(c.table)) errors.push(`节点 ${n.key} 表名无效`)
    }
    if (n.component === 'SOURCE_SQL' && !c.sql) errors.push(`节点 ${n.key} 未配置 SQL`)
    if (n.component === 'FIELD_MAP' && !(c.mappings || []).length) errors.push(`节点 ${n.key} 未配置映射`)
    if ((n.component === 'FIELD_FILTER' || n.component === 'FIELD_CALC') && !c.expression) errors.push(`节点 ${n.key} 未配置表达式`)
    if (n.component === 'FIELD_MASK' && !c.field) errors.push(`节点 ${n.key} 未配置脱敏字段`)
    if (n.component === 'FIELD_CALC' && !IDENT.test(c.field || '')) errors.push(`节点 ${n.key} 新字段名无效`)
    if (n.component === 'SINK_TABLE' && c.writeMode === 'UPSERT' && !(c.keyColumns || []).length) errors.push(`节点 ${n.key} UPSERT 需唯一键`)
  }
  if (!hasSource) errors.push('流程需至少一个来源组件')
  if (!hasSink) errors.push('流程需至少一个目标组件')
  const indeg2 = new Map(nodes.map(n => [n.key, 0]))
  for (const e of edges) if (indeg2.has(e.to)) indeg2.set(e.to, indeg2.get(e.to) + 1)
  const queue = nodes.filter(n => indeg2.get(n.key) === 0).map(n => n.key)
  let visited = 0
  while (queue.length) {
    const k = queue.shift(); visited++
    for (const e of edges) if (e.from === k) { indeg2.set(e.to, indeg2.get(e.to) - 1); if (indeg2.get(e.to) === 0) queue.push(e.to) }
  }
  if (visited !== nodes.length) errors.push('流程存在环')
  const reach = new Set(nodes.filter(n => SINKS.includes(n.component)).map(n => n.key))
  const stack = [...reach]
  while (stack.length) { const k = stack.pop(); for (const e of edges) if (e.to === k && !reach.has(e.from)) { reach.add(e.from); stack.push(e.from) } }
  for (const n of nodes) if (!reach.has(n.key)) errors.push(`节点未连接到目标: ${n.key}`)
  return errors
}
export function toVueNodes(graph) {
  return (graph.nodes || []).map(n => ({ id: n.key, type: 'flowNode', position: n.position || { x: 0, y: 0 }, data: { key: n.key, component: n.component, config: n.config } }))
}
export function toVueEdges(graph) {
  return (graph.edges || []).map(e => ({ id: `${e.from}->${e.to}`, source: e.from, target: e.to, type: 'smoothstep', markerEnd: { type: 'arrowclosed', color: '#728a88' } }))
}
export function newNode(component, existing, position) {
  const prefix = component.toLowerCase().replace(/_/g, '')
  let i = 1
  const keys = new Set((existing || []).map(n => n.key))
  while (keys.has(`${prefix}${i}`)) i++
  return { key: `${prefix}${i}`, component, config: defaultConfig(component), position: position || { x: 40 + (existing || []).length * 40, y: 40 + (existing || []).length * 30 } }
}
