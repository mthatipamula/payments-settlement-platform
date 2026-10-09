import { useState } from 'react'
import { api, num, time, usd, type Row } from './api'
import { errText, History, list, Msg, Status, useData } from './ui'

const STATUSES = ['RECEIVED', 'VALIDATED', 'HELD_FRAUD', 'HELD_SANCTIONS', 'HELD_DUPLICATE', 'RELEASED', 'SENT', 'SETTLED', 'REJECTED', 'CANCELLED']

export default function WiresView({ canOperate }: { canOperate: boolean }) {
  const [status, setStatus] = useState('')
  const q = `/api/v1/wires${status ? `?status=${status}` : ''}`
  const { data, error, reload } = useData(() => api<Row[]>(q), [q], 5000)
  const [open, setOpen] = useState<Row | null>(null)
  const [creating, setCreating] = useState(false)
  const show = (id: string) => api<Row>(`/api/v1/wires/${id}`).then(setOpen)

  return (
    <>
      <div className="filters">
        <h2>Fedwire Funds (ISO 20022 pacs.008)</h2>
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status"><option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s}>{s}</option>)}</select>
        <span className="muted small">{data ? `${num(data.length)} wires` : ''}</span>
        {canOperate && <button onClick={() => setCreating(!creating)}>{creating ? 'Close form' : 'New wire'}</button>}
      </div>
      {creating && <NewWire onDone={(id) => { setCreating(false); reload(); show(id) }} />}
      <Msg text={error} />
      <div className="table-wrap"><table>
        <thead><tr><th>Created</th><th className="r">Amount</th><th>Debtor</th><th>Creditor</th><th>UETR</th><th>Fraud</th><th>Status</th><th>IMAD / reason</th></tr></thead>
        <tbody>{data?.map((w) => (
          <tr key={w.id} className="clickable" onClick={() => show(w.id)}>
            <td className="nowrap">{time(w.created_at)}</td><td className="r"><b>{usd(w.amount)}</b></td>
            <td>{w.debtor_name} <span className="muted">••{w.debtor_account_last4}</span></td>
            <td>{w.creditor_name} <span className="muted">••{w.creditor_account_last4} · {w.creditor_agent_aba}</span>{w.sanctions_hit && <span className="status bad">OFAC</span>}</td>
            <td className="mono small">{String(w.uetr).slice(0, 8)}…</td>
            <td>{w.fraud_score > 0 ? <span title={list(w.fraud_reasons).join(', ')}>{w.fraud_score}</span> : ''}</td>
            <td><Status s={w.status} /></td>
            <td className="small">{w.network_reference ?? w.status_reason}</td>
          </tr>
        ))}</tbody>
      </table></div>
      {data?.length === 0 && <p className="muted">No wires yet.</p>}
      {open && <WireDetail w={open} canOperate={canOperate} onClose={() => setOpen(null)} onChange={(w) => { setOpen(w); reload() }} />}
    </>
  )
}

function WireDetail({ w, canOperate, onClose, onChange }: { w: Row; canOperate: boolean; onClose: () => void; onChange: (w: Row) => void }) {
  const [msg, setMsg] = useState('')
  const [xml, setXml] = useState<'pacs008' | 'pacs002' | null>(null)
  async function act(action: 'release' | 'cancel') {
    try { onChange(await api(`/api/v1/wires/${w.id}/${action}`, { method: 'POST' })); setMsg('') } catch (e) { setMsg(errText(e)) }
  }
  return (
    <div className="drawer wide" role="dialog" aria-label="Wire detail">
      <div className="row-between"><h3>{usd(w.amount)} to {w.creditor_name}</h3><button className="link" onClick={onClose}>Close</button></div>
      <Msg text={msg} />
      <dl className="kv">
        <dt>Status</dt><dd><Status s={w.status} /> {w.status_reason}</dd>
        <dt>UETR</dt><dd className="mono">{w.uetr}</dd>
        <dt>End-to-end ID</dt><dd className="mono">{w.end_to_end_id}</dd>
        <dt>Debtor</dt><dd>{w.debtor_name} ••{w.debtor_account_last4} · ABA {w.debtor_agent_aba}</dd>
        <dt>Creditor</dt><dd>{w.creditor_name} ••{w.creditor_account_last4} · ABA {w.creditor_agent_aba}</dd>
        <dt>Value date</dt><dd>{w.value_date} · channel {w.channel}</dd>
        <dt>Remittance</dt><dd>{w.remittance_info ?? '-'}</dd>
        <dt>Fraud</dt><dd>{w.fraud_score ?? 0} {list(w.fraud_reasons).map((r) => <code key={r} className="rule">{r}</code>)}{w.sanctions_hit && <span className="status bad">Sanctions match</span>}</dd>
        {w.duplicate_of && <><dt>Possible duplicate of</dt><dd className="mono">{w.duplicate_of}</dd></>}
        <dt>Network</dt><dd>{w.network} {w.network_reference && <>· IMAD <span className="mono">{w.network_reference}</span></>}</dd>
      </dl>
      <div className="actions">
        {canOperate && w.status === 'HELD_DUPLICATE' && <button onClick={() => act('release')}>Not a duplicate: release</button>}
        {canOperate && ['HELD_FRAUD', 'HELD_DUPLICATE', 'RELEASED'].includes(w.status) && <button className="danger" onClick={() => act('cancel')}>Cancel wire</button>}
        <button className="secondary" onClick={() => setXml(xml === 'pacs008' ? null : 'pacs008')}>pacs.008 XML</button>
        {w.pacs002 && <button className="secondary" onClick={() => setXml(xml === 'pacs002' ? null : 'pacs002')}>pacs.002 status XML</button>}
      </div>
      {['HELD_FRAUD', 'HELD_SANCTIONS'].includes(w.status) && <p className="muted small">Fraud and sanctions holds are resolved by an analyst under Fraud alerts.</p>}
      {xml && <pre className="xml">{w[xml]}</pre>}
      <h4>Audit trail</h4><History rows={w.history ?? []} />
    </div>
  )
}

