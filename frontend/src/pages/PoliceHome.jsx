import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { useFleet, useNetwork, useSignalStates, useSub } from '../hooks.js';
import { useToast } from '../toast.jsx';
import MapView from '../components/MapView.jsx';
import { EtaBoard, SevBadge, Stat, StatusBadge, StopsTable } from '../components/ui.jsx';
import { isActive } from '../fmt.js';

const ON_ROAD = ['DISPATCHED', 'EN_ROUTE_TO_PATIENT', 'AT_PATIENT', 'EN_ROUTE_TO_HOSPITAL'];
const COLORS = ['#2457d6', '#d62839', '#7a3fd1', '#0e8f7a'];

export default function PoliceHome() {
  const toast = useToast();
  const network = useNetwork();
  const signalStates = useSignalStates();
  const fleet = useFleet();
  const [trips, setTrips] = useState([]);
  const [sel, setSel] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api('/police/requests').then(setTrips).catch((e) => toast('Could not load trips', e.message, 'error')), [toast]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const t = setInterval(load, 15000); return () => clearInterval(t); }, [load]);

  useSub('/topic/police', (e) => {
    if (e.type === 'REQUEST') {
      const v = e.payload;
      setTrips((cur) => {
        const rest = cur.filter((t) => t.id !== v.id);
        return ON_ROAD.includes(v.status) ? [v, ...rest].sort((a, b) => b.id - a.id) : rest;
      });
    } else if (e.type === 'CLEARANCES') {
      load();
    }
  });

  const selected = trips.find((t) => t.id === sel) || trips[0] || null;

  const pendingCount = useMemo(() => trips.reduce((n, t) => n + (t.stops || []).filter((s) => !s.passed && s.clearanceStatus === 'PENDING').length, 0), [trips]);
  const forcedCount = Object.values(signalStates).filter((s) => s.forced).length;

  async function post(path, label) {
    setBusy(true);
    try { await api(path, { method: 'POST' }); await load(); }
    catch (e) { toast(`${label} failed`, e.message, 'error'); }
    finally { setBusy(false); }
  }

  const routes = trips.map((t, i) => ({ points: t.polyline || [], color: COLORS[i % COLORS.length], weight: selected && selected.id === t.id ? 7 : 4, opacity: selected && selected.id === t.id ? 0.95 : 0.5 }));
  const markers = fleet.map((a) => ({ key: `f${a.id}`, kind: 'amb', pos: [a.lat, a.lng], label: `${a.vehicleNo} (${a.status})` }));
  const fit = selected && selected.polyline && selected.polyline.length > 1 ? selected.polyline : null;

  return (
    <div className="stack">
      <div className="grid cols-4">
        <Stat label="Ambulances on road" value={trips.length} />
        <Stat label="Junctions awaiting you" value={pendingCount} tone={pendingCount ? 'hot' : ''} sub="clearance not yet decided" />
        <Stat label="Signals held green" value={forcedCount} tone="good" />
        <Stat label="Fleet online" value={fleet.length} />
      </div>

      <div className="grid split">
        <div className="stack">
          <div className="card">
            <h3>Emergency trips</h3>
            {trips.length ? trips.map((t) => (
              <div key={t.id} className={`list-item ${selected && selected.id === t.id ? 'sel' : ''}`} onClick={() => setSel(t.id)}>
                <div className="row between"><b>#{t.id} {t.ambulance ? t.ambulance.vehicleNo : ''}</b><SevBadge severity={t.severity} /></div>
                <div className="row between small muted"><span>{t.patientName} → {t.hospital && t.hospital.name}</span><StatusBadge status={t.status} /></div>
              </div>
            )) : <div className="muted small">No ambulance is on the road right now. Corridor requests appear here when a hospital accepts a patient.</div>}
          </div>
          <MapView network={network} signalStates={signalStates} routes={routes} markers={markers} height="tall"
            fit={fit} fitKey={selected ? `${selected.id}:${selected.leg}` : 'none'} />
          <div className="tiny muted">Signal colours: red / green = natural cycle, bold green = held green for an ambulance.</div>
        </div>

        {selected ? (
          <div className="stack">
            <div className="card stack">
              <div className="row between">
                <div className="row"><h2 style={{ margin: 0 }}>Trip #{selected.id}</h2><SevBadge severity={selected.severity} /><StatusBadge status={selected.status} /></div>
                <div className="row">
                  <button className="btn good" disabled={busy} onClick={() => post(`/police/requests/${selected.id}/clearances/approve-all`, 'Approve all')}>Approve whole corridor</button>
                  <button className="btn" disabled={busy} onClick={() => post(`/police/requests/${selected.id}/clearances/reject-all`, 'Reject all')}>Reject all</button>
                </div>
              </div>
              <div className="small muted">
                Approved junctions are turned green automatically when the ambulance is about to arrive. Rejected or undecided ones keep their normal cycle and the ambulance may have to wait.
              </div>
              {selected.waitingAt ? <div className="alert-box">Ambulance is stopped at a red light: {selected.waitingAt}</div> : null}
              <EtaBoard req={selected} />
            </div>
            <div className="card">
              <h3>Junctions on this leg ({selected.leg === 'TO_PATIENT' ? 'to patient' : 'to hospital'})</h3>
              <StopsTable stops={selected.stops}
                onApprove={(id) => post(`/police/clearances/${id}/approve`, 'Approve')}
                onReject={(id) => post(`/police/clearances/${id}/reject`, 'Reject')} />
            </div>
          </div>
        ) : <div className="card empty">Select a trip to review its green corridor.</div>}
      </div>
    </div>
  );
}
