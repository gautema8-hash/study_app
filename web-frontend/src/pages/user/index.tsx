import { useEffect, useState, useCallback, useRef } from 'react'
import { App, Table, Tag, Button, Space, Popconfirm, Modal, Form, Input, Drawer, Descriptions, Switch, Select, Card } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { TIER_NAMES } from '../../components/names'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

// W2 用户管理：搜索 / 筛选 / 批量删除 / 导出 / 完整 CRUD
export default function UserManage() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [tier, setTier] = useState<number | undefined>()
  const [status, setStatus] = useState<number | undefined>()
  const [selected, setSelected] = useState<number[]>([])

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)
  const viewSeq = useRef(0) // 详情请求序号，防止快速切换行时旧响应覆盖新数据

  const { message } = App.useApp()

  const load = useCallback(async (p: number, kw: string, t?: number, s?: number) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (kw.trim()) qs.set('keyword', kw.trim())
      if (t !== undefined) qs.set('tier', String(t))
      if (s !== undefined) qs.set('status', String(s))
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/users?${qs}`))
    } catch (e: any) { message.error(e?.message || '用户列表加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, keyword, tier, status) }, [page, tier, status, load])

  const reload = () => load(page, keyword, tier, status)

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiWrite(url, method, body, apiFetch)
    if (!r.ok) { message.error(r.msg); return false }
    message.success('操作成功'); setSelected([]); reload(); return true
  }

  const batchDelete = async () => {
    const r = await apiWrite('/api/ops/insight/batch-delete/users', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 个家庭`); setSelected([]); reload()
  }

  const openEdit = (row?: any) => {
    if (row) form.setFieldsValue({ kyc: row.kyc, status: row.status })
    else form.resetFields()
    setEditing(row ? { id: row.familyId } : {})
  }

  const save = async () => {
    setSaving(true)
    try {
      const v = await form.validateFields()
      const ok = editing?.id
        ? await post(`/api/ops/users/${editing.id}`, { kyc: v.kyc ? 1 : 0, status: v.status }, 'PUT')
        : await post('/api/ops/users', { phone: v.phone, password: v.password, kyc: v.kyc ? 1 : 0 })
      if (ok) setEditing(null)
    } catch (e: any) {
      // 表单校验失败由 antd 内联提示，不额外弹全局错误
      if (e?.errorFields) return
      message.error(e?.message || '保存失败')
    } finally { setSaving(false) }
  }

  const openView = async (id: number) => {
    const seq = ++viewSeq.current
    setViewLoading(true); setView({ id })
    try {
      const d = await apiRead<any>(`/api/ops/users/${id}`)
      if (seq !== viewSeq.current) return // 已被更新的请求取代，丢弃旧响应
      setView(d)
    } catch (e: any) { if (seq === viewSeq.current) message.error(e?.message || '加载详情失败') }
    finally { if (seq === viewSeq.current) setViewLoading(false) }
  }

  const cols: ColumnsType<any> = [
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '手机号(脱敏)', dataIndex: 'phone' },
    { title: '实名核验', dataIndex: 'kyc', render: (v) => (v ? <Tag color="success">已核验</Tag> : <Tag>未核验</Tag>) },
    { title: '会员', dataIndex: 'tier', render: (v) => TIER_NAMES[v as number] ?? v },
    { title: '状态', dataIndex: 'status', width: 80, render: (v) => v === 0 ? <Tag color="error">停用</Tag> : <Tag color="success">正常</Tag> },
    { title: '到期', dataIndex: 'expireAt', render: (v) => (v?.startsWith('9999') ? '永久' : v ?? '-') },
    { title: '学生数', dataIndex: 'students', width: 80 },
    { title: '注册时间', dataIndex: 'createdAt' },
    { title: '操作', width: 180, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.familyId)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Popconfirm title="逻辑删除该家庭？" description="删除后家长端不可登录，数据仍保留" onConfirm={() => post(`/api/ops/users/${r.familyId}`, {}, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  const filtered = !!(keyword || tier !== undefined || status !== undefined)

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Input.Search placeholder="手机号 / 家庭ID" allowClear style={{ width: 220 }} value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={(v) => { setKeyword(v); setPage(1); load(1, v, tier, status) }} />
        <Select placeholder="会员等级" allowClear style={{ width: 130 }} value={tier}
          options={Object.entries(TIER_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
          onChange={(v) => { setTier(v); setPage(1) }} />
        <Select placeholder="账号状态" allowClear style={{ width: 120 }} value={status}
          options={[{ value: 1, label: '正常' }, { value: 0, label: '停用' }]}
          onChange={(v) => { setStatus(v); setPage(1) }} />
        {filtered && <Button size="small" onClick={() => { setKeyword(''); setTier(undefined); setStatus(undefined); setPage(1); load(1, '', undefined, undefined) }}>重置</Button>}
        <Button type="primary" onClick={() => openEdit()}>新增家庭</Button>
        <Button onClick={reload}>刷新</Button>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('用户列表', data.list, [
          { title: '家庭ID', key: 'familyId' }, { title: '手机号', key: 'phone' },
          { title: '实名核验', key: 'kyc', render: (v: boolean) => v ? '已核验' : '未核验' },
          { title: '会员', key: 'tier', render: (v: number) => TIER_NAMES[v] ?? v },
          { title: '状态', key: 'status', render: (v: number) => v === 0 ? '停用' : '正常' },
          { title: '学生数', key: 'students' }, { title: '注册时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="familyId" columns={cols} dataSource={data.list} loading={loading} size="small"
          scroll={{ x: 1080 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]) }}
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: (
            <EmptyState title={filtered ? '没有匹配的用户' : '还没有注册家庭'}
              desc={filtered ? '换个关键词或重置筛选试试' : '可以在此代家长创建家庭账号'}
              onReset={filtered ? () => { setKeyword(''); setTier(undefined); setStatus(undefined); setPage(1); load(1, '', undefined, undefined) } : undefined}
              onCreate={() => openEdit()} createText="新增家庭" />
          ) }} />
      </div>

      <Modal title={editing?.id ? '编辑家庭' : '新增家庭'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={460} destroyOnHidden>
        <Form form={form} layout="vertical">
          {!editing?.id && (
            <Form.Item name="phone" label="手机号" rules={[{ required: true, message: '请输入手机号' }, { pattern: /^1\d{10}$/, message: '请输入 11 位有效手机号' }]}>
              <Input placeholder="11 位手机号" maxLength={11} />
            </Form.Item>
          )}
          {!editing?.id && (
            <Form.Item name="password" label="初始化密码" rules={[{ required: true, message: '请设置初始密码' }, { min: 6, message: '密码至少 6 位' }]}>
              <Input.Password placeholder="家长首次登录密码 ≥6位" />
            </Form.Item>
          )}
          <Form.Item name="kyc" label="实名核验" valuePropName="checked"><Switch /></Form.Item>
          {editing?.id && (
            <Form.Item name="status" label="状态" rules={[{ required: true }]}>
              <Select options={[{ value: 1, label: '正常' }, { value: 0, label: '停用' }]} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Drawer title={`家庭详情 #${view?.familyId ?? view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={440}>
        {viewLoading ? <p>加载中…</p> : view && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="家庭ID">{view.familyId}</Descriptions.Item>
            <Descriptions.Item label="手机号">{view.phone}</Descriptions.Item>
            <Descriptions.Item label="实名核验">{view.kyc ? '已核验' : '未核验'}</Descriptions.Item>
            <Descriptions.Item label="状态">{view.status === 1 ? '正常' : '停用'}</Descriptions.Item>
            <Descriptions.Item label="注册时间">{view.createdAt}</Descriptions.Item>
            <Descriptions.Item label="学生档案">
              {view.students?.length ? (
                <ul style={{ margin: 0, paddingLeft: 16 }}>
                  {view.students.map((s: any) => <li key={s.id}>{s.nickname}（{s.grade}年级）</li>)}
                </ul>
              ) : '暂无'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </Card>
  )
}
