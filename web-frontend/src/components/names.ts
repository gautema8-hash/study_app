// 字典映射（与后端枚举一致）
export const SUBJECTS = ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理', '政治', '其他']
export const subjectName = (v: number) => SUBJECTS[v - 1] ?? v

export const SKU_NAMES: Record<number, string> = { 1: '月卡', 2: '年卡', 3: 'VIP永久', 4: 'SVIP永久' }
export const TIER_NAMES: Record<number, string> = { 0: '未开通', 1: 'VIP', 2: 'SVIP' }
// 支付渠道（与 vip_order.channel 枚举一致）
export const CHANNEL_NAMES: Record<number, string> = { 0: '未知', 1: '微信', 2: '支付宝', 3: '苹果内购' }
export const ORDER_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待支付', color: 'default' },
  1: { text: '已支付', color: 'processing' },
  2: { text: '已发货', color: 'success' },
  3: { text: '已关闭', color: 'default' },
  4: { text: '退款中', color: 'warning' },
  5: { text: '已退款', color: 'error' },
}
export const Q_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '草稿', color: 'default' },
  1: { text: '上架', color: 'success' },
  2: { text: '下架', color: 'warning' },
  3: { text: '回收站', color: 'error' },
}
// 题库商店题包状态（FR-S 状态机）
export const STORE_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '草稿', color: 'default' },
  1: { text: '待审核', color: 'processing' },
  2: { text: '已上架', color: 'success' },
  3: { text: '已下架', color: 'warning' },
  4: { text: '已驳回', color: 'error' },
}
export const TAG_COLORS = ['blue', 'purple', 'green', 'gold', 'cyan', 'magenta', 'orange']
export const yuan = (fen: number) => `¥${(fen / 100).toFixed(2)}`
