import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import { Table, Tag, Select, Space, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SUBJECTS, subjectName, Q_STATUS } from '../../components/names'

// W5 题库运营（内置+自建全量；紧急下架/人工复核工单 TODO）
export default function BankOps() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [subject, setSubject] = useState<number | undefined>()
  const [grade, setGrade] = useState<number | undefined>()

  const load = useCallback(async (p: number, s?: number, g?: number) => {
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (s) qs.set('subject', String(s))
      if (g) qs.set('grade', String(g))
      const r = await apiFetch(`/api/ops/questions?${qs}`)
      const j = await r.json()
      setData(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load(page, subject, grade) }, [page, subject, grade, load])

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '科目', dataIndex: 'subject', width: 80, render: (v) => subjectName(v as number) },
    { title: '年级', dataIndex: 'grade', width: 60 },
    { title: '难度', dataIndex: 'difficulty', width: 60, render: (v) => `L${v}` },
    { title: '来源', dataIndex: 'source', width: 70 },
    { title: '状态', dataIndex: 'status', width: 80, render: (v) => {
        const s = Q_STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '创建时间', dataIndex: 'createdAt', width: 160 },
  ]
  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Select placeholder="科目" allowClear style={{ width: 120 }} value={subject}
          options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))}
          onChange={(v) => { setSubject(v); setPage(1) }} />
        <Select placeholder="年级" allowClear style={{ width: 120 }} value={grade}
          options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))}
          onChange={(v) => { setGrade(v); setPage(1) }} />
      </Space>
      <Table
        rowKey="id" columns={cols} dataSource={data.list} size="small"
        pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
      />
    </div>
  )
}
