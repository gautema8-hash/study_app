// 后台认证：token 管理 + 统一请求封装（401 自动跳登录）
const KEY = 'adminToken'
let TOKEN = localStorage.getItem(KEY) || ''

export function setToken(t: string) { TOKEN = t; localStorage.setItem(KEY, t) }
export function clearToken() { TOKEN = ''; localStorage.removeItem(KEY) }
export function getToken() { return TOKEN }

export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  // 已退出登录时直接短路，避免页面卸载前继续发出一堆 401 请求
  if (!TOKEN) {
    if (!window.location.hash.startsWith('#/login')) window.location.hash = '#/login'
    throw new Error('登录已过期')
  }
  const r = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
      ...(init?.headers || {}),
    },
  })
  if (r.status === 401) {
    clearToken()
    window.location.hash = '#/login'
    throw new Error('登录已过期')
  }
  return r
}
