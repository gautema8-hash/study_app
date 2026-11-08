import { useEffect, useState, useCallback } from 'react'
import { App, Card, Table, Tag, Select, Button, Space } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiRead } from '../../services/api'
import { exportCsv } from '../../components/common'

const MODULES: Record<string, { text: string; color: string }> = {
  USER: { text: '用户', color: 'blue' }, FINANCE: { text: '财务', color: 'gold' },
  QUESTION: { text: '题库', color: 'purple' }, TICKET: { text: '工单', color: 'cyan' },
  IAM: { text: '权限', color: 'red' }, OPS: { text: '运营', color: 'default' },
}

// 操作日志：记录所有后台写操作，支持模块筛选 / 分页 / 导出
export default function Audit() {
  const [data, setData] = useState<{ total: number; list: any[] }>({ total: 0, list: [] })
  const [page, setPage] = useState(1)
  const [module, setModule] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)
  const { message } = App.useApp()

  const load = useCallback(async (p: number, m?: string) => {
    setLoading(true)
    try {
      const qs = new URLSearchParams({ page: String(p), size: '20' })
      if (m) qs.set('module', m)
      setData(await apiRead<{ total: number; list: any[] }>(`/api/ops/insight/audit?${qs}`))
    } catch (e: any) { message.error(e?.message || '操作日志加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load(page, module) }, [page, module, load])

  const cols: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '操作人', dataIndex: 'username', width: 120, render: (v, r) => v || `#${r.operatorId}` },
    { title: '模块', dataIndex: 'module', width: 90, render: (v: string) => {
      const m = MODULES[v] ?? { text: v, color: 'default' }; return <Tag color={m.color}>{m.text}</Tag>
    } },
    { title: '操作', dataIndex: 'action', ellipsis: true },
    { title: '结果', dataIndex: 'result', width: 130, render: (v: string) => {
      const code = Number((v || '').match(/\d+/)?.[0] ?? 0)
      return <Tag color={code < 400 ? 'success' : 'error'}>{code < 400 ? '成功' : '失败'}</Tag>
    } },
    { title: 'IP', dataIndex: 'ip', width: 130, render: (v) => v || '-' },
    { title: '时间', dataIndex: 'createdAt', width: 170 },
  ]

  return (
    <Card size="small" title={`操作日志（共 ${data.total} 条）`}
      extra={<Button size="small" onClick={() => exportCsv('操作日志', data.list, [
        { title: 'ID', key: 'id' }, { title: '操作人', key: 'username' }, { title: '模块', key: 'module' },
        { title: '操作', key: 'action' }, { title: '结果', key: 'result' },
        { title: 'IP', key: 'ip' }, { title: '时间', key: 'createdAt' },
      ])}>导出 CSV</Button>}>
      <div className="ops-toolbar">
        <Select placeholder="全部模块" allowClear style={{ width: 140 }} value={module}
          options={Object.entries(MODULES).map(([k, v]) => ({ value: k, label: v.text }))}
          onChange={(v) => { setModule(v); setPage(1) }} />
        <Button size="small" onClick={() => load(page, module)}>刷新</Button>
        <Space style={{ marginLeft: 'auto', color: '#8c8c8c', fontSize: 12 }}>日志采用哈希链防篡改，仅记录写操作</Space>
      </div>
      <div className="ops-table-wrap">
        <Table rowKey="id" columns={cols} dataSource={data.list} loading={loading} size="small"
          pagination={{ current: page, total: data.total, pageSize: 20, onChange: setPage, showSizeChanger: false }}
          locale={{ emptyText: <div className="ops-empty">暂无操作日志，执行任意写操作后会自动记录</div> }} />
      </div>
    </Card>
  )
}
