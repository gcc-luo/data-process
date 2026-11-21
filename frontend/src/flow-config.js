/** 配置抽屉表单与节点 config 的双向转换（纯函数，供 FlowEditor 与测试共用）。 */
export function configToForm(component, config) {
  const form = JSON.parse(JSON.stringify(config || {}))
  if (component === 'FIELD_MAP') form.mappings ??= []
  if (component === 'SINK_TABLE' && Array.isArray(form.keyColumns)) form.keyColumns = form.keyColumns.join(', ')
  return form
}
export function formToConfig(component, form) {
  const config = { ...(form || {}) }
  if (component === 'SINK_TABLE') config.keyColumns = String(config.keyColumns || '').split(',').map(s => s.trim()).filter(Boolean)
  if (component === 'FIELD_MAP') config.mappings = (config.mappings || []).filter(m => m && m.from?.trim() && m.to?.trim()).map(m => ({ ...m, from: m.from.trim(), to: m.to.trim() }))
  return config
}
