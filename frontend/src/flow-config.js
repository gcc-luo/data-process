/** 配置抽屉表单与节点 config 的双向转换（纯函数，供 FlowEditor 与测试共用）。 */
import { newParameter } from './task-edit.js'
import { safeNumber } from './numeric-input.js'

/** SOURCE_SQL 顺序参数 → 表单 kinds 数组（语义镜像 task-edit.js fromParameters）。 */
export function paramsToForm(parameters) {
  return (Array.isArray(parameters) ? parameters : []).map(p => {
    if (p?.type === 'BUSINESS_DATE') return newParameter('date')
    const value = p?.value ?? null
    if (value === null) return newParameter('null')
    if (typeof value === 'string' || typeof value === 'boolean') return { kind: typeof value, value }
    if (typeof value === 'number' && Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value))) return { kind: 'number', value: String(value) }
    throw new Error('此节点包含当前表单不支持的参数，不能安全编辑')
  })
}

/** 表单 kinds 数组 → SOURCE_SQL 顺序参数（语义镜像 task-edit.js toParameters）。 */
export function formToParams(kinds) {
  return (Array.isArray(kinds) ? kinds : []).map(p => {
    if (p.kind === 'date') return { type: 'BUSINESS_DATE' }
    if (p.kind === 'null') return { type: 'VALUE', value: null }
    const value = p.value
    if (p.kind === 'number' && safeNumber(value)) return { type: 'VALUE', value: Number(value) }
    if (p.kind === 'string' && typeof value === 'string' || p.kind === 'boolean' && typeof value === 'boolean') return { type: 'VALUE', value }
    throw new Error('请输入有效的节点参数，整数需在安全范围内')
  })
}

export function configToForm(component, config) {
  const form = JSON.parse(JSON.stringify(config || {}))
  if (component === 'FIELD_MAP') form.mappings ??= []
  if (component === 'SINK_TABLE' && Array.isArray(form.keyColumns)) form.keyColumns = form.keyColumns.join(', ')
  if (component === 'SOURCE_SQL') form.parameters = paramsToForm(form.parameters)
  return form
}
export function formToConfig(component, form) {
  const config = { ...(form || {}) }
  if (component === 'SINK_TABLE') config.keyColumns = String(config.keyColumns || '').split(',').map(s => s.trim()).filter(Boolean)
  if (component === 'FIELD_MAP') config.mappings = (config.mappings || []).filter(m => m && m.from?.trim() && m.to?.trim()).map(m => ({ ...m, from: m.from.trim(), to: m.to.trim() }))
  if (component === 'SOURCE_SQL') config.parameters = formToParams(config.parameters)
  return config
}
