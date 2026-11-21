/** FLOW 组件目录：分组、文案、默认配置与配置抽屉 schema（与后端 FlowGraph 校验对应）。 */
export const COMPONENTS = [
  {
    component: 'SOURCE_TABLE', group: 'source', label: '数据库表',
    fields: [
      { key: 'sourceId', label: '数据源', type: 'source' },
      { key: 'table', label: '表名（可 schema.table）', type: 'text' }
    ]
  },
  {
    component: 'SOURCE_SQL', group: 'source', label: 'SQL 查询',
    fields: [
      { key: 'sourceId', label: '数据源', type: 'source' },
      { key: 'sql', label: 'SQL 语句', type: 'textarea' }
    ]
  },
  {
    component: 'FIELD_MAP', group: 'interceptor', label: '字段映射',
    fields: [
      { key: 'mappings', label: '映射（上游字段 → 输出字段）', type: 'mappings' }
    ]
  },
  {
    component: 'FIELD_FILTER', group: 'interceptor', label: '数据过滤',
    fields: [
      { key: 'expression', label: '保留条件表达式', type: 'expr' }
    ]
  },
  {
    component: 'FIELD_MASK', group: 'interceptor', label: '字段脱敏',
    fields: [
      { key: 'field', label: '脱敏字段（整体替换为 ****）', type: 'text' }
    ]
  },
  {
    component: 'FIELD_CALC', group: 'interceptor', label: '字段计算',
    fields: [
      { key: 'field', label: '新字段名', type: 'text' },
      { key: 'expression', label: '计算表达式', type: 'expr' }
    ]
  },
  {
    component: 'SINK_TABLE', group: 'sink', label: '数据库表',
    fields: [
      { key: 'sourceId', label: '目标数据源', type: 'source' },
      { key: 'table', label: '目标表', type: 'text' },
      { key: 'writeMode', label: '写入方式', type: 'select', options: [{ value: 'APPEND', label: '追加' }, { value: 'UPSERT', label: '按唯一键更新插入' }] },
      { key: 'keyColumns', label: '唯一键字段（逗号分隔，UPSERT 必填）', type: 'list' },
      { key: 'batchSize', label: '批次大小', type: 'number', min: 1, max: 10000 }
    ]
  }
]
export const GROUPS = [{ key: 'source', label: '来源组件' }, { key: 'interceptor', label: '预处理组件' }, { key: 'sink', label: '目标组件' }]
export function componentDef(component) {
  const def = COMPONENTS.find(c => c.component === component)
  if (!def) throw new Error('未知组件: ' + component)
  return def
}
export function defaultConfig(component) {
  switch (component) {
    case 'SOURCE_TABLE': return { sourceId: null, table: '' }
    case 'SOURCE_SQL': return { sourceId: null, sql: '' }
    case 'FIELD_MAP': return { mappings: [] }
    case 'FIELD_FILTER': return { expression: '' }
    case 'FIELD_MASK': return { field: '' }
    case 'FIELD_CALC': return { field: '', expression: '' }
    case 'SINK_TABLE': return { sourceId: null, table: '', writeMode: 'APPEND', keyColumns: [], batchSize: 500 }
    default: throw new Error('未知组件: ' + component)
  }
}
function brief(text, fallback) {
  const s = text || fallback
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}
export function configSummary(component, config) {
  componentDef(component)
  const c = config || {}
  switch (component) {
    case 'SOURCE_TABLE': case 'SINK_TABLE': return c.table || '未配置表'
    case 'SOURCE_SQL': return brief(c.sql, '未配置 SQL')
    case 'FIELD_MAP': return `${(c.mappings || []).length} 条映射`
    case 'FIELD_FILTER': return brief(c.expression, '未配置条件')
    case 'FIELD_MASK': case 'FIELD_CALC': return c.field || '未配置字段'
    default: return ''
  }
}
