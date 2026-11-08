// 统一接口封装：自动带 token、统一解析 {code,msg,data}、统一中文错误提示
import { App } from 'antd'
import { useCallback } from 'react'

/** 从响应体中提取可读的中文错误信息（兼容后端统一格式与历史格式） */
export function errorText(json: any, fallback = '操作失败，请稍后重试'): string {
  if (!json) return fallback
  const v = json.msg ?? json.message ?? json.error
  if (typeof v === 'string' && v && !/^(Bad Request|Unauthorized|Forbidden|Internal Server Error|Not Found)$/i.test(v)) return v
  return fallback
}

/** 判断业务响应是否真正成功（此前只判断 400/403，导致 405/500 被误报"操作成功"） */
export function isOk(res: Response, json?: any): boolean {
  if (!res.ok) return false
  if (json && typeof json.code === 'number' && json.code !== 0) return false
  return true
}

/** 与 App.useApp() 配合的反馈 hook：所有页面统一用它，避免静态方法失效 */
export function useFeedback() {
  const { message, modal } = App.useApp()
  const ok = useCallback((text: string) => message.success(text), [message])
  const fail = useCallback((text: string) => message.error(text), [message])
  const warn = useCallback((text: string) => message.warning(text), [message])
  return { message, modal, ok, fail, warn }
}

/** 统一的写请求：返回 {ok, msg, data}；失败时 msg 为后端中文原因 */
export async function apiWrite(
  path: string,
  method: string,
  body?: any,
  apiFetchFn?: (p: string, init?: RequestInit) => Promise<Response>,
): Promise<{ ok: boolean; msg: string; data: any }> {
  const f = apiFetchFn ?? defaultFetch
  try {
    const res = await f(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    let json: any = null
    try { json = await res.json() } catch { /* 响应无 body */ }
    if (isOk(res, json)) return { ok: true, msg: json?.msg || '操作成功', data: json?.data }
    return { ok: false, msg: errorText(json, `操作失败（${res.status}）`), data: null }
  } catch (e: any) {
    return { ok: false, msg: e?.message === '登录已过期' ? '登录已过期，请重新登录' : '网络异常，请检查后端连接', data: null }
  }
}

async function defaultFetch(p: string, init?: RequestInit): Promise<Response> {
  const { apiFetch } = await import('../auth')
  return apiFetch(p, init)
}

/** 统一读请求，带 loading 之外的容错，返回 data（失败抛中文错误） */
export async function apiRead<T = any>(path: string): Promise<T> {
  const { apiFetch } = await import('../auth')
  const res = await apiFetch(path)
  const json = await res.json()
  if (!isOk(res, json)) throw new Error(errorText(json, `加载失败（${res.status}）`))
  return json.data as T
}
