import { CLEARANCE_LABEL, CLEARANCE_TONE, STATUS_LABEL, STATUS_TONE, dur, dist } from '../fmt.js';

export const StatusBadge = ({ status }) => <span className={`badge ${STATUS_TONE[status] || 'muted'}`}>{STATUS_LABEL[status] || status}</span>;
export const SevBadge = ({ severity }) => <span className={`badge sev-${severity}`}>{severity}</span>;
export const ClearanceBadge = ({ status }) => status
  ? <span className={`badge ${CLEARANCE_TONE[status] || 'muted'}`}>{CLEARANCE_LABEL[status] || status}</span>
  : <span className="muted">—</span>;

export function Stat({ label, value, sub, tone }) {
  return (
    <div className={`card stat ${tone || ''}`}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {sub ? <div className="sub">{sub}</div> : null}
    </div>
  );
}

const STEPS = ['Request sent', 'Hospital ready', 'Ambulance assigned', 'On the way', 'With patient', 'To hospital', 'Arrived'];
const STEP_OF = {
  PENDING_HOSPITAL: 0, WAITING_AMBULANCE: 1, DISPATCHED: 2, EN_ROUTE_TO_PATIENT: 3, AT_PATIENT: 4,
  EN_ROUTE_TO_HOSPITAL: 5, ARRIVED_AT_HOSPITAL: 6, COMPLETED: 7,
};

export function Stepper({ status }) {
  const at = STEP_OF[status] ?? -1;
  if (at < 0) return null;
  return (
    <div className="stepper">
      {STEPS.map((s, i) => (
        <div key={s} className={`step ${i < at ? 'done' : i === at ? 'now' : ''}`}>
          <div className="bar" />{s}
        </div>
      ))}
    </div>
  );
}

/** Live ETA / savings cards for one request. */
export function EtaBoard({ req }) {
  const active = ['DISPATCHED', 'EN_ROUTE_TO_PATIENT', 'AT_PATIENT', 'EN_ROUTE_TO_HOSPITAL'].includes(req.status);
  return (
    <div className="grid cols-4">
      <Stat label="ETA to patient" value={active ? dur(req.etaPatientSec) : '—'} />
      <Stat label="ETA to hospital" value={active || req.status === 'ARRIVED_AT_HOSPITAL' ? dur(req.etaHospitalSec) : '—'}
        sub={active && req.etaHospitalNoClearanceSec != null && req.etaHospitalNoClearanceSec - req.etaHospitalSec > 3
          ? `${dur(req.etaHospitalNoClearanceSec)} without corridor` : null} />
      <Stat label="Delay saved" value={dur(req.delaySavedSec)} tone="good" sub="by green corridor" />
      <Stat label="Waited at red" value={dur(req.waitedSec)} tone={req.waitedSec > 0 ? 'hot' : ''} sub={req.waitingAt ? `stopped at ${req.waitingAt}` : null} />
    </div>
  );
}

/** Junction table with ETA, predicted wait and clearance state. */
export function StopsTable({ stops, onApprove, onReject }) {
  if (!stops || stops.length === 0) return <div className="empty">No route planned yet.</div>;
  return (
    <div className="scroll-x">
      <table className="t">
        <thead>
          <tr><th>Junction</th><th>Signal</th><th>ETA</th><th>Red wait if no corridor</th><th>Corridor</th>{onApprove ? <th /> : null}</tr>
        </thead>
        <tbody>
          {stops.map((s) => (
            <tr key={s.signalId}>
              <td><b>{s.name}</b><div className="tiny muted">{s.passed ? 'passed' : dist(s.distanceAhead)}</div></td>
              <td><span className={`sigdot ${s.signalForced ? 'f' : s.signalGreen ? 'g' : 'r'}`} />{s.signalForced ? 'forced green' : s.signalGreen ? 'green' : 'red'}</td>
              <td className="mono">{s.passed ? '—' : dur(s.etaSec)}</td>
              <td className="mono">{s.passed ? (s.delaySavedSec > 0 ? `saved ${dur(s.delaySavedSec)}` : s.waitedSec > 0 ? `waited ${dur(s.waitedSec)}` : 'none') : dur(s.predictedWaitSec)}</td>
              <td><ClearanceBadge status={s.passed && s.clearanceStatus === 'PENDING' ? 'PASSED_UNDECIDED' : s.clearanceStatus} /></td>
              {onApprove ? (
                <td className="right">
                  {!s.passed && s.clearanceId && s.clearanceStatus !== 'CANCELLED' ? (
                    <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                      {['PENDING', 'REJECTED', 'EXPIRED'].includes(s.clearanceStatus)
                        ? <button className="btn sm good" onClick={() => onApprove(s.clearanceId)}>Approve</button> : null}
                      {s.clearanceStatus !== 'REJECTED'
                        ? <button className="btn sm" onClick={() => onReject(s.clearanceId)}>Reject</button> : null}
                    </div>
                  ) : null}
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** How the hospital ranking algorithm scored each candidate. */
export function RankingTable({ ranking, chosenId }) {
  if (!ranking || ranking.length === 0) return null;
  return (
    <div className="scroll-x">
      <table className="t">
        <thead><tr><th>Hospital</th><th>Travel</th><th>Speciality</th><th>Beds</th><th>Score</th></tr></thead>
        <tbody>
          {ranking.map((h) => (
            <tr key={h.hospitalId} className={h.hospitalId === chosenId ? 'sel' : ''}>
              <td><b>{h.name}</b></td>
              <td className="mono">{h.travelMinutes.toFixed(1)} min</td>
              <td>{h.specialityMatch ? <span className="badge ok">match</span> : <span className="badge muted">no match</span>}</td>
              <td className="mono">{h.bedsAvailable}/{h.totalBeds}</td>
              <td className="mono"><b>{h.score.toFixed(1)}</b></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="tiny muted" style={{ marginTop: 6 }}>Lower score is better: travel time, speciality match and free beds are combined.</div>
    </div>
  );
}

export function Condition({ req }) {
  if (!req.conditionNote && req.heartRate == null && req.spo2 == null) return <div className="muted small">No patient update from the paramedic yet.</div>;
  return (
    <dl className="kv">
      {req.conditionNote ? (<><dt>Condition</dt><dd>{req.conditionNote}</dd></>) : null}
      {req.heartRate != null ? (<><dt>Heart rate</dt><dd>{req.heartRate} bpm</dd></>) : null}
      {req.spo2 != null ? (<><dt>SpO2</dt><dd>{req.spo2}%</dd></>) : null}
    </dl>
  );
}
