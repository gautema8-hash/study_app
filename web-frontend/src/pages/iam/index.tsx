import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import {
  Table, Tag, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Drawer, Descriptions,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'

const ROLES: Record<string, { text: string; color: string }> = {
  ADMIN: { text: '超级管理员', color: 'red' },
  REVIEWER: { text: '审核员', color: 'blue' },
  CS: { text: '客服', color: 'green' },
}
const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '停用', color: 'default' },
  1: { text: '正常', color: 'success' },
}

// W10 权限管理（admin_user 增删改查 + 重置密码）
export default function Iam() {
  const [list, setList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)

  const [editing, setEditing] = useState<null | { id?: number }>(null)
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  const [resetOf, setResetOf] = useState<null | { id: number; username: string }>(null)
  const [resetForm] = Form.useForm()

  const [view, setView] = useState<any>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await apiFetch('/api/ops/iam/admins')
      const j = await r.json()
      setList(j.data.list)
    } catch (e: any) { if (e.message !== '登录已过期') message.error('加载失败') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const post = async (url: string, body?: any, method = 'POST') => {
    const r = await apiFetch(url, {
      method, headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (r.status === 400 || r.status === 403) { message.error((await r.json()).message ?? '操作失败'); return false }
    message.success('操作成功'); load(); return true
  }

  const openEdit = (row?: any) => {
    if (row) {
      form.setFieldsValue({ username: row.username, role: row.role, status: row.status })
    } else form.resetFields()
    setEditing(row ? { id: row.id } : {})
  }

  const save = async () => {
    setSaving(true)
    try {
      const v = await form.validateFields()
      const body = editing?.id
        ? { ...v, username: undefined }
        : v
      const ok = editing?.id
        ? await post(`/api/ops/iam/admins/${editing.id}`, { ...body, status: v.status }, 'PUT')
        : await post('/api/ops/iam/admins', { ...v, status: 1 })
      if (ok) setEditing(null)
    } finally { setSaving(false) }
  }

  const resetPwd = async () => {
    const v = await resetForm.validateFields()
    const ok = await post(`/api/ops/iam/admins/${resetOf!.id}/reset`, { password: v.password }, 'PUT')
    if (ok) setResetOf(null)
  }

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username' },
    { title: '角色', dataIndex: 'role', width: 120, render: (v) => {
        const r = ROLES[v as string] ?? { text: v, color: 'default' }
        return <Tag color={r.color}>{r.text}</Tag>
      } },
    { title: '状态', dataIndex: 'status', width: 90, render: (v) => {
        const s = STATUS[v as number] ?? { text: v, color: 'default' }
        return <Tag color={s.color}>{s.text}</Tag>
      } },
    { title: '创建时间', dataIndex: 'createdAt', width: 170 },
    { title: '操作', width: 220, render: (_, r) => (
      <Space size={4} wrap>
        <Button size="small" type="link" onClick={() => setView(r)}>查看</Button>
        <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
        <Button size="small" type="link" onClick={() => { resetForm.resetFields(); setResetOf({ id: r.id, username: r.username }) }}>重置密码</Button>
        <Popconfirm title="确认停用该管理员？" onConfirm={() => post(`/api/ops/iam/admins/${r.id}`, {}, 'DELETE')}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space>
    ) },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={() => openEdit()}>新增管理员</Button>
      </Space>
      <Table rowKey="id" columns={cols} dataSource={list} loading={loading} size="small" pagination={false} />

      <Modal title={editing?.id ? '编辑管理员' : '新增管理员'} open={!!editing} onOk={save} onCancel={() => setEditing(null)}
        confirmLoading={saving} width={480}>
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名" rules={editing?.id ? [] : [{ required: true, message: '必填' }]}>
            <Input disabled={!!editing?.id} placeholder="登录用户名" />
          </Form.Item>
          <Form.Item name="password" label={editing?.id ? '新密码（留空不改）' : '初始化密码'} rules={editing?.id ? [{ min: 6, message: '≥6位' }] : [{ required: true, message: '必填' }, { min: 6, message: '≥6位' }]}>
            <Input.Password placeholder={editing?.id ? '留空则不修改' : '≥6位'} />
          </Form.Item>
          <Space>
            <Form.Item name="role" label="角色" rules={[{ required: true, message: '必选' }]}>
              <Select style={{ width: 160 }} options={Object.entries(ROLES).map(([k, v]) => ({ value: k, label: v.text }))} />
            </Form.Item>
            {editing?.id && (
              <Form.Item name="status" label="状态" rules={[{ required: true, message: '必选' }]}>
                <Select style={{ width: 120 }} options={Object.entries(STATUS).map(([k, v]) => ({ value: Number(k), label: v.text }))} />
              </Form.Item>
            )}
          </Space>
        </Form>
      </Modal>

      <Modal title={`重置密码：${resetOf?.username ?? ''}`} open={!!resetOf} onOk={resetPwd} onCancel={() => setResetOf(null)}>
        <Form form={resetForm} layout="vertical">
          <Form.Item name="password" label="新密码" rules={[{ required: true, message: '必填' }, { min: 6, message: '≥6位' }]}>
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
    </div>
  )
}
