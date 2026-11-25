import { authorizedRequest } from './auth.js'

export async function objectRequest(path, options = {}) {
  const response = options.method ? await authorizedRequest(path, options) : await fetch(path, { credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error || body?.message || (response.status === 403 ? '当前账号没有操作权限' : '操作失败，请检查连接和配置'))
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}
export function objectWrite(path, method, body) {
  return objectRequest(path, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
}
export function objectIdentityKey(value) {
  return JSON.stringify([value.sourceId, value.catalog || '', value.schema || '', value.table, value.type])
}
