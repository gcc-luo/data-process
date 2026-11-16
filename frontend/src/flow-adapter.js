export function flowNodes(tasks) {
  return tasks.map((node, i) => ({ id: node.id, position: { x: node.x ?? 40 + (i % 4) * 220, y: node.y ?? 60 + Math.floor(i / 4) * 120 }, data: { label: node.version == null ? node.name : `${node.name} · 版本 ${node.version}` } }))
}
export function flowEdges(edges) {
  return edges.map(edge => ({ id: JSON.stringify([edge.from, edge.to]), source: edge.from, target: edge.to, type: 'smoothstep', markerEnd: { type: 'arrowclosed', color: '#728a88' } }))
}
