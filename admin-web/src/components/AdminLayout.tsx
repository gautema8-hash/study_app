import { Layout, Menu, Typography } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'

const { Sider, Header, Content } = Layout
const { Title } = Typography

// 菜单与后台 PRD 页面清单（W1~W10）一一对应
export const MENUS = [
  { key: '/workbench', label: '工作台' },
  { key: '/user', label: '用户管理' },
  { key: '/order', label: '订单与退款' },
  { key: '/reconcile', label: '对账中心' },
  { key: '/bank', label: '题库运营' },
  { key: '/review', label: '审核工单' },
  { key: '/ticket', label: '客服工单' },
  { key: '/dashboard', label: '数据看板' },
  { key: '/setting', label: '系统配置' },
  { key: '/iam', label: '权限管理' },
]

export default function AdminLayout() {
  const nav = useNavigate()
  const { pathname } = useLocation()
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark">
        <div style={{ color: '#fff', padding: 16, fontWeight: 700 }}>学习解锁 · 管理后台</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[pathname]}
          items={MENUS}
          onClick={(e) => nav(e.key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', borderBottom: '1px solid #eee' }}>
          <Title level={4} style={{ lineHeight: '56px', margin: 0 }}>
            {MENUS.find((m) => m.key === pathname)?.label ?? ''}
          </Title>
        </Header>
        <Content style={{ margin: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
