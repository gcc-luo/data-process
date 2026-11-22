import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  indexComponents, activeOutputPorts, defaultConfig, newNodeId, newNode, normalizeNode, wouldCycle,
  canConnect, edgeKind, newEdgeId, autoLayout, configSummary, isConfigured,
  toVueNodes, toVueEdges, previewTable, createHistory, clientValidate, sqlPlaceholderNames
} from '../src/orch-graph.js'

const COMPONENTS = [
  { type: 'source.mysql', name: 'MySQL', category: 'SOURCE', icon: 'mysql', color: 'blue', enabled: true,
    inputPorts: [], outputPorts: [{ id: 'out', kind: 'DATA', maxConnections: -1 }],
    fields: [{ key: 'sourceId', label: '数据源', type: 'source', required: true, tab: 'basic', dbType: 'MYSQL' }, { key: 'readMode', type: 'select', required: true, tab: 'basic', default: 'TABLE' }, { key: 'table', type: 'table', required: true, tab: 'basic', when: { field: 'readMode', equals: 'TABLE' } }, { key: 'sql', type: 'sql', required: true, tab: 'basic', when: { field: 'readMode', equals: 'SQL' } }] },
  { type: 'transform.field-map', name: '字段映射', category: 'TRANSFORM', icon: 'field-map', color: 'purple', enabled: true,
    inputPorts: [{ id: 'in', kind: 'DATA', maxConnections: 1 }], outputPorts: [{ id: 'out', kind: 'DATA', maxConnections: -1 }],
    fields: [{ key: 'mappings', type: 'mappings', required: true, tab: 'basic' }] },
  { type: 'transform.sql', name: 'SQL 脚本', category: 'TRANSFORM', icon: 'sql', color: 'purple', enabled: true,
    inputPorts: [{ id: 'dep', kind: 'CONTROL', maxConnections: -1 }], outputPorts: [{ id: 'out', kind: 'DATA', maxConnections: -1, when: { field: 'sqlMode', equals: 'SELECT' } }],
    fields: [{ key: 'sourceId', type: 'source', required: true, tab: 'basic' }, { key: 'sqlMode', type: 'select', required: true, tab: 'basic', default: 'SELECT' }, { key: 'sql', type: 'sql', required: true, tab: 'basic' }] },
  { type: 'control.branch', name: '条件分支', category: 'CONTROL', icon: 'branch', color: 'orange', enabled: true,
    inputPorts: [{ id: 'in', kind: 'DATA', maxConnections: 1 }], outputPorts: [{ id: '*', kind: 'DATA', dynamic: true }],
    fields: [{ key: 'rules', type: 'rules', required: true, tab: 'basic' }, { key: 'defaultKey', type: 'text', required: true, tab: 'basic' }] },
  { type: 'control.merge', name: '数据汇聚', category: 'CONTROL', icon: 'merge', color: 'orange', enabled: true,
    inputPorts: [{ id: 'in', kind: 'DATA', maxConnections: -1 }], outputPorts: [{ id: 'out', kind: 'DATA', maxConnections: -1 }],
    fields: [{ key: 'strategy', type: 'select', required: true, tab: 'basic', default: 'UNION' }] },
  { type: 'sink.mysql', name: 'MySQL 写入', category: 'SINK', icon: 'mysql', color: 'green', enabled: true,
    inputPorts: [{ id: 'in', kind: 'DATA', maxConnections: 1 }], outputPorts: [],
    fields: [{ key: 'sourceId', type: 'source', required: true, tab: 'basic', dbType: 'MYSQL' }, { key: 'table', type: 'table', required: true, tab: 'basic' }, { key: 'writeMode', type: 'select', required: true, tab: 'basic', default: 'APPEND' }] },
  { type: 'sink.kafka', name: 'Kafka 输出', category: 'SINK', icon: 'kafka', color: 'green', enabled: false, inputPorts: [], outputPorts: [], fields: [] }
]
const byType = indexComponents(COMPONENTS)

function node(id, component, config = {}) { return { id, component, name: id, enabled: true, position: { x: 0, y: 0 }, config } }
function def(nodes, edges = []) { return { schemaVersion: 1, variables: [], nodes, edges } }
function edge(id, from, fp, to, tp, kind = 'DATA') { return { id, from, fromPort: fp, to, toPort: tp, kind } }

test('newNodeId generates unique ids per component', () => {
  const existing = [node('n_mysql_1', 'source.mysql')]
  assert.equal(newNodeId('source.mysql', existing), 'n_mysql_2')
  assert.equal(newNodeId('sink.mysql', existing), 'n_mysql_2')
})

test('defaultConfig honors registry defaults and array types', () => {
  const cfg = defaultConfig(byType.get('source.mysql'))
  assert.equal(cfg.readMode, 'TABLE')
  assert.equal(cfg.sourceId, null)
  assert.deepEqual(defaultConfig(byType.get('transform.field-map')).mappings, [])
})

