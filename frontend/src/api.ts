// The console authenticates like any API client: an API key (local/service) or a bearer token (SSO).
export type Row = Record<string, any> // eslint-disable-line

export interface Credentials { kind: 'api-key' | 'bearer'; value: string; role: 'OPERATOR' | 'ANALYST' | 'TOKEN' }

const KEY = 'paysettle.credentials'

export function loadCredentials(): Credentials | null {
  try { return JSON.parse(sessionStorage.getItem(KEY) ?? 'null') } catch { return null }
}
export function saveCredentials(c: Credentials | null) {
  try { c ? sessionStorage.setItem(KEY, JSON.stringify(c)) : sessionStorage.removeItem(KEY) } catch { /* storage unavailable */ }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}

export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> { // eslint-disable-line
  const c = loadCredentials()
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) }
  if (c) headers[c.kind === 'api-key' ? 'X-API-Key' : 'Authorization'] = c.kind === 'api-key' ? c.value : `Bearer ${c.value}`
  if (init.body && typeof init.body === 'string' && !headers['Content-Type']) headers['Content-Type'] = 'application/json'
  const res = await fetch(path, { ...init, headers })
  const text = await res.text()
  const body = text ? (() => { try { return JSON.parse(text) } catch { return text } })() : null
  if (!res.ok) throw new ApiError(res.status, body?.code ?? String(res.status), body?.detail ?? body?.error ?? res.statusText)
  return body as T
}

export const usd = (n: unknown, digits = 2) =>
  n == null ? '-' : Number(n).toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: digits, maximumFractionDigits: digits })
export const usdCompact = (n: unknown) =>
  n == null ? '-' : Number(n).toLocaleString('en-US', { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1 })
export const cents = (n: unknown) => (n == null ? '-' : usd(Number(n) / 100))
export const num = (n: unknown) => (n == null ? '-' : Number(n).toLocaleString('en-US'))
export const time = (t: unknown) => (t ? new Date(String(t)).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-')
