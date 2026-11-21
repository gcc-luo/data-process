import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { validateFlow, toVueNodes, toVueEdges, newNode, canConnect, previewTable } from '../src/flow-graph.js'

const chain = {
  nodes: [
    { key: 's', component: 'SOURCE_TABLE', config: { sourceId: 1, table: 'orders' }, position: { x: 0, y: 0 } },
    { key: 'f', component: 'FIELD_FILTER', config: { expression: 'amount > 0' }, position: { x: 200, y: 0 } },
    { key: 't', component: 'SINK_TABLE', config: { sourceId: 2, table: 'copy', writeMode: 'APPEND' }, position: { x: 400, y: 0 } }
  ],
  edges: [{ from: 's', to: 'f' }, { from: 'f', to: 't' }]
}

test('valid chain passes client validation', () => {
  assert.deepEqual(validateFlow(chain), [])
})

test('structural errors are reported with node keys', () => {
  const cycle = { nodes: chain.nodes, edges: [...chain.edges, { from: 'f', to: 's' }] }
  assert.ok(validateFlow(cycle).some(m => m.includes('环')))
  const noSink = { nodes: chain.nodes.slice(0, 2), edges: [{ from: 's', to: 'f' }] }
  assert.ok(validateFlow(noSink).some(m => m.includes('目标')))
  const dangling = { nodes: [...chain.nodes, { key: 'x', component: 'FIELD_CALC', config: { field: 'c', expression: '1' }, position: { x: 0, y: 200 } }], edges: chain.edges }
  assert.ok(validateFlow(dangling).some(m => m.includes('x')))
  const multiIn = { nodes: [...chain.nodes, { key: 's2', component: 'SOURCE_TABLE', config: { sourceId: 1, table: 'o' }, position: { x: 0, y: 200 } }], edges: [...chain.edges, { from: 's2', to: 'f' }] }
  assert.ok(validateFlow(multiIn).some(m => m.includes('f')))
})

test('single out-edge rule enforced client-side', () => {
  const fanOut = { nodes: [...chain.nodes, { key: 't2', component: 'SINK_TABLE', config: { sourceId: 2, table: 'copy2', writeMode: 'APPEND' }, position: { x: 400, y: 200 } }], edges: [...chain.edges, { from: 'f', to: 't2' }] }
  assert.ok(validateFlow(fanOut).some(m => m.includes('只能有一条出边') && m.includes('f')))
})

test('config errors are reported', () => {
  const bad = { nodes: chain.nodes.map(n => n.key === 's' ? { ...n, config: { sourceId: 1, table: '' } } : n), edges: chain.edges }
  assert.ok(validateFlow(bad).some(m => m.includes('s')))
})

test('vue adapter maps keys positions and edges', () => {
  const nodes = toVueNodes(chain)
  assert.equal(nodes.length, 3)
  assert.equal(nodes[0].id, 's')
  assert.deepEqual(nodes[0].position, { x: 0, y: 0 })
  assert.equal(nodes[0].type, 'flowNode')
  const edges = toVueEdges(chain)
  assert.equal(edges.length, 2)
  assert.equal(edges[0].source, 's')
  assert.equal(edges[0].target, 'f')
})

test('empty graph is rejected', () => {
  assert.deepEqual(validateFlow({ nodes: [], edges: [] }), ['流程至少需要一个节点'])
})

test('duplicate node keys are reported', () => {
  const g = { nodes: [...chain.nodes, { ...chain.nodes[0] }], edges: chain.edges }
  assert.ok(validateFlow(g).some(m => m.includes('重复')))
})

test('unknown component is reported with node key', () => {
  const g = { nodes: [...chain.nodes, { key: 'k', component: 'KAFKA', config: {}, position: { x: 0, y: 200 } }], edges: chain.edges }
  assert.ok(validateFlow(g).some(m => m.includes('未知组件') && m.includes('k')))
})

test('sourceId must be a positive integer', () => {
  for (const bad of [-1, 1.5, 'abc', 0, null]) {
    const g = { nodes: chain.nodes.map(n => n.key === 's' ? { ...n, config: { ...n.config, sourceId: bad } } : n), edges: chain.edges }
    assert.ok(validateFlow(g).some(m => m.includes('s') && m.includes('数据源无效')), `sourceId=${JSON.stringify(bad)} should be rejected`)
  }
})

test('FIELD_MASK field must be a valid identifier', () => {
  const mask = { key: 'm', component: 'FIELD_MASK', config: { field: 'bad-name' }, position: { x: 200, y: 0 } }
  const edges = [{ from: 's', to: 'm' }, { from: 'm', to: 't' }]
  const g = { nodes: [chain.nodes[0], mask, chain.nodes[2]], edges }
  assert.ok(validateFlow(g).some(m => m.includes('m') && m.includes('脱敏字段名无效')))
  const ok = { nodes: [chain.nodes[0], { ...mask, config: { field: 'phone' } }, chain.nodes[2]], edges }
  assert.deepEqual(validateFlow(ok), [])
})

