import { useEffect, useState, useCallback } from 'react'
import { App, Table, Tag, Button, Modal, Form, Input, Select, Space, Popconfirm, Drawer, Descriptions, Card } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch, getToken } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { EmptyState, BatchBar, exportCsv } from '../../components/common'

const ROLES: Record<string, { text: string; color: string }> = {
  ADMIN: { text: '超级管理员', color: 'red' }, REVIEWER: { text: '审核员', color: 'blue' },
  CS: { text: '客服', color: 'green' }, FINANCE: { text: '财务', color: 'gold' },
  VIEWER: { text: '只读', color: 'default' }, OPS: { text: '运营', color: 'purple' },
}
const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '停用', color: 'default' }, 1: { text: '正常', color: 'success' },
}

function currentAdminId(): number {
  try {
    const t = getToken()
    if (!t) return 0
    const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return Number(String(p.sub || '').replace('family:', '')) || 0
  } catch { return 0 }
}

// W10 权限管理：管理员 CRUD + 重置密码 + 删除自我保护 + 批量删除 + 导出
export default function Iam() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [selected, setSelected] = useState<number[]>([])
  const [loading, setLoading] = useState(false)
  const selfId = currentAdminId()

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const [resetOf, setResetOf] = useState<null | { id: number; username: string }>(null)
  const [resetForm] = Form.useForm()
  const [view, setView] = useState<any>(null)
  const { message } = App.useApp()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const d = await apiRead<{ list: any[] }>('/api/ops/iam/admins')
      setData({ total: d.list.length, list: d.list })
    } catch (e: any) { if (e?.message !== '登录已过期') message.error(e?.message || '管理员列表加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load() }, [load])

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiWrite(url, method, body, apiFetch)
    if (!r.ok) { message.error(r.msg); return false }
    message.success('操作成功'); setSelected([]); load(); return true
  }

  const batchDelete = async () => {
    if (selected.includes(selfId)) { message.warning('选中项包含当前登录账号，不能删除自己'); return }
    const r = await apiWrite('/api/ops/insight/batch-delete/admins', 'POST', { ids: selected }, apiFetch)
    if (!r.ok) { message.error(r.msg); return }
    message.success(`已删除 ${r.data?.deleted ?? selected.length} 个管理员`); setSelected([]); load()
  }

  const openEdit = (row?: any) => {
    if (row) form.setFieldsValue({ username: row.username, role: row.role, status: row.status, password: undefined })
    else form.resetFields()
    setEditing(row ? { id: row.id } : {})
  }

  const save = async () => {
    setSaving(true)
    try {
      const v = await form.validateFields()
      const ok = editing?.id
        ? await post(`/api/ops/iam/admins/${editing.id}`, { role: v.role, status: v.status, ...(v.password ? { password: v.password } : {}) }, 'PUT')
        : await post('/api/ops/iam/admins', { username: v.username, password: v.password, role: v.role, status: 1 })
      if (ok) setEditing(null)
    } catch (e: any) { if (!e?.errorFields) message.error(e?.message || '保存失败') }
    finally { setSaving(false) }
  }

  const resetPwd = async () => {
    try {
      const v = await resetForm.validateFields()
      if (await post(`/api/ops/iam/admins/${resetOf!.id}/reset`, { password: v.password }, 'PUT')) {
        setResetOf(null); resetForm.resetFields()
      }
    } catch (e: any) { if (!e?.errorFields) message.error(e?.message || '重置失败') }
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '用户名', dataIndex: 'username', render: (v, r) => <Space size={4}>{v}{r.id === selfId && <Tag color="cyan">当前账号</Tag>}</Space> },
    { title: '角色', dataIndex: 'role', width: 120, render: (v) => {
        const r = ROLES[v as string] ?? { text: v, color: 'default' }
        return <Tag color={r.color}>{r.text}</Tag>
      } },
    { title: '状态', dataIndex: 'status', width: 90, render: (v) => {
        const s = STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '创建时间', dataIndex: 'createdAt', width: 170 },
    { title: '操作', width: 230, fixed: 'right', render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => setView(r)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Button size="small" type="link" onClick={() => { resetForm.resetFields(); setResetOf({ id: r.id, username: r.username }) }}>重置密码</Button>
        <Popconfirm title={r.id === selfId ? '不能删除当前登录的账号' : '确认停用该管理员？'}
          onConfirm={() => post(`/api/ops/iam/admins/${r.id}`, {}, 'DELETE')} disabled={r.id === selfId}>
          <Button size="small" type="link" danger disabled={r.id === selfId}>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  return (
    <Card size="small">
      <div className="ops-toolbar">
        <Button type="primary" onClick={() => openEdit()}>新增管理员</Button>
        <Button size="small" onClick={load}>刷新</Button>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>为安全起见，系统禁止删除当前登录账号与最后一个超级管理员</span>
      </div>

      <BatchBar count={selected.length} onDelete={batchDelete} onClear={() => setSelected([])}
        onExport={() => exportCsv('管理员列表', data.list, [
          { title: 'ID', key: 'id' }, { title: '用户名', key: 'username' },
          { title: '角色', key: 'role', render: (v: string) => ROLES[v]?.text ?? v },
          { title: '状态', key: 'status', render: (v: number) => STATUS[v]?.text ?? v },
          { title: '创建时间', key: 'createdAt' },
        ])} />

      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} loading={loading} size="small" scroll={{ x: 980 }}
          rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as number[]),
            getCheckboxProps: (r) => ({ disabled: r.id === selfId }) }}
          locale={{ emptyText: <EmptyState title="暂无管理员" desc="新增管理员以分配后台权限" onCreate={() => openEdit()} createText="新增管理员" /> }} />
      </div>

      <Modal title={editing?.id ? '编辑管理员' : '新增管理员'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={480} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名" rules={editing?.id ? [] : [{ required: true, message: '请输入用户名' }, { max: 32, message: '不超过 32 位' }]}>
            <Input disabled={!!editing?.id} placeholder="登录用户名" />
          </Form.Item>
          <Form.Item name="password" label={editing?.id ? '新密码（留空不改）' : '初始化密码'}
            rules={editing?.id ? [{ min: 6, message: '密码至少 6 位' }] : [{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少 6 位' }]}>
            <Input.Password placeholder={editing?.id ? '留空则不修改' : '≥6位'} />
          </Form.Item>
          <Space wrap>
            <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
              <Select style={{ width: 160 }} options={Object.entries(ROLES).map(([k, v]) => ({ value: k, label: v.text }))} />
            </Form.Item>
            {editing?.id && (
              <Form.Item name="status" label="状态" rules={[{ required: true }]}>
                <Select style={{ width: 120 }} options={Object.entries(STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))} />
              </Form.Item>
            )}
          </Space>
        </Form>
      </Modal>

      <Modal title={`重置密码：${resetOf?.username ?? ''}`} open={!!resetOf} onOk={resetPwd} onCancel={() => setResetOf(null)} destroyOnHidden>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="password" label="新密码" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '密码至少 6 位' }]}>
            <Input.Password placeholder="≥6位" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title={`管理员详情 #${view?.id ?? ''}`} open={!!view} onClose={() => setView(null)} width={420}>
        {view && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{view.id}</Descriptions.Item>
            <Descriptions.Item label="用户名">{view.username}</Descriptions.Item>
            <Descriptions.Item label="角色">{ROLES[view.role]?.text ?? view.role}</Descriptions.Item>
            <Descriptions.Item label="状态">{STATUS[view.status]?.text ?? view.status}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{view.createdAt}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </Card>
  )
}