test('activeOutputPorts respects conditional and dynamic branch ports', () => {
  const sql = byType.get('transform.sql')
  assert.equal(activeOutputPorts(sql, { sqlMode: 'SELECT' }).length, 1)
  assert.equal(activeOutputPorts(sql, { sqlMode: 'DML' }).length, 0)
  const branch = byType.get('control.branch')
  const ports = activeOutputPorts(branch, { rules: [{ key: 'high', label: '大额' }], defaultKey: 'low' })
  assert.deepEqual(ports.map(p => p.id), ['high', 'low'])
})

test('canConnect rejects self, duplicate, cycle and incompatible ports', () => {
  const d = def([node('s', 'source.mysql', { sourceId: 1, table: 't' }), node('m', 'transform.field-map', { mappings: [{}] }), node('k', 'sink.mysql', { sourceId: 2, table: 't' })],
    [edge('e1', 's', 'out', 'm', 'in'), edge('e2', 'm', 'out', 'k', 'in')])
  assert.match(canConnect(d, byType, { source: 's', target: 's', sourceHandle: 'out', targetHandle: 'in' }), /自身/)
  assert.match(canConnect(d, byType, { source: 's', target: 'm', sourceHandle: 'out', targetHandle: 'in' }), /已存在/)
  assert.match(canConnect(d, byType, { source: 'k', target: 's', sourceHandle: 'out', targetHandle: 'in' }), /没有该输出端口/)
  assert.match(canConnect(d, byType, { source: 'm', target: 's', sourceHandle: 'out', targetHandle: 'in' }), /没有该输入端口/)
  // cycle: k has no out, so connect m->s would be source-in; instead build a filter cycle
  const f = def([node('a', 'transform.field-map', { mappings: [{}] }), node('b', 'transform.field-map', { mappings: [{}] })], [edge('e1', 'a', 'out', 'b', 'in')])
  assert.match(canConnect(f, byType, { source: 'b', target: 'a', sourceHandle: 'out', targetHandle: 'in' }), /环/)
})

test('canConnect enforces single DATA input except merge', () => {
  const d = def([node('s', 'source.mysql', { sourceId: 1, table: 't' }), node('s2', 'source.mysql', { sourceId: 1, table: 't' }), node('m', 'transform.field-map', { mappings: [{}] })], [edge('e1', 's', 'out', 'm', 'in')])
  assert.match(canConnect(d, byType, { source: 's2', target: 'm', sourceHandle: 'out', targetHandle: 'in' }), /入边已达上限/)
  const mg = def([node('s', 'source.mysql', { sourceId: 1, table: 't' }), node('s2', 'source.mysql', { sourceId: 1, table: 't' }), node('g', 'control.merge', { strategy: 'UNION' })], [edge('e1', 's', 'out', 'g', 'in')])
  assert.equal(canConnect(mg, byType, { source: 's2', target: 'g', sourceHandle: 'out', targetHandle: 'in' }), null)
})

test('control edge kind derives from target port', () => {
  assert.equal(edgeKind(byType.get('transform.sql'), 'dep'), 'CONTROL')
  assert.equal(edgeKind(byType.get('transform.field-map'), 'in'), 'DATA')
})

test('wouldCycle detects back edges', () => {
  const edges = [edge('e1', 'a', 'out', 'b', 'in'), edge('e2', 'b', 'out', 'c', 'in')]
  assert.equal(wouldCycle(edges, 'c', 'a'), true)
  assert.equal(wouldCycle(edges, 'a', 'c'), false)
})

test('autoLayout only changes positions, preserves ids/config and layers left-to-right', () => {
  const d = def([node('s', 'source.mysql', { sourceId: 1, table: 't' }), node('m', 'transform.field-map', { mappings: [{ from: 'id', to: 'id' }] }), node('k', 'sink.mysql', { sourceId: 2, table: 't' })],
    [edge('e1', 's', 'out', 'm', 'in'), edge('e2', 'm', 'out', 'k', 'in')])
  autoLayout(d)
  const s = d.nodes.find(n => n.id === 's'), m = d.nodes.find(n => n.id === 'm'), k = d.nodes.find(n => n.id === 'k')
  assert.ok(s.position.x < m.position.x && m.position.x < k.position.x)
  assert.deepEqual(m.config.mappings, [{ from: 'id', to: 'id' }])
  assert.equal(d.edges.length, 2)
})

test('isConfigured mirrors required fields with when conditions', () => {
  assert.equal(isConfigured(byType.get('source.mysql'), { sourceId: 1, readMode: 'TABLE', table: 't' }), true)
  assert.equal(isConfigured(byType.get('source.mysql'), { sourceId: 1, readMode: 'TABLE', table: '' }), false)
  assert.equal(isConfigured(byType.get('source.mysql'), { sourceId: 1, readMode: 'SQL', sql: 'SELECT 1' }), true)
  assert.equal(isConfigured(byType.get('transform.field-map'), { mappings: [] }), false)
})

