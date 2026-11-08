import { useEffect, useState, useCallback, useRef } from 'react'
import { App, Table, Button, Tag, Space, Drawer, Descriptions, Spin, Popconfirm, Card, Select } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待处理', color: 'processing' },
  2: { text: '已解决(撤销判废)', color: 'success' },
  3: { text: '已驳回', color: 'error' },
}
const STATUS_OPTIONS = [
  { value: 0, label: '待处理' }, { value: 2, label: '已解决' }, { value: 3, label: '已驳回' }, { value: -1, label: '全部' },
]

// W7 客服申诉工单：状态筛选 / 分页 / 批量删除 / 导出 / 裁决
export default function Tickets() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<number>(0)
  const [selected, setSelected] = useState<number[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)
  const viewSeq = useRef(0)
  const { message } = App.useApp()

  const load = useCallback(async (p: number, st: number) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20', status: String(st) })
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/tickets?${qs}`))
    } catch (e: any) { if (e?.message !== '登录已过期') message.error(e?.message || '申诉工单加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, status) }, [page, status, load])
  const reload = () => load(page, status)

  const decide = async (id: number, approve: boolean) => {
    const r = await apiWrite(`/api/ops/tickets/${id}/decide`, 'POST',
      { approve, decision: approve ? '证据核实，撤销判废' : '证据不足，维持判废' }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(approve ? '已撤销判废，任务恢复达标' : '已驳回申诉'); reload()
  }

  const openView = async (id: number) => {
    const seq = ++viewSeq.current
    setViewLoading(true); setView({ id })
    try {
      const d = await apiRead<any>(`/api/ops/tickets/${id}`)
      if (seq !== viewSeq.current) return
      setView(d)
    } catch (e: any) { if (seq === viewSeq.current) message.error(e?.message || '加载详情失败') }
    finally { if (seq === viewSeq.current) setViewLoading(false) }
  }

  const remove = async (id: number) => {
    const r = await apiWrite(`/api/ops/tickets/${id}`, 'DELETE', undefined, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success('已删除'); setSelected([]); reload()
  }

  const batchDelete = async () => {
    const r = await apiWrite('/api/ops/insight/batch-delete/tickets', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 条工单`); setSelected([]); reload()
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '任务ID', dataIndex: 'taskId', width: 90, render: (v) => v ?? '-' },
    { title: '类型', dataIndex: 'type', width: 80 },
    { title: '申诉内容', dataIndex: 'content', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 140, render: (v) => {
        const s = STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '提交时间', dataIndex: 'createdAt', width: 160 },
    { title: '操作', width: 200, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.id)}>查看</Button>
        {r.status === 0 && <>
          <Button size="small" type="primary" onClick={() => decide(r.id, true)}>撤销判废</Button>
          <Button size="small" danger onClick={() => decide(r.id, false)}>维持</Button>
        </>}
        <Popconfirm title="逻辑删除该工单？" onConfirm={() => remove(r.id)}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Select style={{ width: 130 }} value={status} options={STATUS_OPTIONS}
          onChange={(v) => { setStatus(v); setPage(1); setSelected([]) }} />
        <Button size="small" onClick={reload}>刷新</Button>
        <Tag style={{ marginLeft: 'auto' }}>裁决时效：48 小时</Tag>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('客服工单', data.list, [
          { title: 'ID', key: 'id' }, { title: '家庭ID', key: 'familyId' },
          { title: '任务ID', key: 'taskId' }, { title: '类型', key: 'type' },
          { title: '申诉内容', key: 'content' },
          { title: '状态', key: 'status', render: (v: number) => STATUS[v]?.text ?? v },
          { title: '裁决意见', key: 'decision' }, { title: '提交时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} loading={loading} size="small" scroll={{ x: 1140 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]) }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={status === 0 ? '没有待处理的申诉' : '该状态下暂无工单'}
              desc={status === 0 ? '所有申诉都已裁决完毕' : '切换到「全部」查看历史记录'}
              onReset={status !== 0 ? () => { setStatus(-1); setPage(1) } : undefined} />
          ) }} />
      </div>

      <Drawer title={`申诉工单 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={560}>
        <Spin spinning={viewLoading}>
          {view && (
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="ID">{view.id}</Descriptions.Item>
              <Descriptions.Item label="家庭ID">{view.familyId}</Descriptions.Item>
              <Descriptions.Item label="学生ID">{view.profileId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="任务ID">{view.taskId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="类型">{view.type}</Descriptions.Item>
              <Descriptions.Item label="申诉内容">{view.content}</Descriptions.Item>
              <Descriptions.Item label="状态">{(STATUS[view.status as number] ?? { text: view.status }).text}</Descriptions.Item>
              <Descriptions.Item label="裁决意见">{view.decision || '-'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">{view.createdAt}</Descriptions.Item>
            </Descriptions>
          )}
        </Spin>
      </Drawer>
    </Card>
  )
}
