import { useCallback, useEffect, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { useToast } from '../toast.jsx';
import { SevBadge, Stat, StatusBadge } from '../components/ui.jsx';
import { dur } from '../fmt.js';

const TABS = ['Analytics', 'Trips', 'Users', 'Hospitals', 'Ambulances', 'Signals', 'Roads', 'Settings'];

function useList(path) {
  const toast = useToast();
  const [rows, setRows] = useState(null);
  const reload = useCallback(() => api(path).then(setRows).catch((e) => toast('Load failed', e.message, 'error')), [path, toast]);
  useEffect(() => { reload(); }, [reload]);
  return [rows, reload];
}

function Analytics() {
  const [a, reload] = useList('/admin/analytics');
  if (!a) return <div className="empty">Loading...</div>;
  const trips = a.trips.map((t) => ({ name: `#${t.requestId}`, 'With corridor': Math.round(t.actualSec), 'Without corridor': Math.round(t.withoutClearanceSec) }));
  const sig = a.signals.filter((s) => s.cleared > 0).sort((x, y) => y.savedSec - x.savedSec).slice(0, 10)
    .map((s) => ({ name: s.name, 'Time saved (s)': Math.round(s.savedSec), 'Time waited (s)': Math.round(s.waitedSec) }));
  return (
    <div className="stack">
      <div className="row between"><div className="muted small">Times are simulated seconds.</div><button className="btn sm" onClick={reload}>Refresh</button></div>
      <div className="grid cols-4">
        <Stat label="Total requests" value={a.totalRequests} sub={`${a.completed} completed, ${a.active} active, ${a.cancelled} cancelled`} />
        <Stat label="Avg response" value={dur(a.avgResponseSec)} sub="request to ambulance at patient" />
        <Stat label="Avg trip time" value={dur(a.avgTripSec)} sub="dispatch to hospital" />
        <Stat label="Time saved by corridors" value={dur(a.totalDelaySavedSec)} tone="good" sub={`${dur(a.avgDelaySavedPerTrip)} per trip`} />
        <Stat label="Corridor requests" value={a.clearanceRequests} sub={`${a.clearancesApproved} approved, ${a.clearancesRejected} rejected`} />
        <Stat label="Signals turned green" value={a.greenSent} tone="good" />
        <Stat label="Driver alerts sent" value={a.alertsSent} />
        <Stat label="Time lost at red lights" value={dur(a.totalWaitedSec)} tone={a.totalWaitedSec > 0 ? 'hot' : ''} />
      </div>
      <div className="card">
        <h3>Trip time per emergency</h3>
        {trips.length ? (
          <div style={{ height: 280 }}>
            <ResponsiveContainer>
              <BarChart data={trips}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="name" /><YAxis unit="s" /><Tooltip /><Legend />
                <Bar dataKey="With corridor" fill="#1f9d55" isAnimationActive={false} /><Bar dataKey="Without corridor" fill="#9aa7b8" isAnimationActive={false} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        ) : <div className="muted">No completed trips yet.</div>}
      </div>
      <div className="grid cols-2">
        <div className="card">
          <h3>Busiest corridor junctions</h3>
          {sig.length ? (
            <div style={{ height: 300 }}>
              <ResponsiveContainer>
                <BarChart data={sig} layout="vertical" margin={{ left: 40 }}><CartesianGrid strokeDasharray="3 3" /><XAxis type="number" /><YAxis type="category" dataKey="name" width={130} /><Tooltip /><Legend />
                  <Bar dataKey="Time saved (s)" fill="#1f9d55" isAnimationActive={false} /><Bar dataKey="Time waited (s)" fill="#e5484d" isAnimationActive={false} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          ) : <div className="muted">No junction data yet.</div>}
        </div>
        <div className="card">
          <h3>Patients accepted by hospital</h3>
          <table className="t"><thead><tr><th>Hospital</th><th>Accepted</th></tr></thead>
            <tbody>{a.hospitals.map((h) => <tr key={h.hospitalId}><td>{h.name}</td><td>{h.accepted}</td></tr>)}</tbody></table>
        </div>
      </div>
    </div>
  );
}

function Trips() {
  const [rows, reload] = useList('/admin/requests');
  if (!rows) return <div className="empty">Loading...</div>;
  return (
    <div className="card">
      <div className="row between"><h3 style={{ margin: 0 }}>Latest 100 requests</h3><button className="btn sm" onClick={reload}>Refresh</button></div>
      <div className="scroll-x"><table className="t">
        <thead><tr><th>#</th><th>Patient</th><th>Severity</th><th>Status</th><th>Hospital</th><th>Ambulance</th><th>Saved</th><th>Waited</th><th>Alerts</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.id}><td>{r.id}</td><td>{r.patientName}</td><td><SevBadge severity={r.severity} /></td><td><StatusBadge status={r.status} /></td>
            <td>{r.hospital ? r.hospital.name : '—'}</td><td>{r.ambulance ? r.ambulance.vehicleNo : '—'}</td>
            <td className="mono">{dur(r.delaySavedSec)}</td><td className="mono">{dur(r.waitedSec)}</td><td>{r.alertsSent}</td></tr>
        ))}</tbody></table></div>
    </div>
  );
}

