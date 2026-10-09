import { useState } from 'react'
import { loadCredentials, saveCredentials, type Credentials } from './api'
import Overview from './Overview'
import { FraudAlerts, Duplicates } from './Risk'
import AchView from './Ach'
import WiresView from './Wires'

type Tab = 'overview' | 'alerts' | 'duplicates' | 'ach' | 'wires'
const TABS: [Tab, string][] = [['overview', 'Overview'], ['alerts', 'Fraud alerts'], ['duplicates', 'Duplicates'], ['ach', 'ACH'], ['wires', 'Wires']]

export default function App() {
  const [creds, setCreds] = useState<Credentials | null>(loadCredentials())
  const [tab, setTab] = useState<Tab>('overview')
  if (!creds) return <SignIn onSignIn={(c) => { saveCredentials(c); setCreds(c) }} />
  return (
    <>
      <header className="topbar">
        <div className="brand"><span className="logo">≡</span><div><b>PaySettle</b><span className="muted"> · ACH & Wire Settlement Operations</span></div></div>
        <div className="who">
          <span className={`pill role-${creds.role}`}>{creds.role === 'TOKEN' ? 'SSO token' : creds.role}</span>
          <button className="link" onClick={() => { saveCredentials(null); setCreds(null) }}>Sign out</button>
        </div>
      </header>
      <nav className="tabs">{TABS.map(([id, label]) => <button key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>)}</nav>
      <main>
        {tab === 'overview' && <Overview />}
        {tab === 'alerts' && <FraudAlerts canResolve={creds.role !== 'OPERATOR'} />}
        {tab === 'duplicates' && <Duplicates />}
        {tab === 'ach' && <AchView canOperate={creds.role !== 'ANALYST'} />}
        {tab === 'wires' && <WiresView canOperate={creds.role !== 'ANALYST'} />}
      </main>
    </>
  )
}

function SignIn({ onSignIn }: { onSignIn: (c: Credentials) => void }) {
  const [mode, setMode] = useState<'api-key' | 'bearer'>('api-key')
  const [value, setValue] = useState('')
  const [role, setRole] = useState<'OPERATOR' | 'ANALYST'>('OPERATOR')
  return (
    <div className="signin card">
      <h1><span className="logo">≡</span> PaySettle console</h1>
      <p className="muted">Operations view of ACH and wire settlement: hourly statistics, fraud and sanctions alerts, duplicates and settlement.</p>
      <div className="seg">
        <button className={mode === 'api-key' ? 'active' : ''} onClick={() => setMode('api-key')}>API key</button>
        <button className={mode === 'bearer' ? 'active' : ''} onClick={() => setMode('bearer')}>SSO bearer token</button>
      </div>
      {mode === 'api-key' && (
        <label>Role<select value={role} onChange={(e) => setRole(e.target.value as 'OPERATOR' | 'ANALYST')}>
          <option value="OPERATOR">Payments operator</option><option value="ANALYST">Fraud analyst</option></select></label>
      )}
      <label>{mode === 'api-key' ? 'API key' : 'Access token'}<input type="password" value={value} onChange={(e) => setValue(e.target.value)}
        placeholder={mode === 'api-key' ? (role === 'OPERATOR' ? 'local-operator-key' : 'local-analyst-key') : 'eyJ...'} /></label>
      <button disabled={!value} onClick={() => onSignIn({ kind: mode, value, role: mode === 'bearer' ? 'TOKEN' : role })}>Continue</button>
      <p className="muted small">Operators submit payments and run settlement; analysts clear or confirm fraud and sanctions holds (maker-checker).</p>
    </div>
  )
}
