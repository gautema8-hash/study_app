import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import AdminLayout from './components/AdminLayout'
import Login from './pages/login'

// W1~W10 页面骨架：占位组件，后续按《Web 管理后台 PRD》逐页实现
import Workbench from './pages/workbench'
import UserManage from './pages/user'
import Order from './pages/order'
import Reconcile from './pages/reconcile'
import Bank from './pages/bank'
import Review from './pages/review'
import Ticket from './pages/ticket'
import Dashboard from './pages/dashboard'
import Setting from './pages/setting'
import Iam from './pages/iam'

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
          <Route path="/review" element={<Review />} />
          <Route path="/ticket" element={<Ticket />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/setting" element={<Setting />} />
          <Route path="/iam" element={<Iam />} />
        </Route>
      </Routes>
    </HashRouter>
  )
}
