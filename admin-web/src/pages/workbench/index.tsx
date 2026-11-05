import { useEffect, useState } from 'react'
import { apiFetch } from '../../auth'
import { Card, Col, Row, Statistic, Alert } from 'antd'

// W1 工作台：对接 /api/ops/summary 真实数据（运营汇总）
export default function Workbench() {
  const [d, setD] = useState<Record<string, number> | null>(null)
  const [err, setErr] = useState(false)

  useEffect(() => {
    apiFetch('/api/ops/summary')
      .then((r) => r.json())
      .then((j) => setD(j.data))
      .catch(() => setErr(true))
  }, [])

  if (err) return <Alert type="warning" message="后端未连接（需启动 backend:8080）" />
  if (!d) return <Card loading style={{ minHeight: 300 }} />

  const passRate = d.tasksToday > 0 ? Math.round((d.tasksPassedToday / d.tasksToday) * 100) : 0
  return (
    <div>
      <Row gutter={16}>
        <Col span={6}><Card><Statistic title="注册家庭" value={d.families} /></Card></Col>
        <Col span={6}><Card><Statistic title="学生档案" value={d.students} /></Card></Col>
        <Col span={6}><Card><Statistic title="上架题目" value={d.questions} /></Card></Col>
        <Col span={6}><Card><Statistic title="今日解锁任务" value={d.tasksToday} suffix={`/ 达标率 ${passRate}%`} /></Card></Col>
      </Row>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}><Card><Statistic title="累计付费订单" value={d.ordersPaid} /></Card></Col>
        <Col span={6}><Card><Statistic title="累计收入(元)" value={(d.revenueTotal / 100).toFixed(2)} /></Card></Col>
        <Col span={6}><Card><Statistic title="未读告警" value={d.alarmsUnread} valueStyle={{ color: d.alarmsUnread > 0 ? '#cf1322' : '#3f8600' }} /></Card></Col>
        <Col span={6}><Card><Statistic title="今日风控事件" value={d.riskEventsToday} /></Card></Col>
      </Row>
    </div>
  )
}
