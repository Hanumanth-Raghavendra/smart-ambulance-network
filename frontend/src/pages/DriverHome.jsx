import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useNetwork, useSub } from '../hooks.js';
import { useToast } from '../toast.jsx';
import { useAuth } from '../auth.jsx';
import MapView from '../components/MapView.jsx';
import { dist } from '../fmt.js';

export default function DriverHome() {
  const toast = useToast();
  const { user } = useAuth();
  const network = useNetwork();
  const [me, setMe] = useState(null);
  const [alert, setAlert] = useState(null);
  const [history, setHistory] = useState([]);
  const [watch, setWatch] = useState(false);

  const loadHistory = useCallback(() => api('/driver/alerts').then(setHistory).catch(() => {}), []);
  useEffect(() => { api('/driver/me').then(setMe).catch((e) => toast('Could not load profile', e.message, 'error')); loadHistory(); }, [loadHistory, toast]);

  useSub(user ? '/user/queue/alerts' : null, (e) => {
    if (e.type === 'ALERT') { setAlert(e.payload); loadHistory(); }
    else if (e.type === 'UPDATE') setAlert((cur) => ({ ...(cur || {}), ...e.payload }));
    else if (e.type === 'CLEAR') setAlert(null);
  });

  async function setLocation(lat, lng) {
    try { setMe(await api('/driver/location', { method: 'PUT', body: { lat, lng } })); }
    catch (e) { toast('Could not update location', e.message, 'error'); }
  }

  async function toggleOpt(enabled) {
    try { setMe(await api('/driver/optin', { method: 'PUT', body: { enabled } })); }
    catch (e) { toast('Could not update setting', e.message, 'error'); }
  }

  // optionally follow the real device position
  useEffect(() => {
    if (!watch || !navigator.geolocation) return undefined;
    let last = 0;
    const id = navigator.geolocation.watchPosition((p) => {
      if (Date.now() - last < 4000) return;
      last = Date.now();
      setLocation(p.coords.latitude, p.coords.longitude);
    }, () => { toast('Location blocked', 'Click the map to set your position instead.', 'warning'); setWatch(false); }, { enableHighAccuracy: true });
    return () => navigator.geolocation.clearWatch(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [watch]);

  if (!me) return <div className="empty">Loading...</div>;
  const pos = me.lastLat != null ? [me.lastLat, me.lastLng] : null;

  return (
    <div className="stack">
      {alert ? (
        <div className="alertbanner" role="alert" aria-live="assertive">
          <div className="big">🚑</div>
          <div>
            <div style={{ fontSize: '1.25rem', fontWeight: 800 }}>Ambulance approaching</div>
            <div>{alert.message}</div>
          </div>
        </div>
      ) : (
        <div className="card row between">
          <div><h1 style={{ margin: 0 }}>All clear</h1><div className="muted small">You will be alerted here when an emergency vehicle is about to pass your position.</div></div>
          <span className={`badge ${me.alertsOptIn ? 'ok' : 'muted'}`}>{me.alertsOptIn ? 'Alerts on' : 'Alerts off'}</span>
        </div>
      )}

      <div className="grid split">
        <div className="stack">
          <div className="card stack">
            <h3>Your settings</h3>
            <label className="row" style={{ gap: 10 }}>
              <input type="checkbox" checked={me.alertsOptIn} onChange={(e) => toggleOpt(e.target.checked)} />
              <span>Warn me when an ambulance is approaching</span>
            </label>
            <label className="row" style={{ gap: 10 }}>
              <input type="checkbox" checked={watch} onChange={(e) => setWatch(e.target.checked)} />
              <span>Follow my device location</span>
            </label>
            <div className="small">
              <b>Your position:</b> {pos ? `${pos[0].toFixed(5)}, ${pos[1].toFixed(5)}` : 'not set'}
              <div className="muted">Click the map to place yourself on a road. In the demo, the ambulance warns you when it is within about 900 m ahead of you on its route.</div>
            </div>
          </div>
          <div className="card">
            <h3>Recent alerts</h3>
            {history.length ? (
              <table className="t">
                <thead><tr><th>When</th><th>Trip</th><th>Distance</th><th>From</th></tr></thead>
                <tbody>
                  {history.map((a) => (
                    <tr key={a.id}>
                      <td>{new Date(a.createdAt).toLocaleTimeString()}</td><td>#{a.requestId}</td>
                      <td className="mono">{dist(a.distanceMeters)}</td><td>{a.direction}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <div className="muted small">No alerts yet.</div>}
          </div>
        </div>
        <MapView network={network} height="tall" onPick={setLocation} showNetwork
          markers={pos ? [{ key: 'me', kind: 'drv', pos, label: 'You' }] : []}
          center={pos || undefined} zoom={pos ? 14 : 12} fit={pos ? [pos] : null} fitKey={pos ? 'set' : 'unset'} />
      </div>
    </div>
  );
}
