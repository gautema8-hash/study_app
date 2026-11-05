import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import {
  Table, Tag, Select, Space, Button, Input, Modal, Form, message, Popconfirm, Badge, Checkbox, Drawer, Descriptions, Spin,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { SUBJECTS, subjectName, STORE_STATUS, TAG_COLORS } from '../../components/names'

// 题库商店运营（PRD FR-S01~S07）：录入→提交→审核→上架/下架→打标签
export default function BankStore() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [filters, setFilters] = useState<{ status?: number; subject?: number; grade?: number; keyword?: string }>({})
  const [tags, setTags] = useState<any[]>([])

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [pickerOpen, setPickerOpen] = useState(false)
  const [picked, setPicked] = useState<number[]>([])
  const [pool, setPool] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [poolPage, setPoolPage] = useState(1)
  const [poolKw, setPoolKw] = useState('')

  const [reviewing, setReviewing] = useState<any>(null)
  const [tagging, setTagging] = useState<null | { id: number; current: number[] }>(null)
  const [tagForm] = Form.useForm()
  const [newTagOpen, setNewTagOpen] = useState(false)
  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)

  const load = useCallback(async (p = page, f = filters) => {
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (f.status !== undefined) qs.set('status', String(f.status))
      if (f.subject !== undefined) qs.set('subject', String(f.subject))
      if (f.grade !== undefined) qs.set('grade', String(f.grade))
      if (f.keyword) qs.set('keyword', f.keyword)
      const r = await apiFetch(`/api/ops/store/items?${qs}`)
      setData((await r.json()).data)
    } catch { message.error('后端未连接') }
  }, [page, filters])

  const loadTags = useCallback(async () => {
    try {
      const r = await apiFetch('/api/ops/store/tags')
      setTags((await r.json()).data.list)
    } catch { /* 忽略 */ }
  }, [])

  useEffect(() => { load() }, [page, load])
  useEffect(() => { loadTags() }, [loadTags])

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiFetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    if (r.status === 400 || r.status === 403) { message.error((await r.json()).message ?? '操作失败'); return false }
    message.success('操作成功'); load(); return true
  }

  const openView = async (id: number) => {
    setViewLoading(true); setView({ id })
    try {
      const res = await apiFetch(`/api/ops/store/items/${id}`)
      setView((await res.json()).data)
    } catch { message.error('加载详情失败') }
    finally { setViewLoading(false) }
  }

  // ---------- 新建/编辑 ----------
  const openEdit = async (row?: any) => {
    setPicked([])
    if (row) {
      const r = await apiFetch(`/api/ops/store/items/${row.id}`)
      const d = (await r.json()).data
      form.setFieldsValue({ title: d.title, description: d.description, subject: d.subject, grade: d.grade })
      setPicked(d.questionIds)
    } else form.resetFields()
    setEditing(row ? { id: row.id } : {})
  }

  const saveItem = async () => {
    const v = await form.validateFields()
    if (picked.length === 0) { message.warning('请至少选择 1 道题目'); return }
    const body = { ...v, questionIds: picked }
    const okFlag = editing?.id
      ? await post(`/api/ops/store/items/${editing.id}`, body, 'PUT')
      : await post('/api/ops/store/items', body)
    if (okFlag) setEditing(null)
  }

  const loadPool = useCallback(async (p = 1, kw = '') => {
    try {
      const qs = new URLSearchParams({ page: String(p), size: '10', status: '1' })
      if (kw) qs.set('keyword', kw) // /api/ops/questions 无 keyword 参数，前端二次过滤题干
      const r = await apiFetch(`/api/ops/questions?${qs}`)
      const j = await r.json()
      const list = kw ? j.data.list.filter((q: any) => q.stem?.includes(kw)) : j.data.list
      setPool({ total: kw ? list.length : j.data.total, list })
    } catch { message.error('题目池加载失败') }
  }, [])

  useEffect(() => { if (pickerOpen) loadPool(poolPage, poolKw) }, [pickerOpen, poolPage, poolKw, loadPool])

  // ---------- 渲染 ----------
  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'title', ellipsis: true },
    { title: '科目/年级', width: 110, render: (_, r) => `${subjectName(r.subject)} / ${r.grade}年级` },
    { title: '题量', dataIndex: 'questionCount', width: 70 },
    { title: '标签', dataIndex: 'tags', width: 160, render: (tags: string[]) =>
      tags?.length ? tags.map((t) => <Tag key={t}>{t}</Tag>) : '-' },
    { title: '下载量', dataIndex: 'downloadCount', width: 80, sorter: (a, b) => a.downloadCount - b.downloadCount },
    { title: '状态', dataIndex: 'status', width: 90, render: (v) => {
        const s = STORE_STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '审核信息', dataIndex: 'reviewReason', ellipsis: true, render: (v, r) =>
      v ? <span style={{ color: '#B3413A' }}>{v}</span> : (r.publishedAt ? <span>上架于 {r.publishedAt}</span> : '-') },
    { title: '操作', width: 250, render: (_, r) => (
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
          const res = await apiFetch(`/api/ops/store/items/${r.id}`)
          const d = (await res.json()).data
          setTagging({ id: r.id, current: d.tagIds })
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

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select placeholder="状态" allowClear style={{ width: 110 }} value={filters.status}
          options={Object.entries(STORE_STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))}
          onChange={(v) => { setFilters({ ...filters, status: v }); setPage(1) }} />
        <Select placeholder="科目" allowClear style={{ width: 110 }} value={filters.subject}
          options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))}
          onChange={(v) => { setFilters({ ...filters, subject: v }); setPage(1) }} />
        <Select placeholder="年级" allowClear style={{ width: 110 }} value={filters.grade}
          options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))}
          onChange={(v) => { setFilters({ ...filters, grade: v }); setPage(1) }} />
        <Input.Search placeholder="按名称搜索" allowClear style={{ width: 220 }}
          onSearch={(v) => { setFilters({ ...filters, keyword: v || undefined }); setPage(1) }} />
        <Button type="primary" onClick={() => openEdit()}>新建题库</Button>
        <Button onClick={() => setNewTagOpen(true)}>新建标签</Button>
      </Space>
      <Table rowKey="id" columns={cols} dataSource={data.list} size="small"
        pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }} />

      {/* 新建/编辑题库 */}
      <Modal title={editing?.id ? '编辑题库' : '新建题库'} open={!!editing} onOk={saveItem} onCancel={() => setEditing(null)} width={720}>
        <Form form={form} layout="vertical">
          <Form.Item name="title" label="名称" rules={[{ required: true, message: '必填' }, { max: 64, message: '≤64字' }]}>
            <Input placeholder="题库名称" />
          </Form.Item>
          <Form.Item name="description" label="简介" rules={[{ max: 200, message: '≤200字' }]}>
            <Input.TextArea rows={2} placeholder="家长端展示的简介" />
          </Form.Item>
          <Space>
            <Form.Item name="subject" label="科目" rules={[{ required: true, message: '必选' }]}>
              <Select placeholder="科目" style={{ width: 140 }}
                options={SUBJECTS.map((s, i) => ({ value: i + 1, label: s }))} />
            </Form.Item>
            <Form.Item name="grade" label="年级" rules={[{ required: true, message: '必选' }]}>
              <Select placeholder="年级" style={{ width: 140 }}
                options={Array.from({ length: 12 }, (_, i) => ({ value: i + 1, label: `${i + 1} 年级` }))} />
            </Form.Item>
          </Space>
          <Form.Item label={`题目（已选 ${picked.length}/200）`} required>
            <Button onClick={() => { setPoolPage(1); setPoolKw(''); setPickerOpen(true) }}>选择题目</Button>
          </Form.Item>
        </Form>
      </Modal>

      {/* 内置题目池选择器 */}
      <Modal title="选择内置题目（上架且机审通过）" open={pickerOpen} onCancel={() => setPickerOpen(false)}
        onOk={() => setPickerOpen(false)} width={760} okText="完成">
        <Space style={{ marginBottom: 8 }}>
          <Input.Search placeholder="题干关键词" allowClear style={{ width: 240 }}
            onSearch={(v) => { setPoolPage(1); setPoolKw(v) }} />
          <Badge count={picked.length}> <Button size="small">已选</Button></Badge>
        </Space>
        <Table rowKey="id" columns={poolCols} dataSource={pool.list} size="small"
          pagination={{ current: poolPage, total: pool.total, pageSize: 10, onChange: setPoolPage }} />
      </Modal>

      {/* 审核裁决 */}
      <Modal title={`审核：${reviewing?.title ?? ''}`} open={!!reviewing} onCancel={() => setReviewing(null)} footer={null}>
        {reviewing?.reviewReason && reviewing.status === 1 && <p>提交的题量：{reviewing.questionCount}</p>}
        <Space direction="vertical" style={{ width: '100%' }}>
          <Button type="primary" block onClick={async () => {
            if (await post(`/api/ops/store/items/${reviewing.id}/review`, { pass: true })) setReviewing(null)
          }}>通过并上架</Button>
          <Button danger block onClick={async () => {
            const reason = window.prompt('驳回原因（必填）')
            if (!reason) return
            if (await post(`/api/ops/store/items/${reviewing.id}/review`, { pass: false, reason })) setReviewing(null)
          }}>驳回</Button>
        </Space>
      </Modal>

      {/* 打标签 */}
      <Modal title="打标签（最多5个）" open={!!tagging} onCancel={() => setTagging(null)} onOk={async () => {
        const checked = Array.from(document.querySelectorAll<HTMLInputElement>('#tag-picker input:checked')).map((i) => Number(i.value))
        if (await post(`/api/ops/store/items/${tagging!.id}/tags`, { tagIds: checked }, 'PUT')) setTagging(null)
      }}>
        <div id="tag-picker" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {tags.map((t) => (
            <label key={t.id} style={{ cursor: 'pointer' }}>
              <input type="checkbox" value={t.id} defaultChecked={tagging?.current.includes(t.id)} />{' '}
              <Tag color={t.color}>{t.name}</Tag>
            </label>
          ))}
          {tags.length === 0 && <span>暂无标签，请先新建</span>}
        </div>
      </Modal>

      {/* 新建标签 */}
      <Modal title="新建标签" open={newTagOpen} onCancel={() => setNewTagOpen(false)} onOk={async () => {
        const v = await tagForm.validateFields()
        if (await post('/api/ops/store/tags', v)) { setNewTagOpen(false); tagForm.resetFields(); loadTags() }
      }}>
        <Form form={tagForm} layout="vertical">
          <Form.Item name="name" label="标签名" rules={[{ required: true, message: '必填' }, { max: 16, message: '≤16字' }]}>
            <Input placeholder="如：期末冲刺" />
          </Form.Item>
          <Form.Item name="color" label="颜色" initialValue="blue">
            <Select options={TAG_COLORS.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
          </Form>
      </Modal>

      {/* 查看详情 */}
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
    </div>
  )
}