function Users() {
  const toast = useToast();
  const [rows, reload] = useList('/admin/users');
  const [hospitals] = useList('/admin/hospitals');
  const [f, setF] = useState({ name: '', email: '', password: '', phone: '', role: 'POLICE', hospitalId: '' });

  async function create(e) {
    e.preventDefault();
    try {
      await api('/admin/users', { method: 'POST', body: { ...f, hospitalId: f.role === 'HOSPITAL' ? Number(f.hospitalId) : null } });
      setF({ name: '', email: '', password: '', phone: '', role: 'POLICE', hospitalId: '' });
      toast('User created', f.email, 'success');
      reload();
    } catch (err) { toast('Could not create user', err.message, 'error'); }
  }
  async function toggle(u) {
    try { await api(`/admin/users/${u.id}/active`, { method: 'PUT', body: { enabled: !u.active } }); reload(); }
    catch (err) { toast('Could not update', err.message, 'error'); }
  }
  if (!rows) return <div className="empty">Loading...</div>;
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  return (
    <div className="stack">
      <form className="card stack" onSubmit={create}>
        <h3 style={{ margin: 0 }}>Create account</h3>
        <div className="grid cols-3">
          <label className="field">Name<input required value={f.name} onChange={set('name')} /></label>
          <label className="field">Email<input required type="email" value={f.email} onChange={set('email')} /></label>
          <label className="field">Password (min 6)<input required minLength={6} type="password" value={f.password} onChange={set('password')} /></label>
          <label className="field">Phone<input value={f.phone} onChange={set('phone')} /></label>
          <label className="field">Role
            <select value={f.role} onChange={set('role')}>{['POLICE', 'HOSPITAL', 'AMBULANCE', 'ADMIN', 'PATIENT', 'DRIVER'].map((r) => <option key={r}>{r}</option>)}</select>
          </label>
          {f.role === 'HOSPITAL' ? (
            <label className="field">Hospital
              <select required value={f.hospitalId} onChange={set('hospitalId')}><option value="">Choose...</option>{(hospitals || []).map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}</select>
            </label>
          ) : null}
        </div>
        <div><button className="btn primary">Create</button></div>
      </form>
      <div className="card scroll-x"><table className="t">
        <thead><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th /></tr></thead>
        <tbody>{rows.map((u) => (
          <tr key={u.id}><td>{u.id}</td><td>{u.name}</td><td>{u.email}</td><td>{u.role}</td>
            <td><span className={`badge ${u.active ? 'ok' : 'muted'}`}>{u.active ? 'active' : 'disabled'}</span></td>
            <td className="right"><button className="btn sm" onClick={() => toggle(u)}>{u.active ? 'Disable' : 'Enable'}</button></td></tr>
        ))}</tbody></table></div>
    </div>
  );
}

