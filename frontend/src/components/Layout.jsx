import { Link } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { useWsConnected } from '../hooks.js';
import { ROLE_HOME, ROLE_LABEL } from '../fmt.js';
import { useEffect, useState } from 'react';
import { api } from '../api.js';

export default function Layout({ children }) {
  const { user, logout } = useAuth();
  const live = useWsConnected();
  const [speed, setSpeed] = useState(null);

  useEffect(() => {
    if (user) api('/network').then((n) => setSpeed(n.simSpeed)).catch(() => {});
  }, [user]);

  return (
    <>
      <header className="topbar">
        <Link className="brand" to={user ? ROLE_HOME[user.role] : '/login'}>
          <span className="logo">✚</span> Smart Ambulance Network
        </Link>
        <span className="spacer" />
        {speed && speed !== 1 ? <span className="chip" title="1 real second = this many simulated seconds">Simulation x{speed}</span> : null}
        {user ? (
          <>
            <span className="chip" title={live ? 'Live updates connected' : 'Reconnecting...'}>
              <span className={`dot ${live ? 'on' : 'off'}`} /> {live ? 'Live' : 'Offline'}
            </span>
            <span className="chip">{ROLE_LABEL[user.role]}</span>
            <span className="small">{user.name}</span>
            <button className="btn sm ghost" style={{ color: '#fff', borderColor: 'rgba(255,255,255,.3)' }} onClick={logout}>Sign out</button>
          </>
        ) : null}
      </header>
      <main className="page">{children}</main>
    </>
  );
}
