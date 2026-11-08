import { Button, Space, Popconfirm, Tag } from 'antd'
import { DownloadOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons'
import React from 'react'

/** 统一空状态：区分"暂无数据"与"筛选无结果"，并提供重置/新建快捷动作 */
export function EmptyState({
  title = '暂无数据', desc = '当前条件下没有记录', onReset, onCreate, createText = '立即新建',
}: { title?: string; desc?: string; onReset?: () => void; onCreate?: () => void; createText?: string }) {
  return (
    <div className="ops-empty">
      <div className="ops-empty-icon">▤</div>
      <div className="ops-empty-title">{title}</div>
      <div className="ops-empty-desc">{desc}</div>
      <Space>
        {onReset && <Button size="small" icon={<ReloadOutlined />} onClick={onReset}>重置筛选</Button>}
        {onCreate && <Button size="small" type="primary" onClick={onCreate}>{createText}</Button>}
      </Space>
    </div>
  )
}

/** 前端 CSV 导出（无需后端接口，带 BOM 保证 Excel 中文不乱码） */
export function exportCsv(filename: string, rows: any[], columns: { title: string; key: string; render?: (v: any, r: any) => any }[]) {
  const esc = (v: any) => {
    const s = v == null ? '' : String(v)
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
  }
  const head = columns.map((c) => esc(c.title)).join(',')
  const body = rows.map((r) => columns.map((c) => esc(c.render ? c.render(r[c.key], r) : r[c.key])).join(',')).join('\n')
  const blob = new Blob(['\ufeff' + head + '\n' + body], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename + '_' + new Date().toISOString().slice(0, 10) + '.csv'
  a.click()
  URL.revokeObjectURL(url)
}

/** 列表页通用批量操作条 */
export function BatchBar({
  count, onDelete, onExport, onClear, extra,
}: { count: number; onDelete?: () => void; onExport?: () => void; onClear?: () => void; extra?: React.ReactNode }) {
  if (count === 0 && !onExport) return null
  return (
    <div style={{ marginBottom: 10, padding: '8px 12px', background: '#e6f4ff', borderRadius: 6, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
      <span>已选择 <b>{count}</b> 项</span>
      {onDelete && (
        <Popconfirm title={`确认批量删除选中的 ${count} 项？`} onConfirm={onDelete}>
          <Button size="small" danger icon={<DeleteOutlined />} disabled={count === 0}>批量删除</Button>
        </Popconfirm>
      )}
      {onExport && <Button size="small" icon={<DownloadOutlined />} onClick={onExport}>导出 CSV</Button>}
      {extra}
      {count > 0 && onClear && <Button size="small" type="link" onClick={onClear}>取消选择</Button>}
    </div>
  )
}

/** 通用状态色映射 */
export const STATUS_TONES: Record<string, string> = {
  success: 'success', processing: 'processing', warning: 'warning', error: 'error', default: 'default',
}
export function StatusTag({ text, color = 'default' }: { text: React.ReactNode; color?: string }) {
  return <Tag color={STATUS_TONES[color] ?? color}>{text}</Tag>
}
