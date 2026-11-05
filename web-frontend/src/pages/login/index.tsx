import { useState } from 'react'
import { Button, Card, Form, Input, message } from 'antd'
import { setToken } from '../../auth'

// 后台登录（FR-W01）：账号密码 → admin scope JWT
export default function Login() {
  const [loading, setLoading] = useState(false)

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true)
    try {
      const r = await fetch('/api/ops/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v),
      })
      const j = await r.json()
      if (r.ok && j.code === 0) {
        setToken(j.data.accessToken)
        window.location.hash = '#/workbench'
      } else {
        message.error(j.msg || j.error || '登录失败')
      }
    } catch { message.error('后端未连接') } finally { setLoading(false) }
  }

  return (
    <Card title="学习解锁 · 管理后台登录" style={{ width: 380, margin: '100px auto' }}>
      <Form layout="vertical" onFinish={onFinish}>
        <Form.Item name="username" label="账号" rules={[{ required: true }]}>
          <Input placeholder="admin" autoComplete="username" />
        </Form.Item>
        <Form.Item name="password" label="密码" rules={[{ required: true }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" block loading={loading}>登录</Button>
        <p style={{ color: '#999', marginTop: 12, fontSize: 12 }}>开发引导账号 admin / admin123（首次登录自动升级加密）</p>
      </Form>
    </Card>
  )
}
