import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { flowNodes, flowEdges } from '../src/flow-adapter.js'
test('maps persisted workflow nodes and coordinates to the canvas', () => {
  assert.deepEqual(flowNodes([{id:'a',name:'抽取',version:2,x:180,y:60}])[0], { id:'a', position:{x:180,y:60}, data:{label:'抽取 · 版本 2'} })
})
test('uses stable default positions and maps directed dependencies', () => {
  assert.deepEqual(flowNodes([{id:'a',name:'a',version:1},{id:'b',name:'b',version:1}]).map(n=>n.position), [{x:40,y:60},{x:260,y:60}])
  assert.deepEqual(flowEdges([{from:'a',to:'b'}])[0], {id:'["a","b"]',source:'a',target:'b',type:'smoothstep',markerEnd:{type:'arrowclosed',color:'#728a88'}})
})
