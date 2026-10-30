import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import { ToastProvider } from './toast.jsx';
import Layout from './components/Layout.jsx';
import { ROLE_HOME } from './fmt.js';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import PatientHome from './pages/PatientHome.jsx';
import HospitalHome from './pages/HospitalHome.jsx';
import AmbulanceHome from './pages/AmbulanceHome.jsx';
import PoliceHome from './pages/PoliceHome.jsx';
import DriverHome from './pages/DriverHome.jsx';
import AdminHome from './pages/AdminHome.jsx';

function Guard({ role, children }) {
  const { user, loading } = useAuth();
  if (loading) return <div className="empty">Loading...</div>;
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== role) return <Navigate to={ROLE_HOME[user.role]} replace />;
  return children;
}

function Root() {
  const { user, loading } = useAuth();
  if (loading) return <div className="empty">Loading...</div>;
  return <Navigate to={user ? ROLE_HOME[user.role] : '/login'} replace />;
}

export default function App() {
  return (
    <ToastProvider>
      <Layout>
        <Routes>
          <Route path="/" element={<Root />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/patient" element={<Guard role="PATIENT"><PatientHome /></Guard>} />
          <Route path="/hospital" element={<Guard role="HOSPITAL"><HospitalHome /></Guard>} />
          <Route path="/ambulance" element={<Guard role="AMBULANCE"><AmbulanceHome /></Guard>} />
          <Route path="/police" element={<Guard role="POLICE"><PoliceHome /></Guard>} />
          <Route path="/driver" element={<Guard role="DRIVER"><DriverHome /></Guard>} />
          <Route path="/admin" element={<Guard role="ADMIN"><AdminHome /></Guard>} />
          <Route path="*" element={<Root />} />
        </Routes>
      </Layout>
    </ToastProvider>
  );
}
