import { useEffect, useState, useCallback } from 'react'
import { Table, Button, message, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { subjectName } from '../../components/names'

// W6 审核工单（FR-W12）：灰词题目人工复核，通过→出题池 / 驳回→不出题
export default function Review() {
  const [list, setList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await apiFetch('/api/ops/review/tickets?status=1')
      const j = await r.json()
      setList(j.data.list)
    } catch (e: any) { if (e.message !== '登录已过期') message.error('加载失败') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const decide = async (id: number, pass: boolean) => {
    const r = await apiFetch(`/api/ops/review/tickets/${id}/decide`, {
      method: 'POST', body: JSON.stringify({ pass, reason: pass ? '' : '内容不适合题库' }),
    })
    if (r.ok) { message.success(pass ? '已通过' : '已驳回'); load() } else message.error('裁决失败')
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '科目', dataIndex: 'subject', width: 70, render: (v) => subjectName(v as number) },
    { title: '年级', dataIndex: 'grade', width: 60 },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '命中原因', dataIndex: 'reason', ellipsis: true },
    { title: '提交时间', dataIndex: 'createdAt', width: 160 },
    { title: '操作', width: 150, render: (_, r) => (
      <>
        <Button size="small" type="primary" onClick={() => decide(r.id, true)}>通过</Button>{' '}
        <Button size="small" danger onClick={() => decide(r.id, false)}>驳回</Button>
      </>
    ) },
  ]
  return (
    <>
      <Tag style={{ marginBottom: 12 }}>时效要求：P95 ≤ 4 小时（后台 PRD FR-W12）</Tag>
      <Table rowKey="id" columns={cols} dataSource={list} loading={loading} size="small" pagination={false} />
    </>
  )
}