test('dangling edge does not produce a false cycle', () => {
  const g = { nodes: chain.nodes, edges: [...chain.edges, { from: 'ghost', to: 'f' }] }
  const errors = validateFlow(g)
  assert.ok(errors.some(m => m.includes('非法连线')))
  assert.ok(!errors.some(m => m.includes('环')))
})

test('vue edge adapter sets id type and marker', () => {
  const edges = toVueEdges(chain)
  assert.equal(edges[0].id, 's->f')
  assert.equal(edges[0].type, 'smoothstep')
  assert.deepEqual(edges[0].markerEnd, { type: 'arrowclosed', color: '#728a88' })
})

test('canConnect allows a legal connection', () => {
  const g = { nodes: chain.nodes.slice(0, 2), edges: [] }
  assert.equal(canConnect(g, { source: 's', target: 'f' }), null)
})

test('canConnect rejects self loop', () => {
  assert.ok(canConnect(chain, { source: 'f', target: 'f' }).includes('自身'))
})

test('canConnect rejects duplicate edge', () => {
  assert.ok(canConnect(chain, { source: 's', target: 'f' }).includes('已存在'))
})

test('canConnect rejects incoming edge on source', () => {
  assert.ok(canConnect(chain, { source: 'f', target: 's' }).includes('来源节点不能有入边'))
})

test('canConnect rejects outgoing edge on sink', () => {
  assert.ok(canConnect(chain, { source: 't', target: 'f' }).includes('目标节点不能有出边'))
})

test('canConnect rejects second incoming edge', () => {
  const g = { nodes: [...chain.nodes, { key: 's2', component: 'SOURCE_TABLE', config: { sourceId: 1, table: 'o' }, position: { x: 0, y: 200 } }], edges: chain.edges }
  assert.ok(canConnect(g, { source: 's2', target: 'f' }).includes('已有入边'))
})

test('canConnect rejects second outgoing edge', () => {
  const g = { nodes: [...chain.nodes, { key: 't2', component: 'SINK_TABLE', config: { sourceId: 2, table: 'c2', writeMode: 'APPEND' }, position: { x: 400, y: 200 } }], edges: chain.edges }
  assert.ok(canConnect(g, { source: 'f', target: 't2' }).includes('已有出边'))
})

test('previewTable keeps duplicate column names separate via index props', () => {
  const table = previewTable({ columns: ['id', 'id'], rows: [[1, 2], [3, 4]] })
  assert.deepEqual(table.columns, [{ prop: 'col_0', label: 'id' }, { prop: 'col_1', label: 'id' }])
  assert.deepEqual(table.rows, [{ col_0: 1, col_1: 2 }, { col_0: 3, col_1: 4 }])
})

test('previewTable tolerates empty preview', () => {
  assert.deepEqual(previewTable({ columns: ['a'], rows: [] }), { columns: [{ prop: 'col_0', label: 'a' }], rows: [] })
  assert.deepEqual(previewTable(undefined), { columns: [], rows: [] })
})

test('newNode generates unique keys and default config', () => {
  const a = newNode('FIELD_FILTER', chain.nodes, { x: 10, y: 20 })
  const b = newNode('FIELD_FILTER', [...chain.nodes, a], { x: 10, y: 20 })
  assert.notEqual(a.key, b.key)
  assert.equal(a.config.expression, '')
  assert.deepEqual(a.position, { x: 10, y: 20 })
})

test('SOURCE_SQL chain validates without table requirement', () => {
  const sqlChain = {
    nodes: [
      { key: 's', component: 'SOURCE_SQL', config: { sourceId: 1, sql: 'SELECT id FROM orders' } },
      { key: 'f', component: 'FIELD_FILTER', config: { expression: 'id > 0' } },
      { key: 't', component: 'SINK_TABLE', config: { sourceId: 2, table: 'copy', writeMode: 'APPEND' } }
    ],
    edges: [{ from: 's', to: 'f' }, { from: 'f', to: 't' }]
  }
  assert.deepEqual(validateFlow(sqlChain), [])
  const noSql = { nodes: sqlChain.nodes.map(n => n.key === 's' ? { ...n, config: { sourceId: 1, sql: '' } } : n), edges: sqlChain.edges }
  assert.ok(validateFlow(noSql).some(m => m.includes('未配置 SQL')))
  const noTable = { nodes: chain.nodes.map(n => n.key === 's' ? { ...n, config: { sourceId: 1, table: '' } } : n), edges: chain.edges }
  assert.ok(validateFlow(noTable).some(m => m.includes('表名无效')))
})
