import { useState } from 'react'
import { App, Button, Card, Form, Input, Alert } from 'antd'
import { setToken } from '../../auth'
import { errorText } from '../../services/api'

// 后台登录（FR-W01）：账号密码 → admin scope JWT
export default function Login() {
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')
  const { message } = App.useApp()

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true); setErr('')
    try {
      const r = await fetch('/api/ops/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v),
      })
      const j = await r.json().catch(() => null)
      if (r.ok && j?.code === 0) {
        setToken(j.data.accessToken)
        message.success('登录成功，正在进入工作台…')
        window.location.hash = '#/workbench'
      } else {
        // 统一展示后端中文原因，绝不把 Unauthorized / Bad Request 等英文异常码抛给用户
        const msg = errorText(j, r.status === 401 ? '账号或密码错误，请重试' : '登录失败，请稍后重试')
        setErr(msg)
        message.error(msg)
      }
    } catch {
      setErr('无法连接后台服务，请确认服务已启动')
      message.error('无法连接后台服务')
    } finally { setLoading(false) }
  }

  return (
    <div style={{ padding: 16 }}>
      <Card title="学习解锁 · 管理后台登录" style={{ width: 400, maxWidth: '100%', margin: '80px auto' }}>
        {err && <Alert type="error" message={err} showIcon closable style={{ marginBottom: 16 }} onClose={() => setErr('')} />}
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }, { max: 32, message: '账号不超过 32 位' }]}>
            <Input placeholder="请输入管理员账号" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="请输入密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>登录</Button>
        </Form>
      </Card>
    </div>
  )
}
