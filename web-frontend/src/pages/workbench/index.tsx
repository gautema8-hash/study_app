import { useEffect, useState, useCallback } from 'react'
import { App, Card, Col, Row, Statistic, Alert, Button, Skeleton, Space, Tag } from 'antd'
import { useNavigate } from 'react-router-dom'
import { apiFetch } from '../../auth'
import { apiRead } from '../../services/api'

// W1 工作台：运营汇总 + 待办快捷入口
export default function Workbench() {
  const [d, setD] = useState<Record<string, number> | null>(null)
  const [todo, setTodo] = useState<Record<string, number> | null>(null)
  const [err, setErr] = useState('')
  const { message } = App.useApp()
  const nav = useNavigate()

  const load = useCallback(async () => {
    try {
      const [s, t] = await Promise.all([
        apiFetch('/api/ops/summary').then((r) => r.json()),
        apiRead<Record<string, number>>('/api/ops/insight/todo'),
      ])
      setD(s.data); setTodo(t); setErr('')
    } catch (e: any) {
      setErr(e?.message || '后端未连接')
    }
  }, [])

  useEffect(() => { load() }, [load])

  if (err) return (
    <Alert type="error" showIcon message="工作台数据加载失败" description={err}
      action={<Button size="small" onClick={() => { message.info('正在重试…'); load() }}>重新加载</Button>} />
  )
  if (!d) return <Card><Skeleton active paragraph={{ rows: 4 }} /></Card>

  const passRate = d.tasksToday > 0 ? Math.round((d.tasksPassedToday / d.tasksToday) * 100) : 0
  const cards = [
    { title: '注册家庭', value: d.families, to: '/user' },
    { title: '学生档案', value: d.students, to: '/user' },
    { title: '上架题目', value: d.questions, to: '/bank' },
    { title: '今日解锁任务', value: d.tasksToday, suffix: `/ 达标率 ${passRate}%`, to: '/dashboard' },
    { title: '累计付费订单', value: d.ordersPaid, to: '/order' },
    { title: '累计收入(元)', value: Number((d.revenueTotal / 100).toFixed(2)), to: '/reconcile' },
    { title: '未读告警', value: d.alarmsUnread, danger: d.alarmsUnread > 0, to: '/dashboard' },
    { title: '今日风控事件', value: d.riskEventsToday, to: '/dashboard' },
  ]

  const todos = [
    { label: '待审核题目', key: 'reviewPending', to: '/review', tone: 'orange' },
    { label: '待处理申诉', key: 'ticketPending', to: '/ticket', tone: 'red' },
    { label: '待上架题库', key: 'storePending', to: '/store', tone: 'blue' },
    { label: '未读告警', key: 'alarmsUnread', to: '/dashboard', tone: 'gold' },
    { label: '退款中订单', key: 'ordersRefunding', to: '/order', tone: 'purple' },
  ]

  return (
    <div>
      <Card size="small" title="待办事项 · 快捷入口" style={{ marginBottom: 16 }}
        extra={<Button type="link" size="small" onClick={load}>刷新</Button>}>
        <Space wrap size={8}>
          {todos.map((t) => (
            <Tag key={t.key} color={t.tone} style={{ padding: '6px 12px', fontSize: 13, cursor: 'pointer', margin: 0 }}
              onClick={() => nav(t.to)}>
              {t.label} <b style={{ fontSize: 15 }}>{todo?.[t.key] ?? 0}</b>
            </Tag>
          ))}
        </Space>
      </Card>
      <Row gutter={[16, 16]}>
        {cards.map((c) => (
          <Col xs={24} sm={12} md={6} key={c.title}>
            <Card className="ops-quick-card" size="small" onClick={() => nav(c.to)}>
              <Statistic title={c.title} value={c.value} suffix={c.suffix}
                valueStyle={c.danger ? { color: '#cf1322' } : undefined} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  )
}
