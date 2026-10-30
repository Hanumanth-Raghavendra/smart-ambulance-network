import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { ROLE_HOME } from '../fmt.js';

const DEMO = [
  ['Patient', 'patient@sanet.demo'], ['Hospital', 'hospital1@sanet.demo'], ['Ambulance', 'ambulance1@sanet.demo'],
  ['Traffic police', 'police@sanet.demo'], ['Road user', 'driver1@sanet.demo'], ['Admin', 'admin@sanet.demo'],
];

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function go(e, p) {
    setBusy(true);
    setError('');
    try {
      const u = await login(e, p);
      nav(ROLE_HOME[u.role], { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth stack">
      <div className="card stack">
        <div>
          <h1>Sign in</h1>
          <div className="muted small">From tracking to traffic management: one connected network for every emergency.</div>
        </div>
        <form className="stack" onSubmit={(ev) => { ev.preventDefault(); go(email, password); }}>
          <label className="field">Email<input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" /></label>
          <label className="field">Password<input type="password" required value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
          {error ? <div className="alert-box" role="alert">{error}</div> : null}
          <button className="btn primary big" disabled={busy}>{busy ? 'Signing in...' : 'Sign in'}</button>
        </form>
        <div className="small muted">New patient or road user? <Link to="/register">Create an account</Link></div>
      </div>

      <div className="card stack">
        <h3>Demo accounts</h3>
        <div className="small muted">One click signs you in. Password for every demo account: <b>Demo@123</b></div>
        <div className="demo-grid">
          {DEMO.map(([label, mail]) => (
            <button key={mail} className="btn sm" disabled={busy} onClick={() => go(mail, 'Demo@123')}>{label}</button>
          ))}
        </div>
      </div>
    </div>
  );
}
