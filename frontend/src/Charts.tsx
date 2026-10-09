import { useMemo, useState } from 'react'

export interface Series { key: string; label: string; color: string; values: number[] }

const W = 720
const H = 220
const PAD = { top: 16, right: 64, bottom: 28, left: 52 }

function niceMax(v: number) {
  if (v <= 0) return 1
  const p = 10 ** Math.floor(Math.log10(v))
  return [1, 2, 2.5, 5, 10].map((m) => m * p).find((m) => m >= v) ?? v
}

/** Hourly line chart: one y-axis, 2px lines, crosshair + tooltip, legend + end-of-line direct labels. */
export function LineChart({ title, labels, series, format }: { title: string; labels: string[]; series: Series[]; format: (n: number) => string }) {
  const [hover, setHover] = useState<number | null>(null)
  const max = useMemo(() => niceMax(Math.max(0, ...series.flatMap((s) => s.values))), [series])
  const iw = W - PAD.left - PAD.right
  const ih = H - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (labels.length <= 1 ? 0 : (i / (labels.length - 1)) * iw)
  const y = (v: number) => PAD.top + ih - (v / max) * ih
  const ticks = [0, max / 2, max]

  return (
    <figure className="chart">
      <figcaption><span className="chart-title">{title}</span>
        <span className="legend">{series.map((s) => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</span>
      </figcaption>
      <div className="chart-box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title}
          onMouseLeave={() => setHover(null)}
          onMouseMove={(e) => {
            const r = (e.currentTarget as SVGSVGElement).getBoundingClientRect()
            const px = ((e.clientX - r.left) / r.width) * W
            const i = Math.round(((px - PAD.left) / iw) * (labels.length - 1))
            setHover(i >= 0 && i < labels.length ? i : null)
          }}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="grid" />
              <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" className="axis">{format(t)}</text>
            </g>
          ))}
          {labels.map((l, i) => i % 4 === 0 && <text key={l} x={x(i)} y={H - 8} textAnchor="middle" className="axis">{l.slice(11, 16)}</text>)}
          {series.map((s) => (
            <g key={s.key}>
              <path d={s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('')}
                fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
              <text x={x(s.values.length - 1) + 6} y={y(s.values[s.values.length - 1] ?? 0) + 4} className="direct-label">{s.label}</text>
            </g>
          ))}
          {hover != null && (
            <g>
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + ih} className="crosshair" />
              {series.map((s) => <circle key={s.key} cx={x(hover)} cy={y(s.values[hover] ?? 0)} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />)}
            </g>
          )}
        </svg>
        {hover != null && (
          <div className="tooltip" style={{ left: `${(x(hover) / W) * 100}%` }}>
            <strong>{labels[hover].replace('T', ' ')} ET</strong>
            {series.map((s) => <div key={s.key}><i style={{ background: s.color }} />{s.label}<b>{format(s.values[hover] ?? 0)}</b></div>)}
          </div>
        )}
      </div>
    </figure>
  )
}

/** Grouped hourly bars (one per series per hour) with a 2px surface gap and per-bar tooltip. */
export function BarChart({ title, labels, series }: { title: string; labels: string[]; series: Series[] }) {
  const [hover, setHover] = useState<{ i: number } | null>(null)
  const max = useMemo(() => niceMax(Math.max(1, ...series.flatMap((s) => s.values))), [series])
  const iw = W - PAD.left - PAD.right
  const ih = H - PAD.top - PAD.bottom
  const slot = iw / labels.length
  const barW = Math.max(2, (slot - 4) / series.length - 2)
  const y = (v: number) => PAD.top + ih - (v / max) * ih
  return (
    <figure className="chart">
      <figcaption><span className="chart-title">{title}</span>
        <span className="legend">{series.map((s) => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</span>
      </figcaption>
      <div className="chart-box">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} onMouseLeave={() => setHover(null)}>
          {[0, max / 2, max].map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="grid" />
              <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" className="axis">{Math.round(t)}</text>
            </g>
          ))}
          {labels.map((l, i) => (
            <g key={l} onMouseEnter={() => setHover({ i })}>
              <rect x={PAD.left + i * slot} y={PAD.top} width={slot} height={ih} fill="transparent" />
              {series.map((s, k) => {
                const v = s.values[i] ?? 0
                const h = Math.max(v > 0 ? 2 : 0, (v / max) * ih)
                return <rect key={s.key} x={PAD.left + i * slot + 2 + k * (barW + 2)} y={PAD.top + ih - h} width={barW} height={h} rx={Math.min(3, barW / 2)} fill={s.color} />
              })}
              {i % 4 === 0 && <text x={PAD.left + i * slot + slot / 2} y={H - 8} textAnchor="middle" className="axis">{l.slice(11, 16)}</text>}
            </g>
          ))}
        </svg>
        {hover && (
          <div className="tooltip" style={{ left: `${((PAD.left + (hover.i + 0.5) * slot) / W) * 100}%` }}>
            <strong>{labels[hover.i].replace('T', ' ')} ET</strong>
            {series.map((s) => <div key={s.key}><i style={{ background: s.color }} />{s.label}<b>{s.values[hover.i] ?? 0}</b></div>)}
          </div>
        )}
      </div>
    </figure>
  )
}
