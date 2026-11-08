import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import AdminLayout from './components/AdminLayout'
import Login from './pages/login'
import Workbench from './pages/workbench'
import UserManage from './pages/user'
import Order from './pages/order'
import Reconcile from './pages/reconcile'
import Bank from './pages/bank'
import BankStore from './pages/store'
import Review from './pages/review'
import Ticket from './pages/ticket'
import Dashboard from './pages/dashboard'
import Setting from './pages/setting'
import Iam from './pages/iam'
import Audit from './pages/audit'

export default function App() {
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<AdminLayout />}>
          <Route index element={<Navigate to="/workbench" replace />} />
          <Route path="/workbench" element={<Workbench />} />
          <Route path="/user" element={<UserManage />} />
          <Route path="/order" element={<Order />} />
          <Route path="/reconcile" element={<Reconcile />} />
          <Route path="/bank" element={<Bank />} />
          <Route path="/store" element={<BankStore />} />
          <Route path="/review" element={<Review />} />
          <Route path="/ticket" element={<Ticket />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/setting" element={<Setting />} />
          <Route path="/iam" element={<Iam />} />
          <Route path="/audit" element={<Audit />} />
        </Route>
      </Routes>
    </HashRouter>
  )
}
