import { useEffect, useState, useCallback } from 'react'
import { Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { TIER_NAMES } from '../../components/names'

// W2 用户管理（FR-W04：手机号脱敏展示，明文查看需二次验证 TODO）
export default function UserManage() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)

  const load = useCallback(async (p: number) => {
    try {
      const r = await fetch(`/api/ops/users?page=${p}&size=20`)
      const j = await r.json()
      setData(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load(page) }, [page, load])

  const cols: ColumnsType<any> = [
    { title: '家庭ID', dataIndex: 'familyId', width: 80 },
    { title: '手机号(脱敏)', dataIndex: 'phone' },
    { title: '实名核验', dataIndex: 'kyc', render: (v) => (v ? <Tag color="success">已核验</Tag> : <Tag>未核验</Tag>) },
    { title: '会员', dataIndex: 'tier', render: (v) => TIER_NAMES[v as number] ?? v },
    { title: '到期', dataIndex: 'expireAt', render: (v) => (v?.startsWith('9999') ? '永久' : v ?? '-') },
    { title: '学生数', dataIndex: 'students', width: 80 },
    { title: '注册时间', dataIndex: 'createdAt' },
  ]
  return (
    <Table
      rowKey="familyId" columns={cols} dataSource={data.list}
      pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage }}
      size="small"
    />
  )
}
