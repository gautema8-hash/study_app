import { useEffect, useState, useCallback } from 'react'
import { App, Card, Col, Row, Statistic, Button, Skeleton, Alert, Table, Tag, Progress } from 'antd'
import { apiRead } from '../../services/api'
import { SUBJECTS, TIER_NAMES, yuan } from '../../components/names'

interface DashboardData {
  families: number; students: number; questions: number; storeItems: number
  ordersPaid: number; ordersRefunded: number; revenueTotal: number; refundTotal: number
  vipFamilies: number; alarmsUnread: number
  orderTrend: { day: string; count: number; amount: number }[]
  subjectDist: { subject: number; count: number }[]
  tierDist: { tier: number; count: number }[]
}

// W8 数据看板：由占位页改为真实宽表指标 + 趋势 + 分布
export default function Dashboard() {
  const [d, setD] = useState<DashboardData | null>(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const { message } = App.useApp()

  const load = useCallback(async () => {
    setLoading(true)
    try { setD(await apiRead<DashboardData>('/api/ops/insight/dashboard')); setErr('') }
    catch (e: any) { setErr(e?.message || '加载失败'); message.error(e?.message || '看板数据加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load() }, [load])

  if (loading) return <Card><Skeleton active paragraph={{ rows: 8 }} /></Card>
  if (err || !d) return <Alert type="error" showIcon message="看板数据加载失败" description={err}
    action={<Button size="small" onClick={load}>重试</Button>} />

  const maxTrend = Math.max(1, ...d.orderTrend.map((x) => x.count))
  const maxSubject = Math.max(1, ...d.subjectDist.map((x) => x.count))

  const stats = [
    { title: '注册家庭', value: d.families }, { title: '学生档案', value: d.students },
    { title: '上架题目', value: d.questions }, { title: '上架题库', value: d.storeItems },
    { title: '付费订单', value: d.ordersPaid }, { title: '退款订单', value: d.ordersRefunded },
    { title: '累计收入', value: yuan(d.revenueTotal), isText: true },
    { title: '退款金额', value: yuan(d.refundTotal), isText: true },
    { title: '付费家庭', value: d.vipFamilies }, { title: '未读告警', value: d.alarmsUnread, danger: d.alarmsUnread > 0 },
  ]

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between' }}>
        <span style={{ color: '#8c8c8c' }}>数据实时来自业务库，点击右上角刷新</span>
        <Button size="small" onClick={load}>刷新</Button>
      </div>
      <Row gutter={[12, 12]}>
        {stats.map((s) => (
          <Col xs={12} sm={8} md={6} key={s.title}>
            <Card size="small"><Statistic title={s.title} value={s.value}
              valueStyle={s.danger ? { color: '#cf1322' } : undefined} /></Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={12}>
          <Card size="small" title="近 7 日订单趋势">
            {d.orderTrend.length === 0 ? <div className="ops-empty">近 7 日暂无订单</div> : (
              <Table size="small" pagination={false} rowKey="day" dataSource={d.orderTrend}
                columns={[
                  { title: '日期', dataIndex: 'day', width: 110 },
                  { title: '订单数', dataIndex: 'count', width: 90 },
                  { title: '占比', width: 140, render: (_, r: any) => <Progress percent={Math.round(r.count / maxTrend * 100)} size="small" /> },
                  { title: '金额', dataIndex: 'amount', render: (v: number) => yuan(v) },
                ]} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="题目科目分布">
            {d.subjectDist.length === 0 ? <div className="ops-empty">暂无上架题目</div> : (
              <Table size="small" pagination={false} rowKey="subject" dataSource={d.subjectDist}
                columns={[
                  { title: '科目', dataIndex: 'subject', width: 90, render: (v: number) => SUBJECTS[v - 1] ?? v },
                  { title: '题目数', dataIndex: 'count', width: 90 },
                  { title: '占比', render: (_, r: any) => <Progress percent={Math.round(r.count / maxSubject * 100)} size="small" /> },
                ]} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="会员等级分布">
            <Table size="small" pagination={false} rowKey="tier" dataSource={d.tierDist}
              columns={[
                { title: '等级', dataIndex: 'tier', render: (v: number) => <Tag color={v > 0 ? 'gold' : 'default'}>{TIER_NAMES[v] ?? v}</Tag> },
                { title: '家庭数', dataIndex: 'count' },
              ]} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="运营健康度">
            <p style={{ margin: '4px 0' }}>付费转化率：<b>{d.families ? (d.vipFamilies / d.families * 100).toFixed(1) : 0}%</b></p>
            <p style={{ margin: '4px 0' }}>退款率：<b>{d.ordersPaid ? (d.ordersRefunded / d.ordersPaid * 100).toFixed(1) : 0}%</b></p>
            <p style={{ margin: '4px 0' }}>净收入：<b>{yuan(d.revenueTotal - d.refundTotal)}</b></p>
            <p style={{ margin: '4px 0' }}>待处理告警：<b style={{ color: d.alarmsUnread > 0 ? '#cf1322' : undefined }}>{d.alarmsUnread}</b></p>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
