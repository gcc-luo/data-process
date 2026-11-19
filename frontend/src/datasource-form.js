export const typeLabels = { MYSQL: 'MySQL', POSTGRESQL: 'PostgreSQL', ORACLE: 'Oracle' }

export function profileTypes(profiles) {
  const seen = new Map()
  for (const p of profiles || []) if (!seen.has(p.type)) seen.set(p.type, typeLabels[p.type] || p.type)
  return [...seen].map(([value, label]) => ({ value, label }))
}

export function versionsForType(profiles, type) { return (profiles || []).filter(p => p.type === type) }

export function defaultForm(profile) {
  return { profileId: profile?.id || '', port: profile?.defaultPort ?? 3306, options: Object.fromEntries((profile?.extraFields || []).map(f => [f.key, f.defaultValue])) }
}

export function databaseLabel(profile, options) {
  if (profile?.type !== 'ORACLE') return '数据库'
  const fallback = (profile?.extraFields || []).find(f => f.key === 'connectType')?.defaultValue
  return (options?.connectType || fallback) === 'SID' ? 'SID' : '服务名'
}

export function driversForType(drivers, type) { return (drivers || []).filter(d => d.dbType === type) }

export function driverLabel(driver) {
  return `${driver.name}${driver.versionLabel ? ' ' + driver.versionLabel : ''}${driver.builtin ? '（内置）' : '（上传）'}`
}

export function paramRows(profile, options) {
  const fields = new Set((profile?.extraFields || []).map(f => f.key))
  return Object.entries(options || {}).filter(([k, v]) => !fields.has(k) && v !== '' && v != null).map(([key, value]) => ({ key, value }))
}

export function connectionKey(payload) {
  const p = payload || {}
  const options = Object.fromEntries(Object.entries(p.options || {}).sort(([a], [b]) => a.localeCompare(b)))
  return JSON.stringify({ id: p.id ?? null, profileId: p.profileId, host: p.host, port: p.port, database: p.database, username: p.username, password: p.password ?? null, driverId: p.driverId ?? null, options })
}

export function saveAllowed(testedKey, payload) {
  return Boolean(testedKey) && testedKey === connectionKey(payload)
}

export function testOutcome(result) {
  if (result?.connected) return { ok: true, message: '连接成功' }
  return { ok: false, message: result?.error || '连接失败，请检查配置' }
}

export function batchDeleteSummary(result, items) {
  const deleted = result?.deleted || []
  const failed = result?.failed || []
  const reasons = { REFERENCED: '被引用，无法删除', NOT_FOUND: '不存在' }
  const nameOf = id => (items || []).find(item => item.id === id)?.name || `编号 ${id}`
  return {
    message: deleted.length ? `已删除 ${deleted.length} 个数据源` : '',
    error: failed.length ? `以下数据源未删除：${failed.map(f => `${nameOf(f.id)}（${reasons[f.reason] || '删除失败'}）`).join('、')}` : ''
  }
}

export function buildPayload(form, editing) {
  const options = {}
  for (const [k, v] of Object.entries(form.options || {})) if (v !== '' && v != null) options[k] = String(v)
  return { name: form.name, profileId: form.profileId, host: form.host, port: form.port, database: form.database, username: form.username, password: editing && !form.password ? null : form.password, driverId: form.driverId || null, options }
}
