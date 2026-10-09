import { useCallback, useEffect, useState } from 'react'
import { ApiError } from './api'

/** Loads data, reloads on demand and polls every `every` ms (0 = no polling). */
export function useData<T>(load: () => Promise<T>, deps: unknown[], every = 0) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState('')
  const reload = useCallback(() => load().then((d) => { setData(d); setError('') }, (e) => setError(errText(e))), deps) // eslint-disable-line
  useEffect(() => { reload(); if (!every) return; const t = setInterval(reload, every); return () => clearInterval(t) }, [reload, every])
  return { data, error, reload }
}

export const errText = (e: unknown) => (e instanceof ApiError ? `${e.code}: ${e.message}` : String(e))

const STATUS_TONE: Record<string, string> = {
  SETTLED: 'good', ACCEPTED: 'info', RELEASED: 'info', SENT: 'info', SCREENED: 'info', RECEIVED: 'info', PROCESSED: 'good', PROCESSING: 'info',
  TRANSMITTED: 'info', ACKNOWLEDGED: 'info', SCHEDULED: 'info', VALIDATED: 'info', CREATED: 'info', CLEARED: 'good', OPEN: 'warn', HELD_FRAUD: 'warn', HELD_DUPLICATE: 'warn', HELD_SANCTIONS: 'bad', DUPLICATE: 'warn',
  REJECTED: 'bad', CONFIRMED_FRAUD: 'bad', CANCELLED: 'muted', RETURNED: 'bad', FAILED: 'bad', COMPLETED: 'good', BLOCK: 'bad', REVIEW: 'warn',
}
export function Status({ s }: { s: unknown }) {
  const v = String(s ?? '-')
  return <span className={`status ${STATUS_TONE[v] ?? 'muted'}`}>{v.replace(/_/g, ' ')}</span>
}
export const Rail = ({ r }: { r: unknown }) => <span className={`pill rail-${r}`}>{String(r)}</span>
export const list = (v: unknown) => (Array.isArray(v) ? v : v ? String(v).replace(/[{}"]/g, '').split(',').filter(Boolean) : [])

export function Msg({ text, kind = 'error' }: { text: string; kind?: 'error' | 'ok' }) {
  return text ? <div className={`msg ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{text}</div> : null
}

export function History({ rows }: { rows: { from_status?: string; to_status: string; actor: string; detail?: string; created_at: string }[] }) {
  return (
    <ol className="history">{rows.map((h, i) => (
      <li key={i}><span className="muted small">{new Date(h.created_at).toLocaleTimeString()}</span> {h.from_status ? <><Status s={h.from_status} /> → </> : null}<Status s={h.to_status} />
        <span className="muted small"> by {h.actor}</span>{h.detail && <div className="small">{h.detail}</div>}</li>
    ))}</ol>
  )
}
