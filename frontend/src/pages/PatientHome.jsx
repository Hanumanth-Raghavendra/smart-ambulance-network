import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useRequest, useNetwork } from '../hooks.js';
import { useToast } from '../toast.jsx';
import MapView from '../components/MapView.jsx';
import TripMap from '../components/TripMap.jsx';
import { Condition, EtaBoard, RankingTable, SevBadge, StatusBadge, Stepper, StopsTable } from '../components/ui.jsx';
import { SPECIALITIES, STATUS_LABEL, dur, isActive } from '../fmt.js';

const CANCELLABLE = ['PENDING_HOSPITAL', 'WAITING_AMBULANCE', 'DISPATCHED', 'EN_ROUTE_TO_PATIENT', 'AT_PATIENT'];

function NewRequest({ onCreated, network }) {
  const toast = useToast();
  const [pos, setPos] = useState([17.43, 78.452]);
  const [severity, setSeverity] = useState('SERIOUS');
  const [speciality, setSpeciality] = useState('GENERAL');
  const [description, setDescription] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  function locate() {
    if (!navigator.geolocation) return toast('Location unavailable', 'Your browser cannot share its location. Click the map instead.', 'warning');
    navigator.geolocation.getCurrentPosition(
      (p) => setPos([p.coords.latitude, p.coords.longitude]),
      () => toast('Location blocked', 'Click the map to place the pin instead.', 'warning'),
      { enableHighAccuracy: true, timeout: 8000 });
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const v = await api('/requests', { method: 'POST', body: { lat: pos[0], lng: pos[1], severity, requiredSpeciality: speciality, description, phone, address } });
      onCreated(v);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid split">
      <form className="card stack" onSubmit={submit}>
        <div>
          <h1>Request an ambulance</h1>
          <div className="muted small">The best hospital is chosen from live bed counts, speciality and travel time. Then the road is cleared for the ambulance.</div>
        </div>
        <div>
          <div className="small muted" style={{ fontWeight: 600, marginBottom: 4 }}>How serious is it?</div>
          <div className="seg" role="group" aria-label="Severity">
            {['MINOR', 'SERIOUS', 'CRITICAL'].map((s) => (
              <button type="button" key={s} className={`${severity === s ? 'on' : ''} sev-${s}`} onClick={() => setSeverity(s)}>{s[0] + s.slice(1).toLowerCase()}</button>
            ))}
          </div>
        </div>
        <label className="field">Type of care needed
          <select value={speciality} onChange={(e) => setSpeciality(e.target.value)}>
            {SPECIALITIES.map((s) => <option key={s} value={s}>{s[0] + s.slice(1).toLowerCase()}</option>)}
          </select>
        </label>
        <label className="field">What happened? (optional)
          <textarea rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Chest pain, difficulty breathing..." />
        </label>
        <div className="grid cols-2">
          <label className="field">Contact number<input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+91 ..." /></label>
          <label className="field">Landmark / address<input value={address} onChange={(e) => setAddress(e.target.value)} /></label>
        </div>
        <div className="small"><b>Pickup point:</b> {pos[0].toFixed(5)}, {pos[1].toFixed(5)} <span className="muted">(click the map to move the pin)</span></div>
        {error ? <div className="alert-box" role="alert">{error}</div> : null}
        <button className="btn danger big" disabled={busy}>{busy ? 'Sending...' : 'Request ambulance now'}</button>
        <button type="button" className="btn" onClick={locate}>Use my current location</button>
        <div className="tiny muted">In a real emergency also call 108 / 112. This is a demonstration system.</div>
      </form>
      <MapView network={network} height="tall" onPick={(lat, lng) => setPos([lat, lng])} showHospitals
        markers={[{ key: 'me', kind: 'pat', pos, label: 'Pickup point' }]} fit={[pos]} fitKey="initial" zoom={13} center={pos} />
    </div>
  );
}

