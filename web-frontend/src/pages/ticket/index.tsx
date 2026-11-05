import { useEffect, useState, useCallback } from 'react'
import { Table, Button, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'

// W7 客服申诉工单（FR-W15/16）：判废申诉裁决，48h SLA
const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待处理', color: 'processing' },
  2: { text: '已解决(撤销判废)', color: 'success' },
  3: { text: '已驳回', color: 'error' },
}

export default function Tickets() {
  const [list, setList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await apiFetch('/api/ops/tickets?status=0')
      const j = await r.json()
      setList(j.data.list)
    } catch (e: any) { if (e.message !== '登录已过期') message.error('加载失败') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const decide = async (id: number, approve: boolean) => {
    const r = await apiFetch(`/api/ops/tickets/${id}/decide`, {
      method: 'POST', body: JSON.stringify({ approve, decision: approve ? '证据核实，撤销判废' : '证据不足，维持判废' }),
    })
    if (r.ok) { message.success(approve ? '已撤销判废' : '已驳回'); load() } else message.error('裁决失败')
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '任务ID', dataIndex: 'taskId', width: 80 },
    { title: '类型', dataIndex: 'type', width: 80 },
    { title: '申诉内容', dataIndex: 'content', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 140, render: (v) => {
        const s = STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '提交时间', dataIndex: 'createdAt', width: 160 },
    { title: '操作', width: 150, render: (_, r) => (
      <>
        <Button size="small" type="primary" onClick={() => decide(r.id, true)}>撤销判废</Button>{' '}
        <Button size="small" danger onClick={() => decide(r.id, false)}>维持</Button>
      </>
    ) },
  ]
  return (
    <>
      <Tag style={{ marginBottom: 12 }}>裁决时效：48 小时（后台 PRD FR-W16）</Tag>
      <Table rowKey="id" columns={cols} dataSource={list} loading={loading} size="small" pagination={false} />
    </>
  )
}
