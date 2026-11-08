import { useEffect, useState } from 'react'
import { Layout, Menu, Typography, Button, Tag, Drawer, Grid } from 'antd'
import { MenuOutlined } from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { getToken, clearToken } from '../auth'

const { Sider, Header, Content } = Layout
const { Title } = Typography
const { useBreakpoint } = Grid

export const MENUS = [
  { key: '/workbench', label: '工作台' },
  { key: '/user', label: '用户管理' },
  { key: '/order', label: '订单与退款' },
  { key: '/reconcile', label: '对账中心' },
  { key: '/bank', label: '题库运营' },
  { key: '/store', label: '题库商店' },
  { key: '/review', label: '审核工单' },
  { key: '/ticket', label: '客服工单' },
  { key: '/dashboard', label: '数据看板' },
  { key: '/audit', label: '操作日志' },
  { key: '/setting', label: '系统配置' },
  { key: '/iam', label: '权限管理' },
]

const ROLE_NAMES: Record<string, string> = {
  ADMIN: '超级管理员', REVIEWER: '审核员', CS: '客服', FINANCE: '财务', VIEWER: '只读', OPS: '运营',
}

/** 从 JWT payload 解析当前角色（仅用于展示，鉴权仍由后端负责） */
function currentRole(): string {
  try {
    const t = getToken()
    if (!t) return ''
    const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return (payload.scope || '').replace('admin:', '')
  } catch { return '' }
}

export default function AdminLayout() {
  const nav = useNavigate()
  const { pathname } = useLocation()
  const screens = useBreakpoint()
  const isMobile = !screens.md
  const [drawerOpen, setDrawerOpen] = useState(false)
  const role = currentRole()

  useEffect(() => {
    if (!getToken()) nav('/login')
  }, [pathname, nav])

  const menu = (
    <Menu
      theme="dark" mode="inline" selectedKeys={[pathname]} items={MENUS}
      onClick={(e) => { nav(e.key); setDrawerOpen(false) }}
    />
  )

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {!isMobile && (
        <Sider theme="dark" width={200}>
          <div style={{ color: '#fff', padding: 16, fontWeight: 700, fontSize: 14 }}>学习解锁 · 管理后台</div>
          {menu}
        </Sider>
      )}
      {isMobile && (
        <Drawer placement="left" open={drawerOpen} onClose={() => setDrawerOpen(false)}
          width={200} styles={{ body: { padding: 0, background: '#001529' } }} closable={false}>
          <div style={{ color: '#fff', padding: 16, fontWeight: 700 }}>学习解锁 · 管理后台</div>
          {menu}
        </Drawer>
      )}
      <Layout>
        <Header style={{ background: '#fff', borderBottom: '1px solid #eee', display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: isMobile ? '0 12px' : '0 24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 }}>
            {isMobile && <Button type="text" icon={<MenuOutlined />} onClick={() => setDrawerOpen(true)} />}
            <Title level={4} style={{ lineHeight: '56px', margin: 0, fontSize: isMobile ? 15 : 20, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {MENUS.find((m) => m.key === pathname)?.label ?? ''}
            </Title>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
            {!isMobile && role && <Tag color="blue">{ROLE_NAMES[role] ?? role}</Tag>}
            <Button size={isMobile ? 'small' : 'middle'} onClick={() => { clearToken(); nav('/login') }}>退出登录</Button>
          </div>
        </Header>
        <Content style={{ margin: isMobile ? 8 : 16, minWidth: 0 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
