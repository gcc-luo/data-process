export function chartColumns(rows) {
  const first = rows[0] ?? {}, keys = Object.keys(first)
  const numeric = typeof first.metric_0 === 'number' ? 'metric_0' : keys.find(key => typeof first[key] === 'number')
  return { numeric, category: keys.find(key => key !== numeric) }
}
