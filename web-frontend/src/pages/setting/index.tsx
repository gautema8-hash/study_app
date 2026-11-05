import { useEffect, useState, useCallback } from 'react'
import { apiFetch } from '../../auth'
import { Card, Col, Input, Button, Row, message } from 'antd'

// W9 系统配置（价格/试用/广告上限；双人复核 TODO）
const KEYS: { key: string; label: string; unit: string }[] = [
  { key: 'sku.month.price', label: '月卡价格', unit: '分 (¥9.00=900)' },
  { key: 'sku.year.price', label: '年卡价格', unit: '分' },
  { key: 'sku.vip.price', label: 'VIP永久', unit: '分' },
  { key: 'sku.svip.price', label: 'SVIP永久', unit: '分' },
  { key: 'trial.days', label: '免费试用天数', unit: '天' },
  { key: 'ad.daily.limit', label: '广告换天数日上限', unit: '天' },
]

export default function Setting() {
  const [cfg, setCfg] = useState<Record<string, string>>({})
  const [edits, setEdits] = useState<Record<string, string>>({})

  const load = useCallback(async () => {
    try {
      const r = await apiFetch('/api/ops/config')
      const j = await r.json()
      setCfg(j.data)
    } catch { message.error('后端未连接') }
  }, [])

  useEffect(() => { load() }, [load])

  const save = async (key: string) => {
    const value = edits[key]
    if (!value) return
    const r = await apiFetch(`/api/ops/config/${key}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value }),
    })
    if (r.ok) { message.success(`${key} 已更新`); setEdits((e) => { const c = { ...e }; delete c[key]; return c }); load() }
    else message.error('保存失败')
  }

  return (
    <Row gutter={[16, 16]}>
      {KEYS.map(({ key, label, unit }) => (
        <Col span={8} key={key}>
          <Card title={label} size="small">
            <p>当前值：<b>{cfg[key] ?? '-'}</b> <span style={{ color: '#999' }}>({unit})</span></p>
            <Input.Search
              placeholder="输入新值" enterButton="保存" value={edits[key] ?? ''}
              onChange={(e) => setEdits((s) => ({ ...s, [key]: e.target.value }))}
              onSearch={() => save(key)}
            />
          </Card>
        </Col>
      ))}
    </Row>
  )
}
