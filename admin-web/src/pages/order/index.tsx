import { useEffect, useState, useCallback } from 'react'
import { Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SKU_NAMES, ORDER_STATUS, yuan } from '../../components/names'

// W3 订单与资金台账（退款审批/对账处置 TODO：需双人复核组件接入）
export default function Orders() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)

  const load = useCallback(async (p: number) => {
    try {
      const r = await fetch(`/api/ops/orders?page=${p}&size=20`)
      const j = await r.json()
      setData(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load(page) }, [page, load])

  const cols: ColumnsType<any> = [
    { title: '订单号', dataIndex: 'orderNo', width: 220 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '套餐', dataIndex: 'sku', render: (v) => SKU_NAMES[v as number] ?? v },
    { title: '金额', dataIndex: 'amount', render: (v) => yuan(v as number) },
    { title: '状态', dataIndex: 'status', render: (v) => {
        const s = ORDER_STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '下单时间', dataIndex: 'createdAt', width: 170 },
    { title: '发货时间', dataIndex: 'shippedAt', width: 170, render: (v) => v ?? '-' },
  ]
  return (
    <Table
      rowKey="orderNo" columns={cols} dataSource={data.list} size="small"
      pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
    />
  )
}
