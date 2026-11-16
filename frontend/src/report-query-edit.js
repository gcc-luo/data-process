import { safeNumber, verifyJsonNumbers } from './numeric-input.js'
/** Clone a stored query so edits never mutate the active report before saving. */
export function reportEditor(report) {
  let original
  verifyJsonNumbers(report.configuration)
  try { original = JSON.parse(report.configuration) } catch { throw new Error('报表查询配置无效') }
  if (!original || Array.isArray(original) || typeof original !== 'object' || !Number.isInteger(original.limit)) throw new Error('报表查询配置无效')
  if (report.kind === 'PREVIEW') {
    if (!Array.isArray(original.fields) || original.fields.some(x => typeof x !== 'string')) throw new Error('报表查询配置无效')
  } else if (report.kind === 'ANALYSIS') {
    if (!Array.isArray(original.groups) || !Array.isArray(original.metrics) || !Array.isArray(original.filters) || (original.sort != null && !Array.isArray(original.sort))) throw new Error('报表查询配置无效')
    if (original.groups.some(x => typeof x !== 'string') || original.metrics.some(x => !x || typeof x !== 'object') || original.filters.some(x => !x || typeof x !== 'object') || original.sort?.some(x => !x || typeof x !== 'object')) throw new Error('报表查询配置无效')
  } else throw new Error('不支持的报表查询配置')
  const query = JSON.parse(JSON.stringify(original))
  if (report.kind === 'ANALYSIS') {
    if (query.sort == null) query.sort = []
    query.filters = query.filters.map(filter => {
      const valueType = typeof filter.value === 'number' ? 'number' : typeof filter.value === 'boolean' ? 'boolean' : 'string'
      return { ...filter, valueType, value: valueType === 'number' ? String(filter.value) : filter.value }
    })
  }
  return { original, query, initial: JSON.stringify(query) }
}
export function serializeEditedQuery(editor) {
  const query = { ...editor.query }
  if (Array.isArray(query.filters)) query.filters = query.filters.map(filter => {
    const { valueType, ...entry } = filter
    if (valueType === 'number' && !['IS_NULL', 'IS_NOT_NULL'].includes(entry.operation)) {
      if (!safeNumber(entry.value)) throw new Error('数字筛选值超出安全精度或格式无效')
      entry.value = Number(entry.value)
    }
    return entry
  })
  return query
}
export function canSaveQuery(editor, tested) {
  const current = JSON.stringify(editor.query)
  return current === editor.initial || current === tested
}
export function reportUpdate(report, editor, name, chartType) {
  const changed = JSON.stringify(editor.query) !== editor.initial
  return { datasetId: report.datasetId, kind: report.kind, name, chartType, configuration: changed ? serializeEditedQuery(editor) : editor.original }
}
