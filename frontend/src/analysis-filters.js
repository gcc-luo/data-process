import { safeNumber } from './numeric-input.js'
const operations = new Set(['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IS_NULL', 'IS_NOT_NULL'])
const nullOperations = new Set(['IS_NULL', 'IS_NOT_NULL'])

export function analysisQuery({ groups, metricOperation, metricField, filters, limit, sortField, sortDirection }) {
  if (!Array.isArray(filters) || filters.length > 20) throw new Error('最多支持 20 个筛选条件')
  const conditions = filters.map(filter => {
    if (!filter || typeof filter.field !== 'string' || !filter.field.trim()) throw new Error('请选择筛选字段')
    if (!operations.has(filter.operation)) throw new Error('请选择有效的筛选方式')
    let value = nullOperations.has(filter.operation) ? null : filter.value
    if (!nullOperations.has(filter.operation) && filter.valueType === 'number') {
      if (!safeNumber(value)) throw new Error('数字筛选值超出安全精度或格式无效')
      value = Number(value)
    }
    if (!nullOperations.has(filter.operation) && !(typeof value === 'string' || typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value)))) throw new Error('请输入有效的筛选值')
    if (typeof value === 'string' && value.length > 4096) throw new Error('筛选值过长')
    return { field: filter.field, operation: filter.operation, value }
  })
  return { groups: [...groups], metrics: [{ operation: metricOperation, field: metricField || null }], filters: conditions, limit, sort: sortField ? [{ field: sortField, direction: sortDirection }] : [] }
}
export function changeFilterType(filter, type) {
  filter.valueType = type
  if (type === 'number') filter.value = '0'
  else if (type === 'boolean') filter.value = false
  else if (type === 'string') filter.value = ''
  else throw new Error('无效的筛选值类型')
}
export function changeFilterOperation(filter, operation) {
  filter.operation = operation
  if (nullOperations.has(operation)) filter.value = null
  else if (filter.value == null) { filter.valueType = 'string'; filter.value = '' }
}
