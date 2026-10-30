import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken } from './api.js';
import { connectWs, disconnectWs } from './ws.js';
import { resetNetworkCache } from './hooks.js';

const Ctx = createContext(null);
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(!!getToken());

  useEffect(() => {
    const t = getToken();
    if (!t) return;
    api('/auth/me')
      .then((u) => { setUser(u); connectWs(t); })
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  const finish = useCallback((res) => {
    setToken(res.token);
    resetNetworkCache();
    setUser(res.user);
    connectWs(res.token);
    return res.user;
  }, []);

  const login = useCallback(async (email, password) => finish(await api('/auth/login', { method: 'POST', body: { email, password } })), [finish]);
  const register = useCallback(async (body) => finish(await api('/auth/register', { method: 'POST', body })), [finish]);
  const logout = useCallback(() => {
    disconnectWs();
    setToken(null);
    resetNetworkCache();
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, loading, login, register, logout }), [user, loading, login, register, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
