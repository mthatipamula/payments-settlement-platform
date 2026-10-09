import { useRef, useState } from 'react'
import { api, cents, num, time, type Row } from './api'
import { errText, History, list, Msg, Status, useData } from './ui'

const WINDOWS = ['SAME_DAY_1', 'SAME_DAY_2', 'SAME_DAY_3', 'NEXT_DAY']

export default function AchView({ canOperate }: { canOperate: boolean }) {
  const [sub, setSub] = useState<'files' | 'entries' | 'settlement'>('files')
  const [fileId, setFileId] = useState<string | null>(null)
  return (
    <>
      <div className="seg">
        <button className={sub === 'files' ? 'active' : ''} onClick={() => setSub('files')}>Inbound files</button>
        <button className={sub === 'entries' ? 'active' : ''} onClick={() => { setFileId(null); setSub('entries') }}>Entries</button>
        <button className={sub === 'settlement' ? 'active' : ''} onClick={() => setSub('settlement')}>Settlement (FedACH)</button>
      </div>
      {sub === 'files' && <Files canOperate={canOperate} onOpen={(id) => { setFileId(id); setSub('entries') }} />}
      {sub === 'entries' && <Entries fileId={fileId} />}
      {sub === 'settlement' && <Settlement canOperate={canOperate} />}
    </>
  )
}

function Files({ canOperate, onOpen }: { canOperate: boolean; onOpen: (id: string) => void }) {
  const { data, error, reload } = useData(() => api<Row[]>('/api/v1/ach/files?direction=INBOUND'), [], 5000)
  const input = useRef<HTMLInputElement>(null)
  const [msg, setMsg] = useState<{ text: string; kind: 'ok' | 'error' }>({ text: '', kind: 'ok' })
  const [busy, setBusy] = useState(false)

  async function upload(f: File) {
    const form = new FormData(); form.append('file', f)
    try {
      const r = await api('/api/v1/ach/files', { method: 'POST', body: form })
      setMsg({ text: `Accepted ${f.name}: Spring Batch job ${r.jobExecutionId} queued.`, kind: 'ok' })
    } catch (e) { setMsg({ text: errText(e), kind: 'error' }) }
    if (input.current) input.current.value = ''
    reload()
  }
  async function demo() {
    setBusy(true)
    try {
      const r = await api('/api/v1/simulator/run?achFiles=3&wireCount=25', { method: 'POST' })
      setMsg({ text: `Demo traffic generated: ${JSON.stringify(r)}`, kind: 'ok' })
    } catch (e) { setMsg({ text: errText(e), kind: 'error' }) }
    setBusy(false); reload()
  }
  async function sample() {
    try {
      const text = await api<string>('/api/v1/simulator/sample-nacha')
      const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
      const a = document.createElement('a'); a.href = url; a.download = 'sample.ach'; a.click(); URL.revokeObjectURL(url)
    } catch (e) { setMsg({ text: errText(e), kind: 'error' }) }
  }

  return (
    <>
      <div className="filters">
        <h2>Inbound NACHA files</h2>
        {canOperate && <>
          <input ref={input} type="file" accept=".ach,.txt,text/plain" hidden onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
          <button onClick={() => input.current?.click()}>Upload NACHA file</button>
          <button className="secondary" onClick={sample}>Download sample file</button>
          <button className="secondary" disabled={busy} onClick={demo}>{busy ? 'Generating...' : 'Generate demo traffic'}</button>
        </>}
      </div>
      <Msg text={error || msg.text} kind={error ? 'error' : msg.kind} />
      <div className="table-wrap"><table>
        <thead><tr><th>Received</th><th>File</th><th>Origin</th><th>File ID</th><th className="r">Batches</th><th className="r">Entries</th>
          <th className="r">Debits</th><th className="r">Credits</th><th className="r">Held</th><th className="r">Dups</th><th>Status</th><th /></tr></thead>
        <tbody>{data?.map((f) => (
          <tr key={f.id}>
            <td className="nowrap">{time(f.received_at)}</td><td>{f.file_name}</td><td className="mono">{f.immediate_origin}</td>
            <td className="mono">{f.file_creation_date}{f.file_id_modifier}</td><td className="r">{num(f.batch_count)}</td><td className="r">{num(f.entry_count)}</td>
            <td className="r">{cents(f.total_debit_cents)}</td><td className="r">{cents(f.total_credit_cents)}</td>
            <td className="r">{Number(f.held) > 0 ? <b className="warn-text">{f.held}</b> : 0}</td><td className="r">{num(f.duplicates)}</td>
            <td><Status s={f.status} />{f.reject_reason && <div className="small bad-text">{f.reject_reason}</div>}</td>
            <td>{f.status !== 'REJECTED' && <button className="small-btn" onClick={() => onOpen(f.id)}>Entries</button>}</td>
          </tr>
        ))}</tbody>
      </table></div>
      {data?.length === 0 && <p className="muted">No files yet. Upload a NACHA file or generate demo traffic.</p>}
    </>
  )
}

