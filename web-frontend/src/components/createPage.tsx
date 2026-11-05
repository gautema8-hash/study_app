import { Empty } from 'antd'

// 通用占位页：后续按 PRD 逐页替换实现
export default function createPage(title: string, desc: string) {
  return function Page() {
    return (
      <div style={{ background: '#fff', padding: 40, minHeight: 360 }}>
        <Empty description={`${title} — ${desc}（待实现）`} />
      </div>
    )
  }
}