function Tracking({ id, onClose }) {
  const toast = useToast();
  const network = useNetwork();
  const { req, error } = useRequest(id);
  const [busy, setBusy] = useState(false);

  if (error) return <div className="alert-box">{error}</div>;
  if (!req) return <div className="empty">Loading your request...</div>;

  const done = !isActive(req.status);

  async function cancel() {
    if (!window.confirm('Cancel this ambulance request?')) return;
    setBusy(true);
    try { await api(`/requests/${id}/cancel`, { method: 'POST' }); } catch (e) { toast('Could not cancel', e.message, 'error'); } finally { setBusy(false); }
  }

  return (
    <div className="stack">
      <div className="card stack">
        <div className="row between">
          <div className="row"><h1 style={{ margin: 0 }}>Request #{req.id}</h1><StatusBadge status={req.status} /><SevBadge severity={req.severity} /></div>
          <div className="row">
            {CANCELLABLE.includes(req.status) ? <button className="btn danger sm" disabled={busy} onClick={cancel}>Cancel request</button> : null}
            {done ? <button className="btn primary sm" onClick={onClose}>Done</button> : null}
          </div>
        </div>
        <Stepper status={req.status} />
        {req.status === 'NO_HOSPITAL' ? <div className="alert-box">No hospital with a free bed could take you. Please call 108 / 112 now.</div> : null}
        {req.status === 'PENDING_HOSPITAL' ? <div className="info-box">Waiting for <b>{req.hospital && req.hospital.name}</b> to accept. If they cannot, the next best hospital is tried automatically.</div> : null}
        {req.status === 'WAITING_AMBULANCE' ? <div className="info-box">Hospital is ready. All ambulances are busy; the nearest one to free up will be sent.</div> : null}
        {req.status === 'COMPLETED' ? <div className="info-box">You have been handed over to {req.hospital && req.hospital.name}. Wishing you a quick recovery.</div> : null}
      </div>

      {req.ambulance ? <EtaBoard req={req} /> : null}

      <div className="grid cols-2">
        <div className="card">
          <h3>Hospital</h3>
          {req.hospital ? (
            <dl className="kv">
              <dt>Name</dt><dd>{req.hospital.name}</dd>
              <dt>Contact</dt><dd>{req.hospital.contact || '—'}</dd>
              <dt>Care type</dt><dd>{req.requiredSpeciality}</dd>
            </dl>
          ) : <div className="muted">Not assigned.</div>}
        </div>
        <div className="card">
          <h3>Ambulance</h3>
          {req.ambulance ? (
            <dl className="kv">
              <dt>Vehicle</dt><dd>{req.ambulance.vehicleNo}</dd>
              <dt>Paramedic</dt><dd>{req.ambulance.driverName || '—'}</dd>
              <dt>Status</dt><dd>{STATUS_LABEL[req.status]}</dd>
              <dt>Drivers warned</dt><dd>{req.alertsSent} alert{req.alertsSent === 1 ? '' : 's'}</dd>
            </dl>
          ) : <div className="muted">Not assigned yet.</div>}
        </div>
      </div>

      <TripMap req={req} network={network} height="tall" />

      {req.stops && req.stops.length ? (
        <div className="card">
          <h3>Junctions on the route</h3>
          <div className="muted small" style={{ marginBottom: 8 }}>Traffic police are asked to turn these signals green just before the ambulance arrives.</div>
          <StopsTable stops={req.stops} />
        </div>
      ) : null}

      {req.conditionNote || req.heartRate != null ? <div className="card"><h3>Paramedic update</h3><Condition req={req} /></div> : null}

      {req.ranking && req.ranking.length ? (
        <details className="card">
          <summary><b>Why this hospital?</b> <span className="muted small">(ranking)</span></summary>
          <div style={{ marginTop: 10 }}><RankingTable ranking={req.ranking} chosenId={req.hospital && req.hospital.id} /></div>
        </details>
      ) : null}
    </div>
  );
}

export default function PatientHome() {
  const network = useNetwork();
  const [list, setList] = useState(null);
  const [activeId, setActiveId] = useState(null);

  const load = useCallback(() => api('/requests/mine').then((l) => {
    setList(l);
    const a = l.find((r) => isActive(r.status));
    setActiveId((cur) => (cur && l.some((r) => r.id === cur) ? cur : a ? a.id : null));
  }), []);
  useEffect(() => { load(); }, [load]);

  if (!list) return <div className="empty">Loading...</div>;
  if (activeId) return <Tracking id={activeId} onClose={() => { setActiveId(null); load(); }} />;

  return (
    <div className="stack">
      <NewRequest network={network} onCreated={(v) => { setActiveId(v.id); load(); }} />
      {list.length ? (
        <div className="card">
          <h3>Your previous requests</h3>
          <div className="scroll-x">
            <table className="t">
              <thead><tr><th>#</th><th>Severity</th><th>Hospital</th><th>Outcome</th><th>Delay saved</th></tr></thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id} className="clickable" onClick={() => setActiveId(r.id)}>
                    <td>{r.id}</td><td><SevBadge severity={r.severity} /></td><td>{r.hospital ? r.hospital.name : '—'}</td>
                    <td><StatusBadge status={r.status} /></td><td className="mono">{dur(r.delaySavedSec)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </div>
  );
}
