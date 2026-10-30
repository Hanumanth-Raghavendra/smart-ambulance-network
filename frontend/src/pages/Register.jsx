import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { ROLE_HOME } from '../fmt.js';

export default function Register() {
  const { register } = useAuth();
  const nav = useNavigate();
  const [f, setF] = useState({ name: '', email: '', phone: '', password: '', role: 'PATIENT' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const u = await register(f);
      nav(ROLE_HOME[u.role], { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <form className="card stack" onSubmit={submit}>
        <h1>Create account</h1>
        <div className="muted small">Hospitals, paramedics, police and admins are added by an administrator.</div>
        <div className="seg">
          <button type="button" className={f.role === 'PATIENT' ? 'on' : ''} onClick={() => setF({ ...f, role: 'PATIENT' })}>I need emergency help</button>
          <button type="button" className={f.role === 'DRIVER' ? 'on' : ''} onClick={() => setF({ ...f, role: 'DRIVER' })}>I drive on the road</button>
        </div>
        <label className="field">Full name<input required value={f.name} onChange={set('name')} /></label>
        <label className="field">Email<input type="email" required value={f.email} onChange={set('email')} /></label>
        <label className="field">Phone<input value={f.phone} onChange={set('phone')} placeholder="+91 ..." /></label>
        <label className="field">Password (min 6 characters)<input type="password" required minLength={6} value={f.password} onChange={set('password')} /></label>
        {error ? <div className="alert-box" role="alert">{error}</div> : null}
        <button className="btn primary big" disabled={busy}>{busy ? 'Creating...' : 'Create account'}</button>
        <div className="small muted">Already registered? <Link to="/login">Sign in</Link></div>
      </form>
    </div>
  );
}
