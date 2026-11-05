import { useEffect, useState, useCallback } from 'react'
import { Table, Button, message, Tag, Space, Drawer, Descriptions, Popconfirm } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { subjectName } from '../../components/names'

// W6 审核工单（FR-W12）：灰词题目人工复核，通过→出题池 / 驳回→不出题
export default function Review() {
  const [list, setList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<any>(null)

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

  const remove = async (id: number) => {
    const r = await apiFetch(`/api/ops/review/tickets/${id}`, { method: 'DELETE' })
    if (r.ok) { message.success('已删除'); load() } else message.error('删除失败')
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '科目', dataIndex: 'subject', width: 70, render: (v) => subjectName(v as number) },
    { title: '年级', dataIndex: 'grade', width: 60 },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '命中原因', dataIndex: 'reason', ellipsis: true },
    { title: '提交时间', dataIndex: 'createdAt', width: 160 },
    { title: '操作', width: 220, render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => setView(r)}>查看</Button>
        <Button size="small" type="primary" onClick={() => decide(r.id, true)}>通过</Button>
        <Button size="small" danger onClick={() => decide(r.id, false)}>驳回</Button>
        <Popconfirm title="逻辑删除该灰词题目？" onConfirm={() => remove(r.id)}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]
  return (
    <>
      <Tag style={{ marginBottom: 12 }}>时效要求：P95 ≤ 4 小时（后台 PRD FR-W12）</Tag>
      <Table rowKey="id" columns={cols} dataSource={list} loading={loading} size="small" pagination={false} />
      <Drawer title={`复核工单 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={560}>
        {view && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{view.id}</Descriptions.Item>
            <Descriptions.Item label="家庭ID">{view.familyId}</Descriptions.Item>
            <Descriptions.Item label="科目">{subjectName(view.subject as number)}</Descriptions.Item>
            <Descriptions.Item label="年级">{view.grade}年级</Descriptions.Item>
            <Descriptions.Item label="题干">{view.stem}</Descriptions.Item>
            <Descriptions.Item label="命中原因">{view.reason}</Descriptions.Item>
            <Descriptions.Item label="提交时间">{view.createdAt}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </>
  )
}
