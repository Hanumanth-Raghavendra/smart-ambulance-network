import { Client } from '@stomp/stompjs';

// One shared STOMP connection. Subscriptions survive reconnects (they are re-bound on every connect).
let client = null;
let connected = false;
const statusListeners = new Set();
const subs = new Map();
let nextId = 1;

const notify = () => statusListeners.forEach((fn) => fn(connected));

function bind(s) {
  if (!connected || !client) return;
  s.stomp = client.subscribe(s.dest, (m) => {
    try { s.handler(JSON.parse(m.body)); } catch (e) { console.error('Bad WebSocket message', e); }
  });
}

export function connectWs(token) {
  if (client) return;
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  client = new Client({
    brokerURL: `${scheme}://${window.location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => { connected = true; notify(); subs.forEach(bind); },
    onWebSocketClose: () => { connected = false; notify(); },
    onStompError: (f) => console.warn('STOMP error', f.headers && f.headers.message),
  });
  client.activate();
}

export function disconnectWs() {
  if (client) client.deactivate();
  client = null;
  connected = false;
  subs.forEach((s) => { s.stomp = null; });
  notify();
}

export function subscribe(dest, handler) {
  const id = nextId++;
  const s = { dest, handler, stomp: null };
  subs.set(id, s);
  bind(s);
  return () => {
    try { s.stomp && s.stomp.unsubscribe(); } catch { /* connection already closed */ }
    subs.delete(id);
  };
}

export function onWsStatus(fn) {
  statusListeners.add(fn);
  fn(connected);
  return () => statusListeners.delete(fn);
}
