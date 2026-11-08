import { useEffect, useState, useCallback, useRef } from 'react'
import { App, Table, Tag, Space, Button, Popconfirm, Drawer, Descriptions, Spin, Card, Select, Input } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { SKU_NAMES, ORDER_STATUS, CHANNEL_NAMES, yuan } from '../../components/names'
import { EmptyState, exportCsv } from '../../components/common'

// W3 订单与资金台账：搜索 / 筛选 / 导出 / 查看 / 退款（财务记录不提供删除）
export default function Orders() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<number | undefined>()
  const [sku, setSku] = useState<number | undefined>()
  const [keyword, setKeyword] = useState('')

  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)
  const viewSeq = useRef(0)
  const { message } = App.useApp()

  const load = useCallback(async (p: number, st?: number, sk?: number, kw?: string) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (st !== undefined) qs.set('status', String(st))
      if (sk !== undefined) qs.set('sku', String(sk))
      if (kw?.trim()) qs.set('keyword', kw.trim())
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/orders?${qs}`))
    } catch (e: any) { message.error(e?.message || '订单列表加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, status, sku, keyword) }, [page, status, sku, load])
  const reload = () => load(page, status, sku, keyword)

  const openView = async (orderNo: string) => {
    const seq = ++viewSeq.current
    setViewLoading(true); setView({ orderNo })
    try {
      const d = await apiRead<any>(`/api/ops/orders/${orderNo}`)
      if (seq !== viewSeq.current) return
      setView(d)
    } catch (e: any) { if (seq === viewSeq.current) message.error(e?.message || '加载详情失败') }
    finally { if (seq === viewSeq.current) setViewLoading(false) }
  }

  const refund = async (orderNo: string) => {
    const r = await apiWrite(`/api/ops/orders/${orderNo}/refund`, 'POST', undefined, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success('退款成功'); reload()
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
    { title: '操作', width: 140, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.orderNo)}>查看</Button>
        <Popconfirm title="确认后台退款？" description="仅已发货订单可退，操作不可撤销" onConfirm={() => refund(r.orderNo)}>
          <Button size="small" type="link" danger disabled={r.status !== 2}>退款</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  const filtered = !!(keyword || status !== undefined || sku !== undefined)
  const reset = () => { setKeyword(''); setStatus(undefined); setSku(undefined); setPage(1); load(1, undefined, undefined, '') }

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Input.Search placeholder="订单号 / 家庭ID" allowClear style={{ width: 220 }} value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={(v) => { setKeyword(v); setPage(1); load(1, status, sku, v) }} />
        <Select placeholder="订单状态" allowClear style={{ width: 130 }} value={status}
          options={Object.entries(ORDER_STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))}
          onChange={(v) => { setStatus(v); setPage(1) }} />
        <Select placeholder="套餐" allowClear style={{ width: 120 }} value={sku}
          options={Object.entries(SKU_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
          onChange={(v) => { setSku(v); setPage(1) }} />
        {filtered && <Button size="small" onClick={reset}>重置</Button>}
        <Button size="small" onClick={reload}>刷新</Button>
        <Button onClick={() => exportCsv('订单台账', data.list, [
          { title: '订单号', key: 'orderNo' }, { title: '家庭ID', key: 'familyId' },
          { title: '套餐', key: 'sku', render: (v: number) => SKU_NAMES[v] ?? v },
          { title: '金额(元)', key: 'amount', render: (v: number) => (v / 100).toFixed(2) },
          { title: '渠道', key: 'channel', render: (v: number) => CHANNEL_NAMES[v] ?? v },
          { title: '状态', key: 'status', render: (v: number) => ORDER_STATUS[v]?.text ?? v },
          { title: '下单时间', key: 'createdAt' }, { title: '发货时间', key: 'shippedAt' },
          { title: '退款时间', key: 'refundAt' },
        ])}>导出 CSV</Button>
      </div>
      <div className="ops-table-wrap">
        <Table rowKey="orderNo" columns={cols} dataSource={data.list} loading={loading} size="small"
          scroll={{ x: 1200 }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={filtered ? '没有匹配的订单' : '还没有订单'}
              desc={filtered ? '调整筛选条件后重试' : '订单产生后会自动出现在这里'}
              onReset={filtered ? reset : undefined} />
          ) }} />
      </div>
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
    </Card>
  )
}
