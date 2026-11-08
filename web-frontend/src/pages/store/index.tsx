import { useEffect, useState, useCallback, useRef } from 'react'
import { App, Table, Tag, Select, Space, Button, Input, Modal, Form, Popconfirm, Badge, Checkbox, Drawer, Descriptions, Spin, Card } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { SUBJECTS, subjectName, STORE_STATUS, TAG_COLORS } from '../../components/names'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

// 题库商店运营（PRD FR-S01~S07）：录入→提交→审核→上架/下架→打标签 + 批量/导出/筛选
export default function BankStore() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<{ status?: number; subject?: number; grade?: number; keyword?: string }>({})
  const [tags, setTags] = useState<any[]>([])
  const [selected, setSelected] = useState<number[]>([])

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [pickerOpen, setPickerOpen] = useState(false)
  const [picked, setPicked] = useState<number[]>([])
  const [pool, setPool] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [poolPage, setPoolPage] = useState(1)
  const [poolKw, setPoolKw] = useState('')

  const [reviewing, setReviewing] = useState<any>(null)
  const [tagging, setTagging] = useState<null | { id: number; current: number[] }>(null)
  const [checkedTags, setCheckedTags] = useState<number[]>([])
  const [tagForm] = Form.useForm()
  const [newTagOpen, setNewTagOpen] = useState(false)
  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)
  const viewSeq = useRef(0)
  const { message } = App.useApp()

  const load = useCallback(async (p = page, f = filters) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (f.status !== undefined) qs.set('status', String(f.status))
      if (f.subject !== undefined) qs.set('subject', String(f.subject))
      if (f.grade !== undefined) qs.set('grade', String(f.grade))
      if (f.keyword) qs.set('keyword', f.keyword)
      const d = await apiRead<{ total: number; list: any[] }>(`/api/ops/store/items?${qs}`)
      setData({ total: d.total ?? 0, list: d.list ?? [] })
    } catch (e: any) { message.error(e?.message || '题库列表加载失败') }
    finally { setLoading(false) }
  }, [page, filters, message])

  const loadTags = useCallback(async () => {
    try { const d = await apiRead<{ list: any[] }>('/api/ops/store/tags'); setTags(d.list ?? []) }
    catch (e: any) { message.error(e?.message || '标签加载失败') }
  }, [message])

  useEffect(() => { load() }, [page, load])
  useEffect(() => { loadTags() }, [loadTags])
  const reload = () => load(page, filters)

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiWrite(url, method, body, apiFetch)
    if (!r.ok) { message.error(r.msg); return false }
    message.success('操作成功'); setSelected([]); reload(); return true
  }

  const batchDelete = async () => {
    const r = await apiWrite('/api/ops/insight/batch-delete/storeItems', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 个题库`); setSelected([]); reload()
  }

  const openView = async (id: number) => {
    const seq = ++viewSeq.current
    setViewLoading(true); setView({ id })
    try {
      const d = await apiRead<any>(`/api/ops/store/items/${id}`)
      if (seq !== viewSeq.current) return
      setView(d)
    } catch (e: any) { if (seq === viewSeq.current) message.error(e?.message || '加载详情失败') }
    finally { if (seq === viewSeq.current) setViewLoading(false) }
  }

  const openEdit = async (row?: any) => {
    setPicked([])
    try {
      if (row) {
        const d = await apiRead<any>(`/api/ops/store/items/${row.id}`)
        form.setFieldsValue({ title: d.title, description: d.description, subject: d.subject, grade: d.grade })
        setPicked(d.questionIds ?? [])
      } else form.resetFields()
      setEditing(row ? { id: row.id } : {})
    } catch (e: any) { message.error(e?.message || '加载题库详情失败') }
  }

  const saveItem = async () => {
    try {
      const v = await form.validateFields()
      if (picked.length === 0) { message.warning('请至少选择 1 道题目'); return }
      if (picked.length > 200) { message.warning('单个题库最多 200 道题目'); return }
      const body = { ...v, questionIds: picked }
      const okFlag = editing?.id
        ? await post(`/api/ops/store/items/${editing.id}`, body, 'PUT')
        : await post('/api/ops/store/items', body)
      if (okFlag) setEditing(null)
    } catch (e: any) { if (!e?.errorFields) message.error(e?.message || '保存失败') }
  }

  const loadPool = useCallback(async (p = 1, kw = '') => {
    try {
      const qs = new URLSearchParams({ page: String(p), size: '10', status: '1' })
      if (kw) qs.set('keyword', kw) // 后端已支持题干关键词
      const j = await apiRead<{ total: number; list: any[] }>(`/api/ops/questions?${qs}`)
      setPool({ total: j.total, list: j.list })
    } catch (e: any) { message.error(e?.message || '题目池加载失败') }
  }, [message])

  useEffect(() => { if (pickerOpen) loadPool(poolPage, poolKw) }, [pickerOpen, poolPage, poolKw, loadPool])

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'title', ellipsis: true },
    { title: '科目/年级', width: 110, render: (_, r) => `${subjectName(r.subject)} / ${r.grade}年级` },
    { title: '题量', dataIndex: 'questionCount', width: 70 },
    { title: '标签', dataIndex: 'tags', width: 160, render: (t: string[]) =>
      t?.length ? t.map((x) => <Tag key={x}>{x}</Tag>) : '-' },
    { title: '下载量', dataIndex: 'downloadCount', width: 80, sorter: (a, b) => a.downloadCount - b.downloadCount },
    { title: '状态', dataIndex: 'status', width: 90, render: (v) => {
        const s = STORE_STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '审核信息', dataIndex: 'reviewReason', ellipsis: true, render: (v, r) =>
      v ? <span style={{ color: '#B3413A' }}>{v}</span> : (r.publishedAt ? <span>上架于 {r.publishedAt}</span> : '-') },
    { title: '操作', width: 250, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        {[0, 3, 4].includes(r.status) && <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>}
        {[0, 3, 4].includes(r.status) && (
          <Popconfirm title="提交审核？" onConfirm={() => post(`/api/ops/store/items/${r.id}/submit`)}>
            <Button size="small" type="link">提交审核</Button>
          </Popconfirm>)}
        {r.status === 1 && <Button size="small" type="link" onClick={() => setReviewing(r)}>审核</Button>}
        {r.status === 2 && (
          <Popconfirm title="下架后家长端即时不可见，确认？" onConfirm={() => post(`/api/ops/store/items/${r.id}/offline`)}>
            <Button size="small" type="link" danger>下架</Button>
          </Popconfirm>)}
        <Button size="small" type="link" onClick={() => openView(r.id)}>查看</Button>
        <Button size="small" type="link" onClick={async () => {
          try {
            const d = await apiRead<any>(`/api/ops/store/items/${r.id}`)
            setTagging({ id: r.id, current: d.tagIds ?? [] }); setCheckedTags(d.tagIds ?? [])
          } catch (e: any) { message.error(e?.message || '加载标签失败') }
        }}>打标签</Button>
        <Popconfirm title="逻辑删除后家长端不可见，确认？" onConfirm={() => post(`/api/ops/store/items/${r.id}`, undefined, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  const poolCols: ColumnsType<any> = [
    { title: '', width: 40, render: (_, q) => (
      <Checkbox checked={picked.includes(q.id)}
        onChange={(e) => setPicked(e.target.checked ? [...picked, q.id] : picked.filter((x) => x !== q.id))} />
    ) },
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '科目', dataIndex: 'subject', width: 70, render: (v) => subjectName(v as number) },
    { title: '年级', dataIndex: 'grade', width: 60 },
  ]

  const filtered = !!(filters.status !== undefined || filters.subject !== undefined || filters.grade !== undefined || filters.keyword)
  const reset = () => { setFilters({}); setPage(1); load(1, {}) }

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Select placeholder="状态" allowClear style={{ width: 110 }} value={filters.status}
          options={Object.entries(STORE_STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))}
          onChange={(v) => { setFilters({ ...filters, status: v }); setPage(1) }} />
        <Select placeholder="科目" allowClear style={{ width: 110 }} value={filters.subject}
          options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))}
          onChange={(v) => { setFilters({ ...filters, subject: v }); setPage(1) }} />
        <Select placeholder="年级" allowClear style={{ width: 110 }} value={filters.grade}
          options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))}
          onChange={(v) => { setFilters({ ...filters, grade: v }); setPage(1) }} />
        <Input.Search placeholder="按名称搜索" allowClear style={{ width: 200 }}
          onSearch={(v) => { setFilters({ ...filters, keyword: v || undefined }); setPage(1) }} />
        {filtered && <Button size="small" onClick={reset}>重置</Button>}
        <Button type="primary" onClick={() => openEdit()}>新建题库</Button>
        <Button onClick={() => setNewTagOpen(true)}>新建标签</Button>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('题库商店', data.list, [
          { title: 'ID', key: 'id' }, { title: '名称', key: 'title' }, { title: '简介', key: 'description' },
          { title: '科目', key: 'subject', render: (v: number) => subjectName(v) },
          { title: '年级', key: 'grade' }, { title: '题量', key: 'questionCount' },
          { title: '下载量', key: 'downloadCount' },
          { title: '状态', key: 'status', render: (v: number) => STORE_STATUS[v]?.text ?? v },
          { title: '标签', key: 'tags', render: (v: string[]) => (v || []).join('、') },
          { title: '创建时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} size="small" loading={loading} scroll={{ x: 1240 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]) }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={filtered ? '没有匹配的题库' : '题库商店还是空的'}
              desc={filtered ? '调整筛选条件后重试' : '新建题包并上架，家长端即可下载使用'}
              onReset={filtered ? reset : undefined} onCreate={() => openEdit()} createText="新建题库" />
          ) }} />
      </div>

      <Modal title={editing?.id ? '编辑题库' : '新建题库'} open={!!editing} onOk={saveItem} onCancel={() => setEditing(null)} width={720} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="title" label="名称" rules={[{ required: true, message: '请输入名称' }, { max: 64, message: '≤64字' }]}>
            <Input placeholder="题库名称" />
          </Form.Item>
          <Form.Item name="description" label="简介" rules={[{ max: 200, message: '≤200字' }]}>
            <Input.TextArea rows={2} placeholder="家长端展示的简介" />
          </Form.Item>
          <Space wrap>
            <Form.Item name="subject" label="科目" rules={[{ required: true, message: '必选' }]}>
              <Select placeholder="科目" style={{ width: 140 }} options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))} />
            </Form.Item>
            <Form.Item name="grade" label="年级" rules={[{ required: true, message: '必选' }]}>
              <Select placeholder="年级" style={{ width: 140 }} options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))} />
            </Form.Item>
          </Space>
          <Form.Item label={`题目（已选 ${picked.length}/200）`} required>
            <Button onClick={() => { setPoolPage(1); setPoolKw(''); setPickerOpen(true) }}>选择题目</Button>
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="选择内置题目（上架且机审通过）" open={pickerOpen} onCancel={() => setPickerOpen(false)}
        onOk={() => setPickerOpen(false)} width={760} okText="完成">
        <Space style={{ marginBottom: 8 }}>
          <Input.Search placeholder="题干关键词" allowClear style={{ width: 240 }}
            onSearch={(v) => { setPoolPage(1); setPoolKw(v) }} />
          <Badge count={picked.length}><Button size="small">已选</Button></Badge>
        </Space>
        <div className="ops-table-wrap">
          <Table rowKey="id" columns={poolCols} dataSource={pool.list} size="small"
            pagination={{ current: poolPage, total: pool.total, pageSize: 10, onChange: setPoolPage, showSizeChanger: false }} />
        </div>
      </Modal>

      <Modal title={`审核：${reviewing?.title ?? ''}`} open={!!reviewing} onCancel={() => setReviewing(null)} footer={null}>
        {reviewing?.status === 1 && <p>提交的题量：{reviewing.questionCount}</p>}
        <Space direction="vertical" style={{ width: '100%' }}>
          <Button type="primary" block onClick={async () => {
            if (await post(`/api/ops/store/items/${reviewing.id}/review`, { pass: true })) setReviewing(null)
          }}>通过并上架</Button>
          <Button danger block onClick={() => {
            let reason = ''
            Modal.confirm({
              title: '驳回原因', content: <Input.TextArea rows={3} placeholder="请填写驳回原因（必填）"
                onChange={(e) => { reason = e.target.value }} />,
              onOk: async () => {
                if (!reason.trim()) { message.error('请填写驳回原因'); return Promise.reject() }
                if (await post(`/api/ops/store/items/${reviewing.id}/review`, { pass: false, reason: reason.trim() })) setReviewing(null)
              }, okText: '确认驳回', cancelText: '取消',
            })
          }}>驳回</Button>
        </Space>
      </Modal>

      <Modal title="打标签（最多5个）" open={!!tagging} onCancel={() => setTagging(null)} onOk={async () => {
        const ids = checkedTags.slice(0, 5)
        if (await post(`/api/ops/store/items/${tagging!.id}/tags`, { tagIds: ids }, 'PUT')) setTagging(null)
      }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {tags.map((t) => (
            <label key={t.id} style={{ cursor: 'pointer' }}>
              <Checkbox checked={checkedTags.includes(t.id)}
                onChange={(e) => {
                  const next = e.target.checked ? [...checkedTags, t.id] : checkedTags.filter((x) => x !== t.id)
                  if (next.length > 5) { message.warning('最多选择 5 个标签'); return }
                  setCheckedTags(next)
                }} />{' '}
              <Tag color={t.color}>{t.name}</Tag>
            </label>
          ))}
          {tags.length === 0 && <span>暂无标签，请先新建</span>}
        </div>
      </Modal>

      <Modal title="新建标签" open={newTagOpen} onCancel={() => setNewTagOpen(false)} onOk={async () => {
        const v = await tagForm.validateFields()
        if (await post('/api/ops/store/tags', v)) { setNewTagOpen(false); tagForm.resetFields(); loadTags() }
      }} destroyOnHidden>
        <Form form={tagForm} layout="vertical">
          <Form.Item name="name" label="标签名" rules={[{ required: true, message: '请输入标签名' }, { max: 16, message: '≤16字' }]}>
            <Input placeholder="如：期末冲刺" />
          </Form.Item>
          <Form.Item name="color" label="颜色" initialValue="blue">
            <Select options={TAG_COLORS.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title={`题库详情 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={560}>
        <Spin spinning={viewLoading}>
          {view && (
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="名称">{view.title}</Descriptions.Item>
              <Descriptions.Item label="简介">{view.description || '-'}</Descriptions.Item>
              <Descriptions.Item label="科目/年级">{subjectName(view.subject)} / {view.grade}年级</Descriptions.Item>
              <Descriptions.Item label="题量">{view.questionCount}</Descriptions.Item>
              <Descriptions.Item label="下载量">{view.downloadCount}</Descriptions.Item>
              <Descriptions.Item label="状态">{(STORE_STATUS[view.status as number] ?? { text: view.status }).text}</Descriptions.Item>
              <Descriptions.Item label="标签">
                {view.tagIds?.length
                  ? view.tagIds.map((id: number) => {
                      const t = tags.find((x) => x.id === id)
                      return <Tag key={id} color={t?.color}>{t?.name ?? id}</Tag>
                    })
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">{view.createdAt || '-'}</Descriptions.Item>
              {view.reviewReason && <Descriptions.Item label="审核信息">{view.reviewReason}</Descriptions.Item>}
            </Descriptions>
          )}
        </Spin>
      </Drawer>
    </Card>
  )
}
