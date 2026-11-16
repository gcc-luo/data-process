import { safeNumber, verifyJsonNumbers } from './numeric-input.js'
export function emptyTask() {
  return { name: '', type: 'SQL', sourceId: null, targetId: null, sql: '', parameters: [], sourceQuery: '', targetTable: '', columns: '', writeMode: 'APPEND', keys: '', batchSize: 500, retrySafe: false, maxRetries: 1, retryDelay: 30, originalConfiguration: {} }
}
export function newParameter(kind = 'string') {
  switch (kind) {
    case 'date': case 'null': return { kind, value: null }
    case 'string': return { kind, value: '' }
    case 'number': return { kind, value: '0' }
    case 'boolean': return { kind, value: false }
    default: throw new Error('不支持的参数类型')
  }
}
function fromParameters(parameters) {
  if (!Array.isArray(parameters) || parameters.length > 1000) throw new Error('任务参数无效')
  return parameters.map(parameter => {
    if (!parameter || typeof parameter !== 'object' || Array.isArray(parameter)) throw new Error('任务参数无效')
    if (parameter.type === 'BUSINESS_DATE' && Object.keys(parameter).every(key => key === 'type')) return newParameter('date')
    if (parameter.type !== 'VALUE' || Object.keys(parameter).some(key => !['type', 'value'].includes(key))) throw new Error('此任务包含当前表单不支持的参数，不能安全编辑')
    const value = parameter.value ?? null
    if (value === null) return newParameter('null')
    if (typeof value === 'string' || typeof value === 'boolean') return { kind: typeof value, value }
    if (typeof value === 'number' && Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value))) return { kind: 'number', value: String(value) }
    throw new Error('此任务包含当前表单不支持的参数，不能安全编辑')
  })
}
function toParameters(parameters) {
  if (!Array.isArray(parameters) || parameters.length > 1000) throw new Error('任务参数无效')
  return parameters.map(parameter => {
    if (!parameter || typeof parameter !== 'object') throw new Error('任务参数无效')
    if (parameter.kind === 'date') return { type: 'BUSINESS_DATE' }
    if (parameter.kind === 'null') return { type: 'VALUE', value: null }
    const value = parameter.value
    if (parameter.kind === 'number' && safeNumber(value)) return { type: 'VALUE', value: Number(value) }
    if (parameter.kind === 'string' && typeof value === 'string' || parameter.kind === 'boolean' && typeof value === 'boolean') return { type: 'VALUE', value }
    throw new Error('请输入有效的任务参数，整数需在安全范围内')
  })
}
export function formFromTask(task) {
  verifyJsonNumbers(task.configuration)
  const c = JSON.parse(task.configuration)
  if (task.type !== 'SQL' && task.type !== 'SYNC') throw new Error('不支持的任务类型')
  const parameters = fromParameters((task.type === 'SQL' ? c.parameters : c.sourceParameters) ?? [])
  return { ...emptyTask(), name: task.name, type: task.type, sourceId: c.sourceId, targetId: c.targetId ?? null, sql: c.sql ?? '', parameters, sourceQuery: c.sourceQuery ?? '', targetTable: c.targetTable ?? '', columns: (c.targetColumns ?? []).join(', '), writeMode: c.writeMode ?? 'APPEND', keys: (c.keyColumns ?? []).join(', '), batchSize: c.batchSize ?? 500, retrySafe: !!c.retry?.safe, maxRetries: c.retry?.maxRetries ?? 1, retryDelay: c.retry?.delaySeconds ?? 30, originalConfiguration: c }
}
export function configurationFromForm(t) {
  const configuration = { ...(t.originalConfiguration ?? {}) }
  const parameters = toParameters(t.parameters)
  if (t.type === 'SQL') Object.assign(configuration, { sourceId: t.sourceId, sql: t.sql, parameters })
  else Object.assign(configuration, { sourceId: t.sourceId, targetId: t.targetId, sourceQuery: t.sourceQuery, sourceParameters: parameters, targetTable: t.targetTable, targetColumns: t.columns.split(',').map(s => s.trim()).filter(Boolean), writeMode: t.writeMode, keyColumns: t.writeMode === 'UPSERT' ? t.keys.split(',').map(s => s.trim()).filter(Boolean) : [], batchSize: t.batchSize })
  if (t.retrySafe) configuration.retry = { ...(t.originalConfiguration?.retry ?? {}), safe: true, maxRetries: t.maxRetries, delaySeconds: t.retryDelay }
  else delete configuration.retry
  return configuration
}
