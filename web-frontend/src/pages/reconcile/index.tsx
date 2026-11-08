import { useEffect, useState, useCallback } from 'react'
import { App, Card, Col, Row, Statistic, Button, Skeleton, Alert, Table, Tag } from 'antd'
import { apiRead } from '../../services/api'
import { exportCsv } from '../../components/common'
import { SKU_NAMES, CHANNEL_NAMES, yuan } from '../../components/names'

interface ReconcileData {
  orderCount: number; paidCount: number; paidAmount: number; shippedAmount: number
  refundCount: number; refundAmount: number; refundingCount: number
  byChannel: { channel: number; count: number; amount: number }[]
  bySku: { sku: number; count: number; amount: number }[]
  abnormalOrders: { orderNo: string; familyId: number; sku: number; amount: number; channel: number; status: number; createdAt: string }[]
}

// W4 对账中心：由占位页改为真实资金对账 + 资损异常提示
export default function Reconcile() {
  const [d, setD] = useState<ReconcileData | null>(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)
  const { message } = App.useApp()

  const load = useCallback(async () => {
    setLoading(true)
    try { setD(await apiRead<ReconcileData>('/api/ops/insight/reconcile')); setErr('') }
    catch (e: any) { setErr(e?.message || '加载失败'); message.error(e?.message || '对账数据加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load() }, [load])

  if (loading) return <Card><Skeleton active paragraph={{ rows: 6 }} /></Card>
  if (err || !d) return <Alert type="error" showIcon message="对账数据加载失败" description={err}
    action={<Button size="small" onClick={load}>重试</Button>} />

  const stats = [
    { title: '订单总数', value: d.orderCount }, { title: '有效支付笔数', value: d.paidCount },
    { title: '支付总额', value: yuan(d.paidAmount), text: true },
    { title: '已发货金额', value: yuan(d.shippedAmount), text: true },
    { title: '退款笔数', value: d.refundCount }, { title: '退款总额', value: yuan(d.refundAmount), text: true },
    { title: '退款中', value: d.refundingCount, danger: d.refundingCount > 0 },
    { title: '净收入', value: yuan(d.paidAmount - d.refundAmount), text: true },
  ]

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between' }}>
        <span style={{ color: '#8c8c8c' }}>资金台账口径：status 1/2 计支付，5 计退款；金额单位为分，展示已换算为元</span>
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

      {d.abnormalOrders.length > 0 && (
        <Alert type="warning" showIcon style={{ marginTop: 12 }}
          message={`发现 ${d.abnormalOrders.length} 笔「已支付但未发货」订单，可能存在资损风险`}
          description="请核对支付回调与发货记录，必要时手动补发或退款。" />
      )}

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} lg={8}>
          <Card size="small" title="按支付渠道汇总">
            <Table size="small" pagination={false} rowKey="channel" dataSource={d.byChannel}
              columns={[
                { title: '渠道', dataIndex: 'channel', render: (v: number) => CHANNEL_NAMES[v] ?? v },
                { title: '笔数', dataIndex: 'count' },
                { title: '金额', dataIndex: 'amount', render: (v: number) => yuan(v) },
              ]} />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="按套餐汇总">
            <Table size="small" pagination={false} rowKey="sku" dataSource={d.bySku}
              columns={[
                { title: '套餐', dataIndex: 'sku', render: (v: number) => SKU_NAMES[v] ?? v },
                { title: '笔数', dataIndex: 'count' },
                { title: '金额', dataIndex: 'amount', render: (v: number) => yuan(v) },
              ]} />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="资损校验">
            <p style={{ margin: '4px 0' }}>支付笔数：<b>{d.paidCount}</b></p>
            <p style={{ margin: '4px 0' }}>支付与发货金额差：<b>{yuan(d.paidAmount - d.shippedAmount)}</b></p>
            <p style={{ margin: '4px 0' }}>退款占比：<b>{d.paidAmount ? (d.refundAmount / d.paidAmount * 100).toFixed(1) : 0}%</b></p>
            <Tag color={d.abnormalOrders.length ? 'error' : 'success'}>
              {d.abnormalOrders.length ? `${d.abnormalOrders.length} 笔待核查` : '无异常订单'}
            </Tag>
          </Card>
        </Col>
      </Row>

      <Card size="small" title={`异常订单明细（已支付未发货，共 ${d.abnormalOrders.length} 笔）`} style={{ marginTop: 12 }}
        extra={<Button size="small" onClick={() => exportCsv('异常订单', d.abnormalOrders, [
          { title: '订单号', key: 'orderNo' }, { title: '家庭ID', key: 'familyId' },
          { title: '套餐', key: 'sku', render: (v: number) => SKU_NAMES[v] ?? v },
          { title: '金额(元)', key: 'amount', render: (v: number) => (v / 100).toFixed(2) },
          { title: '渠道', key: 'channel', render: (v: number) => CHANNEL_NAMES[v] ?? v },
          { title: '下单时间', key: 'createdAt' },
        ])}>导出 CSV</Button>}>
        <div className="ops-table-wrap">
          <Table size="small" rowKey="orderNo" dataSource={d.abnormalOrders} pagination={{ pageSize: 10 }}
            columns={[
              { title: '订单号', dataIndex: 'orderNo', width: 210 },
              { title: '家庭ID', dataIndex: 'familyId', width: 80 },
              { title: '套餐', dataIndex: 'sku', render: (v: number) => SKU_NAMES[v] ?? v },
              { title: '金额', dataIndex: 'amount', render: (v: number) => yuan(v) },
              { title: '渠道', dataIndex: 'channel', render: (v: number) => CHANNEL_NAMES[v] ?? v },
              { title: '下单时间', dataIndex: 'createdAt' },
            ]}
            locale={{ emptyText: <div className="ops-empty">暂无异常订单，资金台账健康</div> }} />
        </div>
      </Card>
    </div>
  )
}
