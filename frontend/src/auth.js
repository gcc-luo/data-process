async function csrf(fetcher) {
  const response = await fetcher('/api/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法获取安全令牌')
  const token = await response.json()
  return { [token.headerName]: token.token }
}

export async function login(username, password, captcha, fetcher = fetch) {
  const headers = await csrf(fetcher)
  const body = new URLSearchParams({ username, password })
  if (captcha) body.set('captcha', captcha)
  const response = await fetcher('/api/login', { method: 'POST', credentials: 'same-origin', headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() })
  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    const error = new Error(payload?.error || (response.status === 401 ? '用户名或密码错误，或账号已停用' : '登录失败，请稍后重试'))
    error.captchaRequired = Boolean(payload?.captchaRequired)
    throw error
  }
}

export function sessionExpired(response) {
  if (response.status === 401) { window.dispatchEvent(new Event('governance:session-expired')); return true }
  return false
}

export async function authorizedRequest(url, options = {}, fetcher = fetch) {
  const headers = await csrf(fetcher)
  const response = await fetcher(url, { ...options, credentials: 'same-origin', headers: { ...options.headers, ...headers } })
  if (response.status === 401) window.dispatchEvent(new Event('governance:session-expired'))
  return response
}