function Hospitals() {
  const toast = useToast();
  const [rows, reload] = useList('/admin/hospitals');
  const blank = { name: '', lat: 17.41, lng: 78.47, totalBeds: 50, bedsAvailable: 30, specialities: 'GENERAL', contact: '', active: true };
  const [f, setF] = useState(blank);
  const [editing, setEditing] = useState(null);

  async function save(e) {
    e.preventDefault();
    const body = { ...f, lat: Number(f.lat), lng: Number(f.lng), totalBeds: Number(f.totalBeds), bedsAvailable: Number(f.bedsAvailable) };
    try {
      await api(editing ? `/admin/hospitals/${editing}` : '/admin/hospitals', { method: editing ? 'PUT' : 'POST', body });
      setF(blank); setEditing(null); reload();
      toast('Saved', body.name, 'success');
    } catch (err) { toast('Could not save', err.message, 'error'); }
  }
  if (!rows) return <div className="empty">Loading...</div>;
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  return (
    <div className="stack">
      <form className="card stack" onSubmit={save}>
        <h3 style={{ margin: 0 }}>{editing ? `Edit hospital #${editing}` : 'Add hospital'}</h3>
        <div className="grid cols-3">
          <label className="field">Name<input required value={f.name} onChange={set('name')} /></label>
          <label className="field">Latitude<input required type="number" step="any" value={f.lat} onChange={set('lat')} /></label>
          <label className="field">Longitude<input required type="number" step="any" value={f.lng} onChange={set('lng')} /></label>
          <label className="field">Total beds<input required type="number" min="0" value={f.totalBeds} onChange={set('totalBeds')} /></label>
          <label className="field">Beds available<input required type="number" min="0" value={f.bedsAvailable} onChange={set('bedsAvailable')} /></label>
          <label className="field">Specialities<input value={f.specialities} onChange={set('specialities')} /></label>
          <label className="field">Contact<input value={f.contact || ''} onChange={set('contact')} /></label>
          <label className="row" style={{ gap: 8, alignSelf: 'end' }}><input type="checkbox" checked={f.active} onChange={set('active')} /> Accepting patients</label>
        </div>
        <div className="row"><button className="btn primary">{editing ? 'Save changes' : 'Add hospital'}</button>
          {editing ? <button type="button" className="btn" onClick={() => { setEditing(null); setF(blank); }}>Cancel</button> : null}</div>
      </form>
      <div className="card scroll-x"><table className="t">
        <thead><tr><th>#</th><th>Name</th><th>Beds</th><th>Specialities</th><th>Active</th><th /></tr></thead>
        <tbody>{rows.map((h) => (
          <tr key={h.id}><td>{h.id}</td><td>{h.name}</td><td>{h.bedsAvailable}/{h.totalBeds}</td><td>{h.specialities}</td><td>{h.active ? 'yes' : 'no'}</td>
            <td className="right"><button className="btn sm" onClick={() => { setEditing(h.id); setF({ ...h }); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>Edit</button></td></tr>
        ))}</tbody></table></div>
    </div>
  );
}

function Ambulances() {
  const toast = useToast();
  const [rows, reload] = useList('/admin/ambulances');
  const [users] = useList('/admin/users');
  const blank = { vehicleNo: '', driverId: '', lat: 17.41, lng: 78.47 };
  const [f, setF] = useState(blank);
  const [editing, setEditing] = useState(null);

  async function save(e) {
    e.preventDefault();
    const body = { vehicleNo: f.vehicleNo, driverId: f.driverId === '' ? null : Number(f.driverId), lat: Number(f.lat), lng: Number(f.lng) };
    try {
      await api(editing ? `/admin/ambulances/${editing}` : '/admin/ambulances', { method: editing ? 'PUT' : 'POST', body });
      setF(blank); setEditing(null); reload();
      toast('Saved', body.vehicleNo, 'success');
    } catch (err) { toast('Could not save', err.message, 'error'); }
  }
  if (!rows) return <div className="empty">Loading...</div>;
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  const drivers = (users || []).filter((u) => u.role === 'AMBULANCE');
  return (
    <div className="stack">
      <form className="card stack" onSubmit={save}>
        <h3 style={{ margin: 0 }}>{editing ? `Edit ambulance #${editing}` : 'Add ambulance'}</h3>
        <div className="grid cols-4">
          <label className="field">Vehicle number<input required value={f.vehicleNo} onChange={set('vehicleNo')} /></label>
          <label className="field">Paramedic account
            <select value={f.driverId ?? ''} onChange={set('driverId')}><option value="">None</option>{drivers.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.email})</option>)}</select>
          </label>
          <label className="field">Base latitude<input required type="number" step="any" value={f.lat} onChange={set('lat')} /></label>
          <label className="field">Base longitude<input required type="number" step="any" value={f.lng} onChange={set('lng')} /></label>
        </div>
        <div className="row"><button className="btn primary">{editing ? 'Save changes' : 'Add ambulance'}</button>
          {editing ? <button type="button" className="btn" onClick={() => { setEditing(null); setF(blank); }}>Cancel</button> : null}</div>
      </form>
      <div className="card scroll-x"><table className="t">
        <thead><tr><th>#</th><th>Vehicle</th><th>Paramedic</th><th>Status</th><th>Trip</th><th /></tr></thead>
        <tbody>{rows.map((a) => (
          <tr key={a.id}><td>{a.id}</td><td>{a.vehicleNo}</td><td>{a.driverName || '—'}</td><td>{a.status}</td><td>{a.activeRequestId ? `#${a.activeRequestId}` : '—'}</td>
            <td className="right"><button className="btn sm" disabled={!!a.activeRequestId} onClick={() => { setEditing(a.id); setF({ vehicleNo: a.vehicleNo, driverId: a.driverId ?? '', lat: a.lat, lng: a.lng }); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>Edit</button></td></tr>
        ))}</tbody></table></div>
    </div>
  );
}

function Signals() {
  const toast = useToast();
  const [rows, reload] = useList('/admin/signals');
  const [draft, setDraft] = useState({});
  const blank = { name: '', lat: 17.41, lng: 78.47, zone: '', cycleSeconds: 90, greenSeconds: 40, offsetSeconds: 0 };
  const [f, setF] = useState(blank);

  async function update(s) {
    const d = { ...s, ...(draft[s.id] || {}) };
    try {
      await api(`/admin/signals/${s.id}`, { method: 'PUT', body: { name: d.name, lat: d.lat, lng: d.lng, zone: d.zone, cycleSeconds: Number(d.cycleSeconds), greenSeconds: Number(d.greenSeconds), offsetSeconds: Number(d.offsetSeconds) } });
      setDraft((cur) => { const n = { ...cur }; delete n[s.id]; return n; });
      toast('Signal updated', d.name, 'success'); reload();
    } catch (err) { toast('Could not update', err.message, 'error'); }
  }
  async function create(e) {
    e.preventDefault();
    try {
      await api('/admin/signals', { method: 'POST', body: { ...f, lat: Number(f.lat), lng: Number(f.lng), cycleSeconds: Number(f.cycleSeconds), greenSeconds: Number(f.greenSeconds), offsetSeconds: Number(f.offsetSeconds) } });
      setF(blank); toast('Signal added', f.name, 'success'); reload();
    } catch (err) { toast('Could not add', err.message, 'error'); }
  }
  if (!rows) return <div className="empty">Loading...</div>;
  const val = (s, k) => (draft[s.id] && draft[s.id][k] !== undefined ? draft[s.id][k] : s[k]);
  const edit = (s, k) => (e) => setDraft({ ...draft, [s.id]: { ...(draft[s.id] || {}), [k]: e.target.value } });
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  return (
    <div className="stack">
      <form className="card stack" onSubmit={create}>
        <h3 style={{ margin: 0 }}>Add junction</h3>
        <div className="grid cols-4">
          <label className="field">Name<input required value={f.name} onChange={set('name')} /></label>
          <label className="field">Latitude<input required type="number" step="any" value={f.lat} onChange={set('lat')} /></label>
          <label className="field">Longitude<input required type="number" step="any" value={f.lng} onChange={set('lng')} /></label>
          <label className="field">Zone<input value={f.zone} onChange={set('zone')} /></label>
          <label className="field">Cycle (s)<input type="number" min="20" value={f.cycleSeconds} onChange={set('cycleSeconds')} /></label>
          <label className="field">Green (s)<input type="number" min="5" value={f.greenSeconds} onChange={set('greenSeconds')} /></label>
          <label className="field">Offset (s)<input type="number" min="0" value={f.offsetSeconds} onChange={set('offsetSeconds')} /></label>
        </div>
        <div><button className="btn primary">Add junction</button> <span className="tiny muted">Connect it with a road on the Roads tab so routes can use it.</span></div>
      </form>
      <div className="card scroll-x"><table className="t">
        <thead><tr><th>#</th><th>Junction</th><th>Zone</th><th>Cycle (s)</th><th>Green (s)</th><th>Offset (s)</th><th /></tr></thead>
        <tbody>{rows.map((s) => (
          <tr key={s.id}><td>{s.id}</td><td>{s.name}</td><td>{s.zone || '—'}</td>
            {['cycleSeconds', 'greenSeconds', 'offsetSeconds'].map((k) => (
              <td key={k}><input style={{ width: 70 }} type="number" value={val(s, k)} onChange={edit(s, k)} /></td>
            ))}
            <td className="right">{draft[s.id] ? <button className="btn sm primary" onClick={() => update(s)}>Save</button> : null}</td></tr>
        ))}</tbody></table></div>
    </div>
  );
}

function Roads() {
  const toast = useToast();
  const [rows, reload] = useList('/admin/roads');
  const [signals] = useList('/admin/signals');
  const [draft, setDraft] = useState({});
  const [f, setF] = useState({ fromId: '', toId: '', congestion: 1.0 });

  async function update(r) {
    try {
      await api(`/admin/roads/${r.id}`, { method: 'PUT', body: { fromId: r.fromId, toId: r.toId, congestion: Number(draft[r.id]) } });
      setDraft((cur) => { const n = { ...cur }; delete n[r.id]; return n; });
      reload();
    } catch (err) { toast('Could not update', err.message, 'error'); }
  }
  async function create(e) {
    e.preventDefault();
    try {
      await api('/admin/roads', { method: 'POST', body: { fromId: Number(f.fromId), toId: Number(f.toId), congestion: Number(f.congestion) } });
      setF({ fromId: '', toId: '', congestion: 1.0 }); reload();
    } catch (err) { toast('Could not add road', err.message, 'error'); }
  }
  if (!rows) return <div className="empty">Loading...</div>;
  const opts = (signals || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>);
  return (
    <div className="stack">
      <form className="card stack" onSubmit={create}>
        <h3 style={{ margin: 0 }}>Add road</h3>
        <div className="grid cols-3">
          <label className="field">From<select required value={f.fromId} onChange={(e) => setF({ ...f, fromId: e.target.value })}><option value="">Choose...</option>{opts}</select></label>
          <label className="field">To<select required value={f.toId} onChange={(e) => setF({ ...f, toId: e.target.value })}><option value="">Choose...</option>{opts}</select></label>
          <label className="field">Congestion (1 = free flow)<input type="number" step="0.1" min="1" value={f.congestion} onChange={(e) => setF({ ...f, congestion: e.target.value })} /></label>
        </div>
        <div><button className="btn primary">Add road</button></div>
      </form>
      <div className="card scroll-x">
        <div className="small muted" style={{ marginBottom: 8 }}>Congestion multiplies travel time on a road. 1.0 is free flow, 1.5 means the ambulance travels at two thirds speed. Routing avoids congested roads.</div>
        <table className="t">
          <thead><tr><th>#</th><th>From</th><th>To</th><th>Length</th><th>Congestion</th><th /></tr></thead>
          <tbody>{rows.map((r) => (
            <tr key={r.id}><td>{r.id}</td><td>{r.fromName}</td><td>{r.toName}</td><td className="mono">{Math.round(r.lengthMeters)} m</td>
              <td><input style={{ width: 70 }} type="number" step="0.1" min="1" value={draft[r.id] !== undefined ? draft[r.id] : r.congestion} onChange={(e) => setDraft({ ...draft, [r.id]: e.target.value })} /></td>
              <td className="right">{draft[r.id] !== undefined ? <button className="btn sm primary" onClick={() => update(r)}>Save</button> : null}</td></tr>
          ))}</tbody></table>
      </div>
    </div>
  );
}

function Settings() {
  const toast = useToast();
  const [s, setS] = useState(null);
  useEffect(() => { api('/admin/settings').then(setS).catch((e) => toast('Load failed', e.message, 'error')); }, [toast]);
  async function toggle(v) {
    try { setS(await api('/admin/settings', { method: 'PUT', body: { autoApprove: v } })); }
    catch (e) { toast('Could not save', e.message, 'error'); }
  }
  if (!s) return <div className="empty">Loading...</div>;
  return (
    <div className="card stack" style={{ maxWidth: 640 }}>
      <h3 style={{ margin: 0 }}>Traffic police workflow</h3>
      <label className="row" style={{ gap: 10, alignItems: 'flex-start' }}>
        <input type="checkbox" checked={s.autoApprove} onChange={(e) => toggle(e.target.checked)} />
        <span><b>Auto-approve green corridors</b><br /><span className="muted small">When on, every junction on an ambulance route is approved the moment the route is planned, with no action from the traffic police console. Leave it off to keep a human in the loop (recommended).</span></span>
      </label>
    </div>
  );
}

export default function AdminHome() {
  const [tab, setTab] = useState('Analytics');
  const View = { Analytics, Trips, Users, Hospitals, Ambulances, Signals, Roads, Settings }[tab];
  return (
    <div>
      <div className="tabs" role="tablist">{TABS.map((t) => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>
      <View />
    </div>
  );
}
