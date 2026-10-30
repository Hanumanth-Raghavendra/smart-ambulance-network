import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { subscribe } from './ws.js';
import { useAuth } from './auth.jsx';

const Ctx = createContext({ toast: () => {} });
export const useToast = () => useContext(Ctx).toast;

let seq = 1;

/** Toasts for server notifications (/user/queue/notifications) and for local feedback. */
export function ToastProvider({ children }) {
  const { user } = useAuth();
  const [items, setItems] = useState([]);

  const toast = useCallback((title, body, level = 'info') => {
    const id = seq++;
    setItems((x) => [...x.slice(-3), { id, title, body, level }]);
    setTimeout(() => setItems((x) => x.filter((t) => t.id !== id)), 7000);
  }, []);

  useEffect(() => {
    if (!user) return undefined;
    return subscribe('/user/queue/notifications', (n) => toast(n.title, n.body, n.level));
  }, [user, toast]);

  const value = useMemo(() => ({ toast }), [toast]);
  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="toasts" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.level}`}>
            <b>{t.title}</b>
            {t.body ? <span>{t.body}</span> : null}
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}
