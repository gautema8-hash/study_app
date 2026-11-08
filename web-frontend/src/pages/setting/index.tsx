import { useEffect, useState, useCallback } from 'react'
import { App, Card, Col, Input, Button, Row, Alert, Tag, Space } from 'antd'
import { apiFetch } from '../../auth'
import { apiRead, apiWrite } from '../../services/api'
import { yuan } from '../../components/names'

// W9 系统配置（固定 6 个业务键白名单；前端先校验，后端再做白名单与数字校验）
const KEYS: { key: string; label: string; unit: string; isPrice?: boolean; max: number }[] = [
  { key: 'sku.month.price', label: '月卡价格', unit: '分', isPrice: true, max: 99999999 },
  { key: 'sku.year.price', label: '年卡价格', unit: '分', isPrice: true, max: 99999999 },
  { key: 'sku.vip.price', label: 'VIP永久', unit: '分', isPrice: true, max: 99999999 },
  { key: 'sku.svip.price', label: 'SVIP永久', unit: '分', isPrice: true, max: 99999999 },
  { key: 'trial.days', label: '免费试用天数', unit: '天', max: 365 },
  { key: 'ad.daily.limit', label: '广告换天数日上限', unit: '天', max: 24 },
]

export default function Setting() {
  const [cfg, setCfg] = useState<Record<string, string>>({})
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState<string | null>(null)
  const [err, setErr] = useState('')
  const { message } = App.useApp()

  const load = useCallback(async () => {
    setLoading(true)
    try { setCfg(await apiRead<Record<string, string>>('/api/ops/config')); setErr('') }
    catch (e: any) { setErr(e?.message || '配置加载失败'); message.error(e?.message || '配置加载失败') }
    finally { setLoading(false) }
  }, [message])

  useEffect(() => { load() }, [load])

  const save = async (key: string, max: number) => {
    const value = edits[key]
    if (value === undefined || value === '') { message.warning('请先输入新值'); return }
    // 前端先做数字与范围校验，避免把非法值发到后端再吃 400
    if (!/^\d{1,8}$/.test(value)) { message.error('请输入 1~8 位数字'); return }
    if (Number(value) > max) { message.error(`不能超过 ${max}`); return }
    setSaving(key)
    const r = await apiWrite(`/api/ops/config/${key}`, 'PUT', { value }, apiFetch)
    setSaving(null)
    if (!r.ok) { message.error(r.msg); return }
    message.success('已更新'); setEdits((e) => { const c = { ...e }; delete c[key]; return c }); load()
  }

  return (
    <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="价格类配置以「分」为单位，修改后立即影响新订单；历史订单不受影响。"
        description="系统仅开放 6 个业务键，写入会被后端白名单与数字格式双重校验。" />
      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 12 }}
        action={<Button size="small" onClick={load}>重试</Button>} />}
      <Row gutter={[16, 16]}>
        {KEYS.map(({ key, label, unit, isPrice, max }) => (
          <Col xs={24} sm={12} md={8} key={key}>
            <Card title={<Space>{label}<Tag>{key}</Tag></Space>} size="small" loading={loading}>
              <p style={{ marginBottom: 8 }}>
                当前值：<b>{cfg[key] ?? '-'}</b>
                <span style={{ color: '#999' }}>（{unit}{isPrice && cfg[key] ? `，约 ${yuan(Number(cfg[key]))}` : ''}）</span>
              </p>
              <Input.Search placeholder="输入新值" enterButton="保存" allowClear
                loading={saving === key}
                value={edits[key] ?? ''}
                onChange={(e) => setEdits((s) => ({ ...s, [key]: e.target.value }))}
                onSearch={() => save(key, max)} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  )
}
