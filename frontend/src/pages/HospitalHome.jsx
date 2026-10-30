import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useNetwork, useSignalStates, useSub } from '../hooks.js';
import { useToast } from '../toast.jsx';
import TripMap from '../components/TripMap.jsx';
import { Condition, EtaBoard, RankingTable, SevBadge, StatusBadge, StopsTable } from '../components/ui.jsx';
import { dur } from '../fmt.js';

const NEEDS_DECISION = ['PENDING_HOSPITAL'];
const LIVE = ['WAITING_AMBULANCE', 'DISPATCHED', 'EN_ROUTE_TO_PATIENT', 'AT_PATIENT', 'EN_ROUTE_TO_HOSPITAL', 'ARRIVED_AT_HOSPITAL'];

function BedEditor({ hospital, onSaved }) {
  const toast = useToast();
  const [beds, setBeds] = useState(hospital.bedsAvailable);
  const [spec, setSpec] = useState(hospital.specialities);
  useEffect(() => { setBeds(hospital.bedsAvailable); setSpec(hospital.specialities); }, [hospital.bedsAvailable, hospital.specialities]);

  async function save() {
    try {
      const h = await api('/hospital/beds', { method: 'PUT', body: { bedsAvailable: Number(beds), specialities: spec } });
      onSaved(h);
      toast('Saved', 'Live bed count updated. Ranking uses it immediately.', 'success');
    } catch (e) { toast('Could not save', e.message, 'error'); }
  }

  return (
    <div className="card">
      <div className="row between"><h3 style={{ margin: 0 }}>{hospital.name}</h3><span className="badge info">{hospital.bedsAvailable}/{hospital.totalBeds} beds free</span></div>
      <div className="grid cols-3" style={{ marginTop: 10, alignItems: 'end' }}>
        <label className="field">Free beds (max {hospital.totalBeds})<input type="number" min="0" max={hospital.totalBeds} value={beds} onChange={(e) => setBeds(e.target.value)} /></label>
        <label className="field">Specialities (comma separated)<input value={spec} onChange={(e) => setSpec(e.target.value)} /></label>
        <button className="btn primary" onClick={save}>Update live capacity</button>
      </div>
    </div>
  );
}

function Detail({ req, network, signalStates, onChange }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  async function act(path, label) {
    setBusy(true);
    try { onChange(await api(`/requests/${req.id}/${path}`, { method: 'POST', body: path === 'reject' ? { reason: 'Hospital unable to accept' } : undefined })); }
    catch (e) { toast(`${label} failed`, e.message, 'error'); }
    finally { setBusy(false); }
  }

  return (
    <div className="stack">
      <div className="card stack">
        <div className="row between">
          <div className="row"><h2 style={{ margin: 0 }}>#{req.id} {req.patientName}</h2><SevBadge severity={req.severity} /><StatusBadge status={req.status} /></div>
          <div className="row">
            {req.status === 'PENDING_HOSPITAL' ? (<>
              <button className="btn good" disabled={busy} onClick={() => act('accept', 'Accept')}>Accept patient</button>
              <button className="btn danger" disabled={busy} onClick={() => act('reject', 'Reject')}>Reject</button>
            </>) : null}
            {req.status === 'ARRIVED_AT_HOSPITAL' ? <button className="btn good" disabled={busy} onClick={() => act('handover', 'Handover')}>Confirm handover</button> : null}
          </div>
        </div>
        <dl className="kv">
          <dt>Needs</dt><dd>{req.requiredSpeciality} care</dd>
          <dt>Description</dt><dd>{req.description || '—'}</dd>
          <dt>Patient phone</dt><dd>{req.patientPhone || '—'}</dd>
          <dt>Ambulance</dt><dd>{req.ambulance ? `${req.ambulance.vehicleNo} (${req.ambulance.driverName || 'paramedic'})` : 'not assigned yet'}</dd>
        </dl>
        {req.status === 'PENDING_HOSPITAL' ? <div className="info-box">This request is waiting for your decision. Accepting reserves one bed.</div> : null}
      </div>
      {req.ambulance ? <EtaBoard req={req} /> : null}
      <div className="card"><h3>Patient condition (from paramedic)</h3><Condition req={req} /></div>
      <TripMap req={req} network={network} signalStates={signalStates} height="short" />
      {req.stops && req.stops.length ? <div className="card"><h3>Route and junction ETAs</h3><StopsTable stops={req.stops} /></div> : null}
      <details className="card"><summary><b>Hospital ranking for this request</b></summary><div style={{ marginTop: 10 }}><RankingTable ranking={req.ranking} chosenId={req.hospital && req.hospital.id} /></div></details>
    </div>
  );
}

