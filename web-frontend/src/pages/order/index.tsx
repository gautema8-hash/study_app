import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import { Table, Tag, message, Space, Button, Popconfirm, Drawer, Descriptions, Spin } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SKU_NAMES, ORDER_STATUS, CHANNEL_NAMES, yuan } from '../../components/names'

// W3 订单与资金台账（查看 + 退款；订单为财务记录，不提供删除）
export default function Orders() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)

  const load = useCallback(async (p: number) => {
    try {
      const r = await apiFetch(`/api/ops/orders?page=${p}&size=20`)
      const j = await r.json()
      setData(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load(page) }, [page, load])

  const openView = async (orderNo: string) => {
    setViewLoading(true); setView({ orderNo })
    try {
      const res = await apiFetch(`/api/ops/orders/${orderNo}`)
      setView((await res.json()).data)
    } catch { message.error('加载详情失败') }
    finally { setViewLoading(false) }
  }

  const refund = async (orderNo: string) => {
    const r = await apiFetch(`/api/ops/orders/${orderNo}/refund`, { method: 'POST' })
    if (r.ok) { message.success('退款成功'); load(page) } else message.error((await r.json()).message ?? '退款失败')
  }

  const cols: ColumnsType<any> = [
    { title: '订单号', dataIndex: 'orderNo', width: 220 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '套餐', dataIndex: 'sku', render: (v) => SKU_NAMES[v as number] ?? v },
    { title: '金额', dataIndex: 'amount', render: (v) => yuan(v as number) },
    { title: '渠道', dataIndex: 'channel', width: 90, render: (v) => CHANNEL_NAMES[v as number] ?? v },
    { title: '状态', dataIndex: 'status', render: (v) => {
        const s = ORDER_STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '下单时间', dataIndex: 'createdAt', width: 170 },
    { title: '发货时间', dataIndex: 'shippedAt', width: 170, render: (v) => v ?? '-' },
    { title: '操作', width: 140, render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.orderNo)}>查看</Button>
        <Popconfirm title="确认后台退款？仅已发货订单可退" onConfirm={() => refund(r.orderNo)}>
          <Button size="small" type="link" danger disabled={r.status !== 2}>退款</Button>
        </Popconfirm>
      </Space>
    ) },
  ]
  return (
    <>
      <Table
        rowKey="orderNo" columns={cols} dataSource={data.list} size="small"
        pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
      />
      <Drawer title={`订单详情 ${view?.orderNo ?? ''}`} open={!!view} onClose={() => setView(null)} width={560}>
        <Spin spinning={viewLoading}>
          {view && (
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="订单号">{view.orderNo}</Descriptions.Item>
              <Descriptions.Item label="家庭ID">{view.familyId}</Descriptions.Item>
              <Descriptions.Item label="套餐">{SKU_NAMES[view.sku as number] ?? view.sku}</Descriptions.Item>
              <Descriptions.Item label="金额">{yuan(view.amount as number)}</Descriptions.Item>
              <Descriptions.Item label="渠道">{CHANNEL_NAMES[view.channel as number] ?? view.channel}</Descriptions.Item>
              <Descriptions.Item label="状态">{(ORDER_STATUS[view.status as number] ?? { text: view.status }).text}</Descriptions.Item>
              <Descriptions.Item label="下单时间">{view.createdAt || '-'}</Descriptions.Item>
              <Descriptions.Item label="发货时间">{view.shippedAt || '-'}</Descriptions.Item>
              <Descriptions.Item label="退款时间">{view.refundAt || '-'}</Descriptions.Item>
            </Descriptions>
          )}
        </Spin>
      </Drawer>
    </>
  )
}
