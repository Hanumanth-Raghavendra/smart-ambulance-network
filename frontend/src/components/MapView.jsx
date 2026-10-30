import { useEffect } from 'react';
import { MapContainer, TileLayer, Polyline, CircleMarker, Marker, Tooltip, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import { CITY_CENTER } from '../fmt.js';

const GLYPH = { amb: '🚑', pat: '📍', hos: '🏥', drv: '🚗' };
const icons = {};
const iconFor = (kind) => {
  if (!icons[kind]) {
    icons[kind] = L.divIcon({
      className: '',
      html: `<div class="pin ${kind}">${GLYPH[kind]}</div>`,
      iconSize: kind === 'drv' ? [28, 28] : [34, 34],
      iconAnchor: kind === 'drv' ? [14, 14] : [17, 17],
    });
  }
  return icons[kind];
};

function FitBounds({ points, fitKey }) {
  const map = useMap();
  useEffect(() => {
    if (points && points.length > 1) map.fitBounds(points, { padding: [40, 40], maxZoom: 15 });
    else if (points && points.length === 1) map.setView(points[0], 15);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey]);
  return null;
}

function Picker({ onPick }) {
  useMapEvents({ click: (e) => onPick && onPick(e.latlng.lat, e.latlng.lng) });
  return null;
}

/**
 * Generic Leaflet map. Draws the road network and junction signals (live colours when states are given),
 * route polylines, junction markers and pins for patient / hospital / ambulance / road users.
 */
export default function MapView({
  network, signalStates, routes = [], stops = [], markers = [], onPick, fit, fitKey, height = '', showNetwork = true,
  showHospitals = false, center = CITY_CENTER, zoom = 12,
}) {
  return (
    <div className={`mapbox ${height}`}>
      <MapContainer center={center} zoom={zoom} style={{ height: '100%', width: '100%' }} scrollWheelZoom>
        <TileLayer attribution="&copy; OpenStreetMap contributors" url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
        {fit ? <FitBounds points={fit} fitKey={fitKey} /> : null}
        {onPick ? <Picker onPick={onPick} /> : null}

        {showNetwork && network ? network.roads.map((r, i) => (
          <Polyline key={`r${i}`} positions={[r.from, r.to]}
            pathOptions={{ color: r.congestion > 1.35 ? '#e08a1e' : '#8794a7', weight: 3, opacity: 0.55 }} />
        )) : null}

        {routes.map((r, i) => (
          <Polyline key={`rt${i}`} positions={r.points}
            pathOptions={{ color: r.color || '#2457d6', weight: r.weight || 6, opacity: r.opacity || 0.85, dashArray: r.dashed ? '8 8' : undefined }} />
        ))}

        {showNetwork && network ? network.signals.map((s) => {
          const st = signalStates && signalStates[s.id];
          const color = !st ? '#8794a7' : st.forced ? '#1f9d55' : st.green ? '#2fbf71' : '#e5484d';
          return (
            <CircleMarker key={`s${s.id}`} center={[s.lat, s.lng]} radius={st && st.forced ? 9 : 6}
              pathOptions={{ color: '#fff', weight: 2, fillColor: color, fillOpacity: 1 }}>
              <Tooltip direction="top">{s.name}{st ? (st.forced ? ' - GREEN (corridor)' : st.green ? ' - green' : ' - red') : ''}</Tooltip>
            </CircleMarker>
          );
        }) : null}

        {stops.map((s) => (
          <CircleMarker key={`st${s.id}`} center={s.pos} radius={s.forced ? 10 : 8}
            pathOptions={{ color: '#fff', weight: 2, fillColor: s.color, fillOpacity: 1 }}>
            <Tooltip direction="top">{s.label}</Tooltip>
          </CircleMarker>
        ))}

        {showHospitals && network ? network.hospitals.map((h) => (
          <Marker key={`h${h.id}`} position={[h.lat, h.lng]} icon={iconFor('hos')}>
            <Tooltip direction="top">{h.name} ({h.bedsAvailable} beds)</Tooltip>
          </Marker>
        )) : null}

        {markers.map((m) => (
          <Marker key={m.key} position={m.pos} icon={iconFor(m.kind)} zIndexOffset={m.kind === 'amb' ? 1000 : 0}>
            {m.label ? <Tooltip direction="top">{m.label}</Tooltip> : null}
          </Marker>
        ))}
      </MapContainer>
    </div>
  );
}
