async function csrf(fetcher) {
  const response = await fetcher('/api/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法获取安全令牌')
  const token = await response.json()
  return { [token.headerName]: token.token }
}

export async function login(username, password, fetcher = fetch) {
  const headers = await csrf(fetcher)
  const response = await fetcher('/api/login', { method: 'POST', credentials: 'same-origin', headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username, password }).toString() })
  if (!response.ok) throw new Error(response.status === 401 ? '用户名或密码错误，或账号已停用' : '登录失败，请稍后重试')
}

export async function authorizedRequest(url, options = {}, fetcher = fetch) {
  const headers = await csrf(fetcher)
  return fetcher(url, { ...options, credentials: 'same-origin', headers: { ...options.headers, ...headers } })
}
