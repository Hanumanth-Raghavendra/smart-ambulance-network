import { useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import { onWsStatus, subscribe } from './ws.js';

/** Subscribe to a STOMP destination while the component is mounted. */
export function useSub(dest, handler) {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    if (!dest) return undefined;
    return subscribe(dest, (m) => ref.current(m));
  }, [dest]);
}

export function useWsConnected() {
  const [c, setC] = useState(false);
  useEffect(() => onWsStatus(setC), []);
  return c;
}

/** One request, loaded once and then kept live through /topic/request/{id}. */
export function useRequest(id) {
  const [req, setReq] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    setReq(null);
    setError(null);
    if (!id) return undefined;
    let alive = true;
    api(`/requests/${id}`).then((v) => alive && setReq(v)).catch((e) => alive && setError(e.message));
    return () => { alive = false; };
  }, [id]);
  useSub(id ? `/topic/request/${id}` : null, (v) => setReq(v));
  return { req, setReq, error };
}

let networkPromise = null;
/** Junctions, roads and hospitals are static enough to fetch once per session. */
export function useNetwork() {
  const [net, setNet] = useState(null);
  useEffect(() => {
    if (!networkPromise) networkPromise = api('/network').catch((e) => { networkPromise = null; throw e; });
    let alive = true;
    networkPromise.then((n) => alive && setNet(n)).catch(() => {});
    return () => { alive = false; };
  }, []);
  return net;
}
export const resetNetworkCache = () => { networkPromise = null; };

/** Live junction states (police, admin, hospital, ambulance only). Returns a map id -> state. */
export function useSignalStates() {
  const [m, setM] = useState({});
  useSub('/topic/signals', (list) => {
    const next = {};
    list.forEach((s) => { next[s.id] = s; });
    setM(next);
  });
  return m;
}

export function useFleet() {
  const [fleet, setFleet] = useState([]);
  useSub('/topic/fleet', setFleet);
  return fleet;
}
