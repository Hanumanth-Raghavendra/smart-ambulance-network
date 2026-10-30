import MapView from './MapView.jsx';

/** Map for a single emergency request: route legs, junction states, patient, hospital and ambulance. */
export default function TripMap({ req, network, signalStates, height, extraMarkers = [], showNetwork = true }) {
  if (!req) return <MapView network={network} height={height} showNetwork={showNetwork} />;

  const routes = [];
  if (req.polyline && req.polyline.length > 1) {
    routes.push({ points: req.polyline, color: '#2457d6', weight: 6 });
  }
  if (req.leg === 'TO_PATIENT' && req.hospitalLeg && req.hospitalLeg.length > 1) {
    routes.push({ points: req.hospitalLeg, color: '#6b7a90', weight: 4, dashed: true, opacity: 0.7 });
  }

  const stops = (req.stops || []).map((s) => ({
    id: s.signalId,
    pos: [s.lat, s.lng],
    forced: s.signalForced,
    color: s.passed ? '#9aa7b8' : s.signalForced ? '#1f9d55' : s.signalGreen ? '#2fbf71' : '#e5484d',
    label: `${s.name}${s.passed ? ' (passed)' : s.signalForced ? ' - green corridor' : s.signalGreen ? ' - green' : ' - red'}`,
  }));

  const markers = [{ key: 'pat', kind: 'pat', pos: [req.lat, req.lng], label: `Patient: ${req.patientName}` }];
  if (req.hospital) markers.push({ key: 'hos', kind: 'hos', pos: [req.hospital.lat, req.hospital.lng], label: req.hospital.name });
  if (req.ambulance) markers.push({ key: 'amb', kind: 'amb', pos: [req.ambulance.lat, req.ambulance.lng], label: req.ambulance.vehicleNo });
  markers.push(...extraMarkers);

  const fit = [[req.lat, req.lng]];
  if (req.hospital) fit.push([req.hospital.lat, req.hospital.lng]);
  if (req.ambulance) fit.push([req.ambulance.lat, req.ambulance.lng]);

  return (
    <MapView network={network} signalStates={signalStates} routes={routes} stops={stops} markers={markers}
      fit={fit} fitKey={`${req.id}:${req.leg}:${req.ambulance ? 1 : 0}:${req.hospital ? req.hospital.id : 0}`}
      height={height} showNetwork={showNetwork} />
  );
}
