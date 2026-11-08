import { useEffect, useState, useCallback } from 'react'
import { App, Table, Button, Tag, Space, Drawer, Descriptions, Popconfirm, Card, Select } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { subjectName } from '../../components/names'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

const STATUS_OPTIONS = [
  { value: 1, label: '待复核' }, { value: 0, label: '已通过' }, { value: 2, label: '已驳回' }, { value: -1, label: '全部' },
]

// W6 审核工单：灰词题目人工复核 + 状态筛选 / 分页 / 批量删除 / 导出
export default function Review() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<number>(1)
  const [selected, setSelected] = useState<number[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<any>(null)
  const { message } = App.useApp()

  const load = useCallback(async (p: number, st: number) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20', status: String(st) })
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/review/tickets?${qs}`))
    } catch (e: any) { if (e?.message !== '登录已过期') message.error(e?.message || '审核工单加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, status) }, [page, status, load])
  const reload = () => load(page, status)

  const decide = async (id: number, pass: boolean) => {
    const r = await apiWrite(`/api/ops/review/tickets/${id}/decide`, 'POST',
      { pass, reason: pass ? '' : '内容不适合题库' }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(pass ? '已通过，题目进入出题池' : '已驳回'); reload()
  }

  const remove = async (id: number) => {
    const r = await apiWrite(`/api/ops/review/tickets/${id}`, 'DELETE', undefined, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success('已删除'); setSelected([]); reload()
  }

  const batchDelete = async () => {
    const r = await apiWrite('/api/ops/insight/batch-delete/reviewTickets', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 条工单`); setSelected([]); reload()
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '科目', dataIndex: 'subject', width: 70, render: (v) => subjectName(v as number) },
    { title: '年级', dataIndex: 'grade', width: 60 },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '命中原因', dataIndex: 'reason', ellipsis: true },
    { title: '状态', dataIndex: 'reviewStatus', width: 90, render: (v) => {
      const map: Record<number, { t: string; c: string }> = { 1: { t: '待复核', c: 'processing' }, 0: { t: '已通过', c: 'success' }, 2: { t: '已驳回', c: 'error' } }
      const s = map[v] ?? { t: v, c: 'default' }; return <Tag color={s.c}>{s.t}</Tag>
    } },
    { title: '提交时间', dataIndex: 'createdAt', width: 160 },
    { title: '操作', width: 220, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => setView(r)}>查看</Button>
        {r.reviewStatus === 1 && <>
          <Button size="small" type="primary" onClick={() => decide(r.id, true)}>通过</Button>
          <Button size="small" danger onClick={() => decide(r.id, false)}>驳回</Button>
        </>}
        <Popconfirm title="逻辑删除该灰词题目？" onConfirm={() => remove(r.id)}>
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
        <Tag style={{ marginLeft: 'auto' }}>时效要求：P95 ≤ 4 小时</Tag>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('审核工单', data.list, [
          { title: 'ID', key: 'id' }, { title: '家庭ID', key: 'familyId' },
          { title: '科目', key: 'subject', render: (v: number) => subjectName(v) },
          { title: '年级', key: 'grade' }, { title: '题干', key: 'stem' },
          { title: '命中原因', key: 'reason' }, { title: '提交时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} loading={loading} size="small" scroll={{ x: 1120 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]) }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={status === 1 ? '太好了，没有待复核工单' : '该状态下暂无工单'}
              desc={status === 1 ? '所有灰词题目都已处理完毕' : '切换到「全部」查看历史记录'}
              onReset={status !== 1 ? () => { setStatus(-1); setPage(1) } : undefined} />
          ) }} />
      </div>

      <Drawer title={`复核工单 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={560}>
        {view && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{view.id}</Descriptions.Item>
            <Descriptions.Item label="家庭ID">{view.familyId}</Descriptions.Item>
            <Descriptions.Item label="科目">{subjectName(view.subject as number)}</Descriptions.Item>
            <Descriptions.Item label="年级">{view.grade}年级</Descriptions.Item>
            <Descriptions.Item label="题干">{view.stem}</Descriptions.Item>
            <Descriptions.Item label="命中原因">{view.reason || '-'}</Descriptions.Item>
            <Descriptions.Item label="提交时间">{view.createdAt}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </Card>
  )
}