const DEMO = {
  amount: '18250.00', debtorName: 'Acme Manufacturing Inc', debtorAccount: '4401229871', debtorRouting: '123456780',
  creditorName: 'Blue Ridge Supply Co', creditorAccount: '7730015512', creditorRouting: '021000021', remittanceInfo: 'Invoice 88231',
}

function NewWire({ onDone }: { onDone: (id: string) => void }) {
  const [f, setF] = useState(DEMO)
  const [key, setKey] = useState<string>(() => crypto.randomUUID())
  const [msg, setMsg] = useState<{ text: string; kind: 'ok' | 'error' }>({ text: '', kind: 'ok' })
  const set = (k: keyof typeof DEMO) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  async function submit() {
    try {
      const r = await api<Row>('/api/v1/wires', {
        method: 'POST', headers: { 'Idempotency-Key': key },
        body: JSON.stringify({
          amount: f.amount, currency: 'USD', remittanceInfo: f.remittanceInfo || null,
          debtor: { name: f.debtorName, accountNumber: f.debtorAccount, routingNumber: f.debtorRouting },
          creditor: { name: f.creditorName, accountNumber: f.creditorAccount, routingNumber: f.creditorRouting },
        }),
      })
      if (r.idempotentReplay) setMsg({ text: 'Same Idempotency-Key: the original wire was returned, no new payment created.', kind: 'ok' })
      else onDone(r.id)
    } catch (e) { setMsg({ text: errText(e), kind: 'error' }) }
  }
  return (
    <div className="card form">
      <div className="grid3">
        <label>Amount (USD)<input value={f.amount} onChange={set('amount')} inputMode="decimal" /></label>
        <label>Remittance info<input value={f.remittanceInfo} onChange={set('remittanceInfo')} maxLength={140} /></label>
        <label>Idempotency-Key<div className="inline"><input value={key} onChange={(e) => setKey(e.target.value)} className="mono" />
          <button className="secondary" onClick={() => setKey(crypto.randomUUID())}>New</button></div></label>
        <label>Debtor name<input value={f.debtorName} onChange={set('debtorName')} /></label>
        <label>Debtor account<input value={f.debtorAccount} onChange={set('debtorAccount')} /></label>
        <label>Debtor bank ABA<input value={f.debtorRouting} onChange={set('debtorRouting')} maxLength={9} /></label>
        <label>Creditor name<input value={f.creditorName} onChange={set('creditorName')} /></label>
        <label>Creditor account<input value={f.creditorAccount} onChange={set('creditorAccount')} /></label>
        <label>Creditor bank ABA<input value={f.creditorRouting} onChange={set('creditorRouting')} maxLength={9} /></label>
      </div>
      <p className="muted small">Try: submit twice with the same key (idempotent replay); a new key with the same parties and amount (held as possible duplicate);
        creditor "Viktor Malenkov" (sanctions hold); account ending 0000 (Fedwire AC01 reject); amount over $250,000 (fraud review).</p>
      <Msg text={msg.text} kind={msg.kind} />
      <button onClick={submit}>Submit wire</button>
    </div>
  )
}
