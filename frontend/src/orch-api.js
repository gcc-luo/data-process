/** 任务编排 API 客户端：读用 fetch，写用 authorizedRequest（CSRF）。错误统一抛 Error。 */
import { authorizedRequest } from './auth.js'

async function read(url) {
  const response = await fetch(url, { credentials: 'same-origin' })
  if (response.status === 401) { window.dispatchEvent(new Event('governance:session-expired')); throw new Error('登录已失效') }
  if (response.status === 403) throw new Error('当前账号没有相应权限')
  if (!response.ok) throw new Error('读取失败，请检查连接')
  if (response.status === 204) return null
  return response.json()
}

async function write(url, method, body) {
  const response = await authorizedRequest(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (response.status === 401) { window.dispatchEvent(new Event('governance:session-expired')); throw new Error('登录已失效') }
  if (response.status === 403) throw new Error('当前账号没有相应权限')
  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    const error = new Error(payload?.error || payload?.message || `操作失败（${response.status}）`)
    error.status = response.status
    error.payload = payload
    throw error
  }
  if (response.status === 204) return null
  return response.json().catch(() => null)
}

export const fetchComponents = () => read('/api/orch/components')
export const fetchTasks = () => read('/api/tasks')
export const createTask = body => write('/api/tasks', 'POST', body)
export const fetchDraft = id => read(`/api/tasks/${id}/draft`)
export const saveDraft = (id, expectedRevision, definition) => write(`/api/tasks/${id}/draft`, 'PUT', { expectedRevision, definition })
export const validateTask = (id, definition) => write(`/api/tasks/${id}/validate`, 'POST', definition ? { definition } : {})
export const validateDefinition = definition => write('/api/orch/validate', 'POST', { definition })
export const publishTask = (id, expectedRevision, comment) => write(`/api/tasks/${id}/publish`, 'POST', { expectedRevision, comment })
export const fetchVersions = id => read(`/api/tasks/${id}/versions`)
export const fetchVersion = (id, version) => read(`/api/tasks/${id}/versions/${version}`)
export const copyTask = (id, body) => write(`/api/tasks/${id}/copy`, 'POST', body)
export const deleteTask = id => write(`/api/tasks/${id}`, 'DELETE')
export const updateTaskMeta = (id, changes) => write(`/api/tasks/${id}`, 'PUT', changes)
export const startRun = (id, body) => write(`/api/tasks/${id}/runs`, 'POST', body)
export const fetchRuns = id => read(`/api/tasks/${id}/runs`)
export const stopTask = id => write(`/api/tasks/${id}/stop`, 'POST')
export const retryRun = (id, runId) => write(`/api/tasks/${id}/runs/${runId}/retry`, 'POST')
export const fetchRunDetail = runId => read(`/api/task-runs/${runId}`)
export const fetchRunLogs = (runId, { nodeKey = '', level = '', afterId = 0, limit = 500 } = {}) =>
  read(`/api/task-runs/${runId}/logs?nodeKey=${encodeURIComponent(nodeKey)}&level=${encodeURIComponent(level)}&afterId=${afterId}&limit=${limit}`)
export const fetchRunNodePreview = (runId, nodeKey) => read(`/api/task-runs/${runId}/nodes/${encodeURIComponent(nodeKey)}/preview`)
export const testRun = body => write('/api/orch/test-run', 'POST', body)
export const queryPreview = body => write('/api/orch/query-preview', 'POST', body)
export const fetchSchedule = id => read(`/api/tasks/${id}/schedule`)
export const saveSchedule = (id, body) => write(`/api/tasks/${id}/schedule`, 'PUT', body)
export const deleteSchedule = id => write(`/api/tasks/${id}/schedule`, 'DELETE')
export const fetchDataSources = () => read('/api/data-sources')
export const fetchTaskDirectories = () => read('/api/task-directories')
export const fetchTables = sourceId => read(`/api/data-sources/${sourceId}/tables`)
export const fetchColumns = (sourceId, table) => read(`/api/data-sources/${sourceId}/columns?table=${encodeURIComponent(table)}`)