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

export function paramRows(profile, options) {
  const fields = new Set((profile?.extraFields || []).map(f => f.key))
  return Object.entries(options || {}).filter(([k, v]) => !fields.has(k) && v !== '' && v != null).map(([key, value]) => ({ key, value }))
}

export function buildPayload(form, editing) {
  const options = {}
  for (const [k, v] of Object.entries(form.options || {})) if (v !== '' && v != null) options[k] = String(v)
  return { name: form.name, profileId: form.profileId, host: form.host, port: form.port, database: form.database, username: form.username, password: editing && !form.password ? null : form.password, driverId: form.driverId || null, options }
}
