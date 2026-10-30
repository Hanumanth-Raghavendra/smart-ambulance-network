import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useNetwork, useSignalStates, useSub } from '../hooks.js';
import { useToast } from '../toast.jsx';
import TripMap from '../components/TripMap.jsx';
import { EtaBoard, SevBadge, StatusBadge, Stepper, StopsTable } from '../components/ui.jsx';
import { isActive } from '../fmt.js';

function ConditionForm({ req }) {
  const toast = useToast();
  const [note, setNote] = useState(req.conditionNote || '');
  const [hr, setHr] = useState(req.heartRate ?? '');
  const [spo2, setSpo2] = useState(req.spo2 ?? '');

  async function send(e) {
    e.preventDefault();
    try {
      await api(`/requests/${req.id}/condition`, { method: 'PUT', body: { note, heartRate: hr === '' ? null : Number(hr), spo2: spo2 === '' ? null : Number(spo2) } });
      toast('Sent to hospital', 'The receiving team sees this update live.', 'success');
    } catch (err) { toast('Could not send', err.message, 'error'); }
  }

  return (
    <form className="card stack" onSubmit={send}>
      <h3>Patient condition</h3>
      <label className="field">Notes for the hospital<textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Conscious, oxygen started..." /></label>
      <div className="grid cols-2">
        <label className="field">Heart rate (bpm)<input type="number" min="0" max="300" value={hr} onChange={(e) => setHr(e.target.value)} /></label>
        <label className="field">SpO2 (%)<input type="number" min="0" max="100" value={spo2} onChange={(e) => setSpo2(e.target.value)} /></label>
      </div>
      <button className="btn primary">Send update to hospital</button>
    </form>
  );
}

/** Streams the device GPS to the server while a GPS-mode trip is moving. */
function useGpsStream(active) {
  const toast = useToast();
  const last = useRef(0);
  useEffect(() => {
    if (!active || !navigator.geolocation) return undefined;
    const id = navigator.geolocation.watchPosition(
      (p) => {
        const now = Date.now();
        if (now - last.current < 2500) return;
        last.current = now;
        api('/ambulance/location', { method: 'POST', body: { lat: p.coords.latitude, lng: p.coords.longitude, speedKmph: p.coords.speed ? p.coords.speed * 3.6 : null } }).catch(() => {});
      },
      () => toast('GPS unavailable', 'Allow location access in the browser to stream your position.', 'warning'),
      { enableHighAccuracy: true, maximumAge: 1000 });
    return () => navigator.geolocation.clearWatch(id);
  }, [active, toast]);
}

export default function AmbulanceHome() {
  const toast = useToast();
  const network = useNetwork();
  const signalStates = useSignalStates();
  const [me, setMe] = useState(null);
  const [req, setReq] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api('/ambulance/me').then((r) => { setMe(r.ambulance); setReq(r.request); }).catch((e) => toast('Ambulance', e.message, 'error')), [toast]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(load, 8000); return () => clearInterval(t); }, [load]);

  useSub(me ? `/topic/ambulance/${me.id}` : null, (e) => {
    if (e.type === 'REQUEST') setReq(isActive(e.payload.status) ? e.payload : null);
    else load();
  });

  useGpsStream(!!req && req.gpsMode === 'GPS' && ['EN_ROUTE_TO_PATIENT', 'EN_ROUTE_TO_HOSPITAL'].includes(req.status));

  async function act(path, body, label) {
    setBusy(true);
    try { setReq(await api(`/requests/${req.id}/${path}`, { method: 'POST', body })); }
    catch (e) { toast(`${label} failed`, e.message, 'error'); }
    finally { setBusy(false); }
  }

  if (!me) return <div className="empty">Loading...</div>;

  return (
    <div className="stack">
      <div className="card row between">
        <div><h1 style={{ margin: 0 }}>{me.vehicleNo}</h1><div className="muted small">Paramedic: {me.driverName}</div></div>
        <span className={`badge ${req ? 'info' : 'ok'}`}>{req ? 'On a trip' : 'Available for dispatch'}</span>
      </div>

      {!req ? (
        <div className="card empty"><h3>Standing by</h3>You will be notified the moment the control room assigns a patient to this ambulance.</div>
      ) : (
        <>
          <div className="card stack">
            <div className="row between">
              <div className="row"><h2 style={{ margin: 0 }}>Trip #{req.id}</h2><SevBadge severity={req.severity} /><StatusBadge status={req.status} /></div>
              <div className="row">
                {req.status === 'DISPATCHED' ? (<>
                  <button className="btn good" disabled={busy} onClick={() => act('start', { mode: 'SIMULATED' }, 'Start')}>Accept and start (simulated GPS)</button>
                  <button className="btn" disabled={busy} onClick={() => act('start', { mode: 'GPS' }, 'Start')}>Accept using this device's GPS</button>
                </>) : null}
                {req.status === 'AT_PATIENT' ? <button className="btn good" disabled={busy} onClick={() => act('pickup', undefined, 'Pickup')}>Patient on board, go to hospital</button> : null}
                {req.gpsMode === 'GPS' && ['EN_ROUTE_TO_PATIENT', 'EN_ROUTE_TO_HOSPITAL'].includes(req.status)
                  ? <button className="btn" disabled={busy} onClick={() => act('arrived', undefined, 'Arrive')}>Mark arrived</button> : null}
              </div>
            </div>
            <Stepper status={req.status} />
            <dl className="kv">
              <dt>Patient</dt><dd>{req.patientName} {req.patientPhone ? `(${req.patientPhone})` : ''}</dd>
              <dt>Pickup</dt><dd>{req.address || `${req.lat.toFixed(4)}, ${req.lng.toFixed(4)}`}</dd>
              <dt>Condition</dt><dd>{req.description || '—'}</dd>
              <dt>Destination</dt><dd>{req.hospital ? req.hospital.name : '—'}</dd>
              <dt>GPS source</dt><dd>{req.gpsMode === 'GPS' ? 'This device' : 'Simulated'}</dd>
            </dl>
            {req.status === 'ARRIVED_AT_HOSPITAL' ? <div className="info-box">Waiting for the hospital to confirm the handover.</div> : null}
            {req.waitingAt ? <div className="alert-box">Held at a red light: {req.waitingAt}. Waiting for traffic police clearance.</div> : null}
          </div>
          <EtaBoard req={req} />
          <TripMap req={req} network={network} signalStates={signalStates} height="tall" />
          <div className="grid cols-2" style={{ alignItems: 'start' }}>
            <div className="card"><h3>Junctions ahead</h3><StopsTable stops={req.stops} /></div>
            <ConditionForm key={req.id} req={req} />
          </div>
        </>
      )}
    </div>
  );
}
