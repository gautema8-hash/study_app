import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import {
  Table, Tag, Select, Space, Button, Input, Modal, Form, message, Popconfirm, Drawer, Descriptions, InputNumber,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SUBJECTS, subjectName, Q_STATUS } from '../../components/names'

// W5 题库运营（内置+自建全量；管理员可新增/编辑/逻辑删除/查看题目）
export default function BankOps() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [subject, setSubject] = useState<number | undefined>()
  const [grade, setGrade] = useState<number | undefined>()

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)

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

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiFetch(url, {
      method, headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (r.status === 400 || r.status === 403) { message.error((await r.json()).message ?? '操作失败'); return false }
    message.success('操作成功'); load(page, subject, grade); return true
  }

  const openEdit = async (row?: any) => {
    if (row) {
      const r = await apiFetch(`/api/ops/questions/${row.id}`)
      const d = (await r.json()).data
      form.setFieldsValue({
        subject: d.subject, grade: d.grade, difficulty: d.difficulty, qType: d.qType,
        stem: d.stem, optionsJson: d.optionsJson ?? '[]', answer: d.answer, analysis: d.analysis,
      })
    } else form.resetFields()
    setEditing(row ? { id: row.id } : {})
  }

  const save = async () => {
    setSaving(true)
    try {
      const v = await form.validateFields()
      const ok = editing?.id
        ? await post(`/api/ops/questions/${editing.id}`, v, 'PUT')
        : await post('/api/ops/questions', v)
      if (ok) setEditing(null)
    } finally { setSaving(false) }
  }

  const openView = async (id: number) => {
    setViewLoading(true); setView({ id })
    try {
      const r = await apiFetch(`/api/ops/questions/${id}`)
      const j = await r.json()
      setView(j.data)
    } catch { message.error('加载失败') }
    finally { setViewLoading(false) }
  }

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
    { title: '操作', width: 180, render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.id)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Popconfirm title="逻辑删除该题目？" onConfirm={() => post(`/api/ops/questions/${r.id}`, {}, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]
  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select placeholder="科目" allowClear style={{ width: 120 }} value={subject}
          options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))}
          onChange={(v) => { setSubject(v); setPage(1) }} />
        <Select placeholder="年级" allowClear style={{ width: 120 }} value={grade}
          options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))}
          onChange={(v) => { setGrade(v); setPage(1) }} />
        <Button type="primary" onClick={() => openEdit()}>新建题目</Button>
      </Space>
      <Table
        rowKey="id" columns={cols} dataSource={data.list} size="small"
        pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
      />

      <Modal title={editing?.id ? '编辑题目' : '新建题目'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={560}>
        <Form form={form} layout="vertical">
          <Form.Item name="stem" label="题干" rules={[{ required: true, message: '必填' }]}>
            <Input.TextArea rows={2} placeholder="题目内容" />
          </Form.Item>
          <Space>
            <Form.Item name="subject" label="科目" rules={[{ required: true, message: '必选' }]}>
              <Select style={{ width: 130 }} options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))} />
            </Form.Item>
            <Form.Item name="grade" label="年级" rules={[{ required: true, message: '必选' }]}>
              <Select style={{ width: 120 }} options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))} />
            </Form.Item>
            <Form.Item name="difficulty" label="难度" rules={[{ required: true, message: '必选' }]}>
              <Select style={{ width: 120 }} options={[1, 2, 3, 4, 5].map((d) => ({ value: d, label: `L${d}` }))} />
            </Form.Item>
            <Form.Item name="qType" label="题型" rules={[{ required: true, message: '必选' }]}>
              <Select style={{ width: 120 }} options={[1, 2, 3, 4].map((t) => ({ value: t, label: ['', '单选', '多选', '判断', '填空'][t] }))} />
            </Form.Item>
          </Space>
          <Form.Item name="optionsJson" label="选项（JSON 数组，如 ['A','B']）" rules={[{ required: true, message: '必填' }]}>
            <Input.TextArea rows={2} placeholder='["选项A","选项B"]' />
          </Form.Item>
          <Form.Item name="answer" label="答案" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如 A，或多选 AB，或判断 true" />
          </Form.Item>
          <Form.Item name="analysis" label="解析">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title={`题目详情 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={480}>
        {viewLoading ? <p>加载中…</p> : view && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{view.id}</Descriptions.Item>
            <Descriptions.Item label="科目/年级">{subjectName(view.subject)} / {view.grade}年级</Descriptions.Item>
            <Descriptions.Item label="难度">L{view.difficulty}</Descriptions.Item>
            <Descriptions.Item label="题型">{['', '单选', '多选', '判断', '填空'][view.qType]}</Descriptions.Item>
            <Descriptions.Item label="题干">{view.stem}</Descriptions.Item>
            <Descriptions.Item label="选项">{view.optionsJson}</Descriptions.Item>
            <Descriptions.Item label="答案">{view.answer}</Descriptions.Item>
            <Descriptions.Item label="解析">{view.analysis}</Descriptions.Item>
            <Descriptions.Item label="来源">{view.source === 1 ? '内置' : '自建'}</Descriptions.Item>
            <Descriptions.Item label="状态">{Q_STATUS[view.status]?.text ?? view.status}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </div>
  )
}
