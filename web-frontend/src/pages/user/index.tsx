import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import {
  Table, Tag, message, Button, Space, Popconfirm, Modal, Form, Input, Drawer, Descriptions, Switch, Select,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { TIER_NAMES } from '../../components/names'

// W2 用户管理（FR-W04：手机号脱敏展示；后台可代建/编辑/逻辑删除）
export default function UserManage() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const [view, setView] = useState<any>(null)
  const [viewLoading, setViewLoading] = useState(false)

  const load = useCallback(async (p: number) => {
    try {
      const r = await apiFetch(`/api/ops/users?page=${p}&size=20`)
      const j = await r.json()
      setData(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load(page) }, [page, load])

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiFetch(url, {
      method, headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (r.status === 400 || r.status === 403) { message.error((await r.json()).message ?? '操作失败'); return false }
    message.success('操作成功'); load(page); return true
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
    } finally { setSaving(false) }
  }

  const openView = async (id: number) => {
    setViewLoading(true); setView({ id })
    try {
      const r = await apiFetch(`/api/ops/users/${id}`)
      const j = await r.json()
      setView(j.data)
    } catch { message.error('加载详情失败') }
    finally { setViewLoading(false) }
  }

  const cols: ColumnsType<any> = [
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '手机号(脱敏)', dataIndex: 'phone' },
    { title: '实名核验', dataIndex: 'kyc', render: (v) => (v ? <Tag color="success">已核验</Tag> : <Tag>未核验</Tag>) },
    { title: '会员', dataIndex: 'tier', render: (v) => TIER_NAMES[v as number] ?? v },
    { title: '到期', dataIndex: 'expireAt', render: (v) => (v?.startsWith('9999') ? '永久' : v ?? '-') },
    { title: '学生数', dataIndex: 'students', width: 80 },
    { title: '注册时间', dataIndex: 'createdAt' },
    { title: '操作', width: 180, render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => openView(r.familyId)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Popconfirm title="逻辑删除该家庭？" onConfirm={() => post(`/api/ops/users/${r.familyId}`, {}, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]
  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={() => openEdit()}>新增家庭</Button>
      </Space>
      <Table
        rowKey="familyId" columns={cols} dataSource={data.list}
        pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
        size="small"
      />

      <Modal title={editing?.id ? '编辑家庭' : '新增家庭'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={460}>
        <Form form={form} layout="vertical">
          {!editing?.id && (
            <Form.Item name="phone" label="手机号" rules={[{ required: true, message: '必填' }, { pattern: /^1\d{10}$/, message: '手机号格式' }]}>
              <Input placeholder="11 位手机号" />
            </Form.Item>
          )}
          {!editing?.id && (
            <Form.Item name="password" label="初始化密码" rules={[{ required: true, message: '必填' }, { min: 6, message: '≥6位' }]}>
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

      <Drawer title={`家庭详情 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={440}>
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
    </div>
  )
}
