import { useCallback, useEffect, useState } from 'react'
import { api, num, usdCompact, type Row } from './api'
import { BarChart, LineChart } from './Charts'

const ACH = 'var(--series-1)'
const WIRE = 'var(--series-2)'

export default function Overview() {
  const [summary, setSummary] = useState<Row | null>(null)
  const [hourly, setHourly] = useState<Row[]>([])
  const [rules, setRules] = useState<Row[]>([])
  const [showTable, setShowTable] = useState(false)
  const [updated, setUpdated] = useState('')
  const load = useCallback(async () => {
    const [s, h, r] = await Promise.all([api('/api/v1/observability/summary'), api<Row[]>('/api/v1/observability/hourly?hours=24'), api<Row[]>('/api/v1/observability/fraud-rules')])
    setSummary(s); setHourly(h); setRules(r); setUpdated(new Date().toLocaleTimeString())
  }, [])
  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t) }, [load])
  if (!summary) return <p className="muted">Loading...</p>

  const a = summary.ach, w = summary.wire, f = summary.fraud, d = summary.duplicates
  const labels = hourly.map((h) => h.hour_et as string)
  const col = (k: string) => hourly.map((h) => Number(h[k]))
  return (
    <>
      <div className="row-between"><h2>Today</h2><span className="muted small">Auto-refresh every 15s · updated {updated}</span></div>
      <div className="tiles">
        <Tile label="ACH entries" value={num(a.entries)} sub={`${usdCompact(a.amount)} · ${num(a.settled)} settled · ${num(a.same_day)} same-day`} />
        <Tile label="Wires" value={num(w.wires)} sub={`${usdCompact(w.amount)} · ${num(w.settled)} settled (${usdCompact(w.settled_amount)})`} />
        <Tile label="Open fraud alerts" value={num(f.open_alerts)} tone={f.open_alerts > 0 ? 'warn' : undefined} sub={`ACH ${f.open_ach} · Wire ${f.open_wire} · ${usdCompact(f.open_amount)} held`} />
        <Tile label="Sanctions holds" value={num(w.sanctions_holds)} tone={w.sanctions_holds > 0 ? 'bad' : undefined} sub="awaiting compliance" />
        <Tile label="Duplicates caught" value={num(d.today)} sub={`ACH ${d.ach} · Wire ${d.wire}`} />
        <Tile label="Rejected" value={num(Number(a.rejected) + Number(w.rejected))} sub={`ACH ${a.rejected} · Wire ${w.rejected}`} />
        <Tile label="In flight" value={num(Number(a.in_flight) + Number(w.in_flight))} sub={`outbox ${summary.outbox.pending} pending${summary.outbox.failed > 0 ? ` · ${summary.outbox.failed} FAILED` : ''}`} tone={summary.outbox.failed > 0 ? 'bad' : undefined} />
        <Tile label="ACH files" value={num(summary.achFiles.files)} sub={`${summary.achFiles.rejected} rejected · ${summary.achFiles.processing} processing`} />
      </div>

      <div className="row-between"><h2>Last 24 hours (hourly, Eastern)</h2>
        <button className="link" onClick={() => setShowTable(!showTable)}>{showTable ? 'Show charts' : 'Show as table'}</button></div>
      {showTable ? <HourlyTable rows={hourly} /> : (
        <div className="charts">
          <LineChart title="Payments per hour" labels={labels} format={(n) => num(Math.round(n))}
            series={[{ key: 'ach', label: 'ACH', color: ACH, values: col('ach_count') }, { key: 'wire', label: 'Wire', color: WIRE, values: col('wire_count') }]} />
          <LineChart title="Value per hour" labels={labels} format={(n) => usdCompact(n)}
            series={[{ key: 'ach', label: 'ACH', color: ACH, values: col('ach_amount') }, { key: 'wire', label: 'Wire', color: WIRE, values: col('wire_amount') }]} />
          <BarChart title="Fraud alerts per hour" labels={labels}
            series={[{ key: 'ach', label: 'ACH', color: ACH, values: col('ach_fraud_alerts') }, { key: 'wire', label: 'Wire', color: WIRE, values: col('wire_fraud_alerts') }]} />
          <BarChart title="Duplicates per hour" labels={labels}
            series={[{ key: 'ach', label: 'ACH', color: ACH, values: col('ach_duplicates') }, { key: 'wire', label: 'Wire', color: WIRE, values: col('wire_duplicates') }]} />
        </div>
      )}

      <h2>Fraud rules fired (7 days)</h2>
      <div className="table-wrap"><table><thead><tr><th>Rail</th><th>Rule</th><th className="r">Alerts</th><th className="r">Amount</th></tr></thead>
        <tbody>{rules.map((r) => <tr key={r.rail + r.rule}><td><span className={`pill rail-${r.rail}`}>{r.rail}</span></td><td><code>{r.rule}</code></td><td className="r">{num(r.hits)}</td><td className="r">{usdCompact(r.amount)}</td></tr>)}</tbody></table></div>
    </>
  )
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'warn' | 'bad' }) {
  return <div className={`tile ${tone ?? ''}`}><div className="tile-label">{label}</div><div className="tile-value">{value}</div>{sub && <div className="tile-sub">{sub}</div>}</div>
}

function HourlyTable({ rows }: { rows: Row[] }) {
  return (
    <div className="table-wrap"><table>
      <thead><tr><th>Hour (ET)</th><th className="r">ACH #</th><th className="r">ACH $</th><th className="r">ACH fraud</th><th className="r">ACH dups</th><th className="r">ACH rej</th>
        <th className="r">Wire #</th><th className="r">Wire $</th><th className="r">Wire fraud</th><th className="r">Wire dups</th><th className="r">Wire rej</th></tr></thead>
      <tbody>{[...rows].reverse().map((r) => (
        <tr key={r.hour_et}><td>{String(r.hour_et).replace('T', ' ')}</td><td className="r">{num(r.ach_count)}</td><td className="r">{usdCompact(r.ach_amount)}</td>
          <td className="r">{num(r.ach_fraud_alerts)}</td><td className="r">{num(r.ach_duplicates)}</td><td className="r">{num(r.ach_rejected)}</td>
          <td className="r">{num(r.wire_count)}</td><td className="r">{usdCompact(r.wire_amount)}</td><td className="r">{num(r.wire_fraud_alerts)}</td>
          <td className="r">{num(r.wire_duplicates)}</td><td className="r">{num(r.wire_rejected)}</td></tr>
      ))}</tbody>
    </table></div>
  )
}
