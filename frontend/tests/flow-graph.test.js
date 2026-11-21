import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { validateFlow, toVueNodes, toVueEdges, newNode } from '../src/flow-graph.js'

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

test('newNode generates unique keys and default config', () => {
  const a = newNode('FIELD_FILTER', chain.nodes, { x: 10, y: 20 })
  const b = newNode('FIELD_FILTER', [...chain.nodes, a], { x: 10, y: 20 })
  assert.notEqual(a.key, b.key)
  assert.equal(a.config.expression, '')
  assert.deepEqual(a.position, { x: 10, y: 20 })
})
