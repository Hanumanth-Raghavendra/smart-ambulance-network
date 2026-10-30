export const dur = (s) => {
  if (s === null || s === undefined || Number.isNaN(s)) return '—';
  const t = Math.max(0, Math.round(s));
  const m = Math.floor(t / 60);
  const r = t % 60;
  return m > 0 ? `${m}m ${String(r).padStart(2, '0')}s` : `${r}s`;
};

export const dist = (m) => {
  if (m === null || m === undefined) return '—';
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
};

export const STATUS_LABEL = {
  PENDING_HOSPITAL: 'Waiting for hospital',
  WAITING_AMBULANCE: 'Finding an ambulance',
  DISPATCHED: 'Ambulance assigned',
  EN_ROUTE_TO_PATIENT: 'Ambulance on the way',
  AT_PATIENT: 'Ambulance with patient',
  EN_ROUTE_TO_HOSPITAL: 'Heading to hospital',
  ARRIVED_AT_HOSPITAL: 'Arrived at hospital',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  NO_HOSPITAL: 'No hospital available',
};

export const STATUS_TONE = {
  PENDING_HOSPITAL: 'warn', WAITING_AMBULANCE: 'warn', DISPATCHED: 'info', EN_ROUTE_TO_PATIENT: 'info',
  AT_PATIENT: 'info', EN_ROUTE_TO_HOSPITAL: 'info', ARRIVED_AT_HOSPITAL: 'ok', COMPLETED: 'ok',
  CANCELLED: 'muted', NO_HOSPITAL: 'bad',
};

export const ACTIVE = ['PENDING_HOSPITAL', 'WAITING_AMBULANCE', 'DISPATCHED', 'EN_ROUTE_TO_PATIENT', 'AT_PATIENT',
  'EN_ROUTE_TO_HOSPITAL', 'ARRIVED_AT_HOSPITAL'];
export const isActive = (s) => ACTIVE.includes(s);

export const CLEARANCE_LABEL = {
  PENDING: 'Awaiting police', APPROVED: 'Approved', GREEN_SENT: 'Green sent', PASSED: 'Passed',
  REJECTED: 'Rejected', CANCELLED: 'Cancelled', EXPIRED: 'Expired', PASSED_UNDECIDED: 'Passed',
};
export const CLEARANCE_TONE = {
  PENDING: 'warn', APPROVED: 'info', GREEN_SENT: 'ok', PASSED: 'muted', REJECTED: 'bad', CANCELLED: 'muted',
  EXPIRED: 'bad', PASSED_UNDECIDED: 'muted',
};

export const SPECIALITIES = ['GENERAL', 'CARDIAC', 'TRAUMA', 'NEURO', 'PEDIATRIC', 'MATERNITY'];

export const ROLE_HOME = {
  PATIENT: '/patient', HOSPITAL: '/hospital', AMBULANCE: '/ambulance', POLICE: '/police', DRIVER: '/driver', ADMIN: '/admin',
};
export const ROLE_LABEL = {
  PATIENT: 'Patient', HOSPITAL: 'Hospital', AMBULANCE: 'Ambulance', POLICE: 'Traffic police', DRIVER: 'Road user', ADMIN: 'Admin',
};

// Centre of the demo city (Hyderabad)
export const CITY_CENTER = [17.4125, 78.4700];