function Entries({ fileId }: { fileId: string | null }) {
  const [status, setStatus] = useState('')
  const q = `/api/v1/ach/entries?${fileId ? `fileId=${fileId}&` : ''}${status ? `status=${status}` : ''}`
  const { data, error } = useData(() => api<Row[]>(q), [q], 10000)
  const file = useData(() => (fileId ? api<Row>(`/api/v1/ach/files/${fileId}`) : Promise.resolve(null)), [fileId])
  const [open, setOpen] = useState<Row | null>(null)
  const steps: Row[] = file.data?.job?.steps ? JSON.parse(file.data.job.steps) : []

  return (
    <>
      <div className="filters">
        <h2>{fileId ? `Entries in ${file.data?.file_name ?? 'file'}` : 'ACH entries (latest 500)'}</h2>
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status"><option value="">All statuses</option>
          {['ACCEPTED', 'HELD_FRAUD', 'DUPLICATE', 'REJECTED', 'SENT', 'SETTLED'].map((s) => <option key={s}>{s}</option>)}</select>
      </div>
      {steps.length > 0 && (
        <div className="steps">Spring Batch job <Status s={file.data?.job?.status} />
          {steps.map((s) => <span key={s.step} className="step"><b>{s.step}</b> <Status s={s.status} /> read {s.read} · write {s.write} · commits {s.commits}</span>)}</div>
      )}
      <Msg text={error} />
      <div className="table-wrap"><table>
        <thead><tr><th>Trace #</th><th>SEC</th><th>Code</th><th>Company</th><th>Receiver</th><th>RDFI</th><th className="r">Amount</th><th>Effective</th><th>Fraud</th><th>Status</th></tr></thead>
        <tbody>{data?.map((e) => (
          <tr key={e.id} className="clickable" onClick={() => api<Row>(`/api/v1/ach/entries/${e.id}`).then(setOpen)}>
            <td className="mono">{e.trace_number}</td><td>{e.sec_code}</td><td className="mono" title={e.direction}>{e.transaction_code}</td>
            <td>{e.company_name}</td><td>{e.individual_name} <span className="muted">••{e.account_last4}</span></td><td className="mono">{e.rdfi_routing}</td>
            <td className="r">{e.direction === 'DEBIT' ? '−' : ''}{cents(e.amount_cents)}</td><td className="nowrap">{e.effective_date}{e.same_day && <span className="pill">same-day</span>}</td>
            <td>{e.fraud_score > 0 ? <span title={list(e.fraud_reasons).join(', ')}>{e.fraud_score}</span> : ''}</td>
            <td><Status s={e.status} />{e.reject_reason && <div className="small bad-text">{e.reject_reason}</div>}</td>
          </tr>
        ))}</tbody>
      </table></div>
      {open && (
        <div className="drawer" role="dialog" aria-label="Entry detail">
          <div className="row-between"><h3>Entry {open.trace_number}</h3><button className="link" onClick={() => setOpen(null)}>Close</button></div>
          <dl className="kv">
            <dt>Status</dt><dd><Status s={open.status} /></dd>
            <dt>Amount</dt><dd>{cents(open.amount_cents)} {open.direction}</dd>
            <dt>Receiver</dt><dd>{open.individual_name} ••{open.account_last4} at {open.rdfi_routing}</dd>
            <dt>Fraud rules</dt><dd>{list(open.fraud_reasons).map((r) => <code key={r} className="rule">{r}</code>)}</dd>
            {open.duplicate_of && <><dt>Duplicate of</dt><dd className="mono">{open.duplicate_of}</dd></>}
          </dl>
          <h4>Audit trail</h4><History rows={open.history ?? []} />
        </div>
      )}
    </>
  )
}

function Settlement({ canOperate }: { canOperate: boolean }) {
  const { data, error, reload } = useData(() => api<Row[]>('/api/v1/ach/settlement/batches'), [], 5000)
  const [msg, setMsg] = useState<{ text: string; kind: 'ok' | 'error' }>({ text: '', kind: 'ok' })
  async function run(path: string) {
    try { const r = await api(path, { method: 'POST' }); setMsg({ text: JSON.stringify(r), kind: 'ok' }) } catch (e) { setMsg({ text: errText(e), kind: 'error' }) }
    reload()
  }
  return (
    <>
      <div className="filters"><h2>FedACH settlement windows</h2>
        {canOperate && <>
          {WINDOWS.map((w) => <button key={w} className="secondary" onClick={() => run(`/api/v1/ach/settlement/windows/${w}/run?forceSettle=true`)}>Run {w.replace(/_/g, ' ').toLowerCase()}</button>)}
          <button onClick={() => run('/api/v1/ach/settlement/sweep')}>Sweep due windows</button>
        </>}
      </div>
      <p className="muted small">Accepted entries are originated to FedACH at each window cutoff (ET) as a balanced NACHA file. "Run" forces a window now and marks it settled for the demo; the scheduler sweeps due windows every minute.</p>
      <Msg text={error || msg.text} kind={error ? 'error' : msg.kind} />
      <div className="table-wrap"><table>
        <thead><tr><th>Created</th><th>Window</th><th>Date</th><th className="r">Entries</th><th className="r">Debits</th><th className="r">Credits</th><th className="r">Net</th><th>File</th><th>Network ref</th><th>Status</th><th>Settled</th></tr></thead>
        <tbody>{data?.map((b) => (
          <tr key={b.id}><td className="nowrap">{time(b.created_at)}</td><td>{b.settlement_window}</td><td>{b.settlement_date}</td><td className="r">{num(b.entry_count)}</td>
            <td className="r">{cents(b.total_debit_cents)}</td><td className="r">{cents(b.total_credit_cents)}</td><td className="r"><b>{cents(b.net_cents)}</b></td>
            <td>{b.file_name}</td><td className="mono small">{b.network_reference}</td><td><Status s={b.status} /></td><td className="nowrap">{time(b.settled_at)}</td></tr>
        ))}</tbody>
      </table></div>
      {data?.length === 0 && <p className="muted">No settlement batches yet.</p>}
    </>
  )
}
