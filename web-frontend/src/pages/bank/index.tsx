import { useEffect, useState, useCallback, useRef } from 'react'
import { App, Table, Tag, Select, Space, Button, Input, Modal, Form, Popconfirm, Drawer, Descriptions, Card } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { SUBJECTS, subjectName, Q_STATUS } from '../../components/names'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

// W5 题库运营：搜索 / 筛选 / 批量删除 / 导出 / 完整 CRUD
export default function BankOps() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [subject, setSubject] = useState<number | undefined>()
  const [grade, setGrade] = useState<number | undefined>()
  const [status, setStatus] = useState<number | undefined>()
  const [keyword, setKeyword] = useState('')
  const [selected, setSelected] = useState<number[]>([])

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)
  const viewSeq = useRef(0)
  const { message } = App.useApp()

  const load = useCallback(async (p: number, s?: number, g?: number, st?: number, kw?: string) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (s !== undefined) qs.set('subject', String(s))
      if (g !== undefined) qs.set('grade', String(g))
      if (st !== undefined) qs.set('status', String(st))
      if (kw?.trim()) qs.set('keyword', kw.trim())
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/questions?${qs}`))
    } catch (e: any) { message.error(e?.message || '题目列表加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, subject, grade, status, keyword) }, [page, subject, grade, status, load])
  const reload = () => load(page, subject, grade, status, keyword)

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiWrite(url, method, body, apiFetch)
    if (!r.ok) { message.error(r.msg); return false }
    message.success('操作成功'); setSelected([]); reload(); return true
  }

  const batchDelete = async () => {
    const r = await apiWrite('/api/ops/insight/batch-delete/questions', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 道题目`); setSelected([]); reload()
  }

  const openEdit = async (row?: any) => {
    try {
      if (row) {
        const d = await apiRead<any>(`/api/ops/questions/${row.id}`)
        form.setFieldsValue({
          subject: d.subject, grade: d.grade, difficulty: d.difficulty, qType: d.qType,
          stem: d.stem, optionsJson: d.optionsJson ?? '[]', answer: d.answer, analysis: d.analysis,
        })
      } else form.resetFields()
      setEditing(row ? { id: row.id } : {})
    } catch (e: any) { message.error(e?.message || '加载题目详情失败') }
  }

  const save = async () => {
    setSaving(true)
    try {
      const v = await form.validateFields()
      // 选项 JSON 前端先校验，避免脏数据入库
      try { JSON.parse(v.optionsJson) } catch { message.error('选项必须是合法的 JSON 数组'); return }
      const ok = editing?.id
        ? await post(`/api/ops/questions/${editing.id}`, v, 'PUT')
        : await post('/api/ops/questions', v)
      if (ok) setEditing(null)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '保存失败')
    } finally { setSaving(false) }
  }

  const openView = async (id: number) => {
    const seq = ++viewSeq.current
    setViewLoading(true); setView({ id })
    try {
      const d = await apiRead<any>(`/api/ops/questions/${id}`)
      if (seq !== viewSeq.current) return
      setView(d)
    } catch (e: any) { if (seq === viewSeq.current) message.error(e?.message || '加载详情失败') }
    finally { if (seq === viewSeq.current) setViewLoading(false) }
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
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
    { title: '操作', width: 180, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.id)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Popconfirm title="逻辑删除该题目？" onConfirm={() => post(`/api/ops/questions/${r.id}`, {}, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  const filtered = !!(keyword || subject !== undefined || grade !== undefined || status !== undefined)
  const reset = () => { setKeyword(''); setSubject(undefined); setGrade(undefined); setStatus(undefined); setPage(1); load(1, undefined, undefined, undefined, '') }

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Input.Search placeholder="题干关键词 / 题目ID" allowClear style={{ width: 220 }} value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={(v) => { setKeyword(v); setPage(1); load(1, subject, grade, status, v) }} />
        <Select placeholder="科目" allowClear style={{ width: 110 }} value={subject}
          options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))}
          onChange={(v) => { setSubject(v); setPage(1) }} />
        <Select placeholder="年级" allowClear style={{ width: 110 }} value={grade}
          options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))}
          onChange={(v) => { setGrade(v); setPage(1) }} />
        <Select placeholder="状态" allowClear style={{ width: 110 }} value={status}
          options={Object.entries(Q_STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))}
          onChange={(v) => { setStatus(v); setPage(1) }} />
        {filtered && <Button size="small" onClick={reset}>重置</Button>}
        <Button type="primary" onClick={() => openEdit()}>新建题目</Button>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('题库列表', data.list, [
          { title: 'ID', key: 'id' }, { title: '题干', key: 'stem' },
          { title: '科目', key: 'subject', render: (v: number) => subjectName(v) },
          { title: '年级', key: 'grade' }, { title: '难度', key: 'difficulty', render: (v: number) => `L${v}` },
          { title: '来源', key: 'source' },
          { title: '状态', key: 'status', render: (v: number) => Q_STATUS[v]?.text ?? v },
          { title: '创建时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} loading={loading} size="small" scroll={{ x: 1100 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]) }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={filtered ? '没有匹配的题目' : '题库还是空的'}
              desc={filtered ? '调整筛选条件或重置后重试' : '新建第一道题目，家长端才能使用'}
              onReset={filtered ? reset : undefined} onCreate={() => openEdit()} createText="新建题目" />
          ) }} />
      </div>

      <Modal title={editing?.id ? '编辑题目' : '新建题目'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={560} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="stem" label="题干" rules={[{ required: true, message: '请输入题干' }, { max: 500, message: '题干不超过 500 字' }]}>
            <Input.TextArea rows={2} placeholder="题目内容" />
          </Form.Item>
          <Space wrap>
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
          <Form.Item name="optionsJson" label="选项（JSON 数组，如 ['A','B']）" rules={[{ required: true, message: '请输入选项' }]}>
            <Input.TextArea rows={2} placeholder='["选项A","选项B"]' />
          </Form.Item>
          <Form.Item name="answer" label="答案" rules={[{ required: true, message: '请输入答案' }]}>
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
            <Descriptions.Item label="解析">{view.analysis || '-'}</Descriptions.Item>
            <Descriptions.Item label="来源">{view.source === 1 ? '内置' : '自建'}</Descriptions.Item>
            <Descriptions.Item label="状态">{Q_STATUS[view.status]?.text ?? view.status}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </Card>
  )
}