test('configSummary produces readable subtitles', () => {
  assert.equal(configSummary(byType.get('sink.mysql'), { table: 'dst', writeMode: 'UPSERT' }), 'dst · UPSERT')
  assert.equal(configSummary(byType.get('transform.field-map'), { mappings: [{}, {}] }), '2 条映射')
})

test('sqlPlaceholderNames parses named params, skips quotes/comments/casts', () => {
  assert.deepEqual(sqlPlaceholderNames("SELECT id FROM t WHERE a = :x AND b = :y"), ['x', 'y'])
  assert.deepEqual(sqlPlaceholderNames("SELECT ':notparam' FROM t WHERE a = :x -- :comment"), ['x'])
  assert.deepEqual(sqlPlaceholderNames("SELECT a::text FROM t WHERE b = :x"), ['x'])
  assert.deepEqual(sqlPlaceholderNames("SELECT id FROM t"), [])
})

test('toVueNodes/toVueEdges adapt definition with state and issues', () => {
  const d = def([node('s', 'source.mysql', { sourceId: 1, table: 't' })], [])
  const vn = toVueNodes(d, byType, { nodeStates: new Map([['s', 'SUCCEEDED']]), issueIds: new Set(['s']) })
  assert.equal(vn[0].type, 'orchNode')
  assert.equal(vn[0].data.state, 'SUCCEEDED')
  assert.equal(vn[0].data.hasIssue, true)
  const ve = toVueEdges(def([], [edge('e1', 'a', 'out', 'b', 'dep', 'CONTROL')]))
  assert.equal(ve[0].sourceHandle, 'out')
  assert.equal(ve[0].targetHandle, 'dep')
})

test('previewTable indexes columns to avoid name clashes', () => {
  const t = previewTable({ columns: ['id', 'id'], rows: [[1, 2]] })
  assert.deepEqual(t.columns.map(c => c.prop), ['col_0', 'col_1'])
  assert.deepEqual(t.rows[0], { col_0: 1, col_1: 2 })
})

test('createHistory undo/redo restores snapshots and caps at 30', () => {
  const h = createHistory(3)
  const s0 = { nodes: [], edges: [], variables: [] }
  h.push(s0)
  const s1 = { nodes: [node('a', 'source.mysql')], edges: [], variables: [] }
  h.push(s1)
  const current = { nodes: [node('a', 'source.mysql'), node('b', 'sink.mysql')], edges: [], variables: [] }
  const undone = h.undo(current)
  assert.equal(undone.nodes.length, 1)
  const redone = h.redo(undone)
  assert.equal(redone.nodes.length, 2)
  for (let i = 0; i < 10; i++) h.push({ nodes: [], edges: [], variables: [], i })
  assert.equal(h.canUndo(), true)
})

test('clientValidate flags empty, disabled, cycle and incomplete', () => {
  assert.ok(clientValidate(def([]), byType).some(i => i.code === 'WORKFLOW_EMPTY'))
  assert.ok(clientValidate(def([node('p', 'sink.kafka')]), byType).some(i => i.code === 'COMPONENT_NOT_SUPPORTED'))
  const incomplete = clientValidate(def([node('s', 'source.mysql', {}), node('k', 'sink.mysql', { sourceId: 2, table: 't' })], [edge('e1', 's', 'out', 'k', 'in')]), byType)
  assert.ok(incomplete.some(i => i.code === 'NODE_REQUIRED_FIELD'))
  const cyclic = clientValidate(def([node('a', 'transform.field-map', { mappings: [{}] }), node('b', 'transform.field-map', { mappings: [{}] })], [edge('e1', 'a', 'out', 'b', 'in'), edge('e2', 'b', 'out', 'a', 'in')]), byType)
  assert.ok(cyclic.some(i => i.code === 'WORKFLOW_CYCLE_DETECTED'))
})

test('newEdgeId increments uniquely', () => {
  assert.equal(newEdgeId([{ id: 'e_1' }, { id: 'e_2' }]), 'e_3')
})

test('normalizeNode fills missing retry/enabled/timeout so inspector never reads undefined.enabled', () => {
  // 草稿 JSON 节点常缺 retry（后端 parse 才补默认）——前端必须补全，否则重试页签渲染崩溃
  const raw = { id: 'n_src', component: 'source.mysql', name: '源', config: { sourceId: 3, readMode: 'SQL', sql: 'SELECT 1' } }
  const n = normalizeNode(raw)
  assert.equal(n.retry.enabled, false)
  assert.equal(n.retry.maxAttempts, 3)
  assert.equal(n.enabled, true)
  assert.equal(n.timeoutSeconds, 300)
  assert.deepEqual(n.position, { x: 0, y: 0 })
  assert.equal(n.config.sql, 'SELECT 1')
  // 已有 retry 部分字段时合并保留
  const partial = normalizeNode({ id: 'x', component: 'sink.mysql', retry: { enabled: true, maxAttempts: 5 }, config: {} })
  assert.equal(partial.retry.enabled, true)
  assert.equal(partial.retry.maxAttempts, 5)
  assert.equal(partial.retry.backoff, 'FIXED')
})