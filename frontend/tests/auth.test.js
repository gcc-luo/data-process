import { test } from 'node:test'
import assert from 'node:assert/strict'
import { login, authorizedRequest } from '../src/auth.js'

test('login fetches CSRF token and submits encoded credentials', async () => {
  const requests = []
  const fetcher = async (url, options) => {
    requests.push([url, options])
    return url === '/api/csrf' ? { ok: true, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'abc' }) } : { ok: true, status: 204 }
  }
  await login('alice', 'p&ss', null, fetcher)
  assert.equal(requests[1][0], '/api/login')
  assert.equal(requests[1][1].headers['X-CSRF-TOKEN'], 'abc')
  assert.equal(new URLSearchParams(requests[1][1].body).get('password'), 'p&ss')
})

test('authorized mutation uses fresh CSRF token', async () => {
  const requests = []
  const fetcher = async (url, options) => {
    requests.push([url, options])
    return url === '/api/csrf' ? { ok: true, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'new' }) } : { ok: true }
  }
  await authorizedRequest('/api/workflows/validate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }, fetcher)
  assert.equal(requests[1][1].headers['X-CSRF-TOKEN'], 'new')
})
