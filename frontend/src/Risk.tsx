import { Fragment, useState } from 'react'
import { api, num, time, usd, type Row } from './api'
import { errText, list, Msg, Rail, Status, useData } from './ui'

export function FraudAlerts({ canResolve }: { canResolve: boolean }) {
  const [status, setStatus] = useState('OPEN')
  const [rail, setRail] = useState('')
  const q = `/api/v1/fraud-alerts?status=${status}${rail ? `&rail=${rail}` : ''}`
  const { data, error, reload } = useData(() => api<Row[]>(q), [q], 15000)
  const [open, setOpen] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [msg, setMsg] = useState('')

  async function resolve(id: string, action: 'clear' | 'confirm') {
    try {
      await api(`/api/v1/fraud-alerts/${id}/${action}`, { method: 'POST', body: JSON.stringify({ note }) })
      setMsg(''); setOpen(null); setNote(''); reload()
    } catch (e) { setMsg(errText(e)) }
  }

  return (
    <>
      <div className="filters">
        <h2>Fraud & sanctions alerts</h2>
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
          <option value="OPEN">Open</option><option value="CLEARED">Cleared</option><option value="CONFIRMED_FRAUD">Confirmed fraud</option><option value="ALL">All</option></select>
        <select value={rail} onChange={(e) => setRail(e.target.value)} aria-label="Rail"><option value="">All rails</option><option>ACH</option><option>WIRE</option></select>
        <span className="muted small">{data ? `${num(data.length)} alerts` : ''}</span>
      </div>
      {!canResolve && <p className="muted small">Signed in as operator: alerts are read-only. A fraud analyst clears or confirms them (maker-checker).</p>}
      <Msg text={error || msg} />
      <div className="table-wrap"><table>
        <thead><tr><th>Raised</th><th>Rail</th><th className="r">Score</th><th>Action</th><th className="r">Amount</th><th>Summary</th><th>Rules</th><th>Payment</th><th>Alert</th><th /></tr></thead>
        <tbody>{data?.map((a) => (<Fragment key={a.id}>
          <tr>
            <td className="nowrap">{time(a.created_at)}</td><td><Rail r={a.rail} /></td><td className="r"><b>{a.score}</b></td><td><Status s={a.action} /></td>
            <td className="r">{usd(a.amount)}</td><td className="summary">{a.summary}</td>
            <td>{list(a.rules).map((r) => <code key={r} className="rule">{r}</code>)}</td>
            <td><Status s={a.payment_status} /></td><td><Status s={a.status} />{a.resolved_by && <div className="muted small">{a.resolved_by}</div>}</td>
            <td>{canResolve && a.status === 'OPEN' && <button className="small-btn" onClick={() => { setOpen(open === a.id ? null : a.id); setNote('') }}>Review</button>}</td>
          </tr>
          {open === a.id && (
            <tr className="expand"><td colSpan={10}>
              <label>Analyst note (required, kept in the audit trail)<textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2}
                placeholder="e.g. Called customer on file; payroll confirmed by CFO" /></label>
              <div className="actions">
                <button disabled={!note.trim()} onClick={() => resolve(a.id, 'clear')}>Clear and release payment</button>
                <button className="danger" disabled={!note.trim()} onClick={() => resolve(a.id, 'confirm')}>Confirm fraud and reject</button>
              </div>
            </td></tr>
          )}
          {a.note && open !== a.id && <tr className="note-row"><td colSpan={10} className="muted small">Note: {a.note}</td></tr>}
        </Fragment>))}</tbody>
      </table></div>
      {data?.length === 0 && <p className="muted">No alerts match.</p>}
    </>
  )
}

const KIND_LABEL: Record<string, string> = {
  ACH_FILE: 'ACH file re-sent (content hash or NACHA file ID)', ACH_TRACE: 'ACH trace number reused',
  ACH_ENTRY_MATCH: 'ACH entry repeated (receiver, amount, date)', ACH_ENTRY_MATCH_IN_FILE: 'ACH entry repeated within file',
  WIRE_IDEMPOTENT_REPLAY: 'Wire client retry (Idempotency-Key)', WIRE_UETR: 'Wire UETR reused', WIRE_MATCH: 'Wire near-duplicate held',
}

export function Duplicates() {
  const [rail, setRail] = useState('')
  const q = `/api/v1/observability/duplicates${rail ? `?rail=${rail}` : ''}`
  const { data, error } = useData(() => api<Row[]>(q), [q], 15000)
  const byKind = new Map<string, number>()
  data?.forEach((d) => byKind.set(d.kind, (byKind.get(d.kind) ?? 0) + 1))
  return (
    <>
      <div className="filters">
        <h2>Duplicate detection</h2>
        <select value={rail} onChange={(e) => setRail(e.target.value)} aria-label="Rail"><option value="">All rails</option><option>ACH</option><option>WIRE</option></select>
      </div>
      <p className="muted small">ACH: whole-file (SHA-256 and NACHA file ID), trace number, and entry match (receiver account, amount, effective date).
        Wires: Idempotency-Key replay, UETR reuse, and near-duplicates held for operator release.</p>
      <div className="tiles">{[...byKind].map(([k, n]) => <div key={k} className="tile"><div className="tile-label">{KIND_LABEL[k] ?? k}</div><div className="tile-value">{num(n)}</div></div>)}</div>
      <Msg text={error} />
      <div className="table-wrap"><table>
        <thead><tr><th>Detected</th><th>Rail</th><th>Check</th><th className="r">Amount</th><th>Detail</th><th>Payment / original</th></tr></thead>
        <tbody>{data?.map((d) => (
          <tr key={d.id}><td className="nowrap">{time(d.created_at)}</td><td><Rail r={d.rail} /></td><td>{KIND_LABEL[d.kind] ?? d.kind}</td>
            <td className="r">{d.amount == null ? '-' : usd(d.amount)}</td><td className="summary">{d.detail}</td>
            <td className="mono small">{String(d.payment_id ?? '').slice(0, 8)}{d.duplicate_of ? ` → ${String(d.duplicate_of).slice(0, 8)}` : ''}</td></tr>
        ))}</tbody>
      </table></div>
      {data?.length === 0 && <p className="muted">No duplicates detected.</p>}
    </>
  )
}