export default function HospitalHome() {
  const toast = useToast();
  const network = useNetwork();
  const signalStates = useSignalStates();
  const [hospital, setHospital] = useState(null);
  const [reqs, setReqs] = useState([]);
  const [sel, setSel] = useState(null);

  const load = useCallback(() => api('/hospital/requests').then(setReqs).catch((e) => toast('Could not load requests', e.message, 'error')), [toast]);
  useEffect(() => { api('/hospital/me').then(setHospital).catch((e) => toast('Hospital', e.message, 'error')); load(); }, [load, toast]);
  useEffect(() => { const t = setInterval(load, 10000); return () => clearInterval(t); }, [load]);

  const upsert = useCallback((v) => setReqs((cur) => {
    const mine = hospital && v.hospital && v.hospital.id === hospital.id;
    const others = cur.filter((r) => r.id !== v.id);
    return mine ? [v, ...others].sort((a, b) => b.id - a.id) : others;
  }), [hospital]);

  useSub(hospital ? `/topic/hospital/${hospital.id}` : null, (e) => { if (e.type === 'REQUEST') upsert(e.payload); });

  if (!hospital) return <div className="empty">Loading...</div>;
  const pending = reqs.filter((r) => NEEDS_DECISION.includes(r.status));
  const live = reqs.filter((r) => LIVE.includes(r.status));
  const history = reqs.filter((r) => !NEEDS_DECISION.includes(r.status) && !LIVE.includes(r.status));
  const selected = reqs.find((r) => r.id === sel) || pending[0] || live[0] || null;

  const Item = ({ r }) => (
    <div className={`list-item ${selected && selected.id === r.id ? 'sel' : ''}`} onClick={() => setSel(r.id)}>
      <div className="row between"><b>#{r.id} {r.patientName}</b><SevBadge severity={r.severity} /></div>
      <div className="row between small muted"><span>{r.requiredSpeciality}</span><StatusBadge status={r.status} /></div>
      {r.ambulance && LIVE.includes(r.status) ? <div className="small">ETA to us: <b className="mono">{dur(r.etaHospitalSec)}</b></div> : null}
    </div>
  );

  return (
    <div className="stack">
      <BedEditor hospital={hospital} onSaved={setHospital} />
      <div className="grid split">
        <div className="stack">
          <div className="card">
            <h3>Needs your decision {pending.length ? <span className="badge warn">{pending.length}</span> : null}</h3>
            {pending.length ? pending.map((r) => <Item key={r.id} r={r} />) : <div className="muted small">Nothing waiting.</div>}
          </div>
          <div className="card">
            <h3>Incoming and active</h3>
            {live.length ? live.map((r) => <Item key={r.id} r={r} />) : <div className="muted small">No ambulances on the way.</div>}
          </div>
          <div className="card">
            <h3>History</h3>
            {history.length ? history.slice(0, 8).map((r) => <Item key={r.id} r={r} />) : <div className="muted small">No past requests.</div>}
          </div>
        </div>
        {selected ? <Detail key={selected.id} req={selected} network={network} signalStates={signalStates} onChange={upsert} /> : <div className="card empty">Select a request to see details.</div>}
      </div>
    </div>
  );
}
