// Web 管理后台浏览器渲染 E2E（Edge headless + CDP，Node 内置 API，零新依赖）
// 用法: ADMIN_TOKEN=<jwt> node scripts/web_browser_e2e.mjs [base_url]
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:3100'
const TOKEN = process.env.ADMIN_TOKEN || ''
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const OUT = 'docs'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

if (!TOKEN) { console.error('缺少 ADMIN_TOKEN'); process.exit(1) }
mkdirSync(OUT, { recursive: true })

const profile = join(tmpdir(), 'xuexijiesuo-cdp')
const edge = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=9222', `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' })

let ws, id = 0
const pending = new Map()
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const msgId = ++id
    pending.set(msgId, { resolve, reject })
    ws.send(JSON.stringify({ id: msgId, method, params }))
  })
}
async function evalJs(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  return r.result?.result?.value
}
async function goto(hash) {
  await send('Page.navigate', { url: `${BASE}/#${hash}` })
  await sleep(1400)
}
async function text() { return (await evalJs('document.body.innerText')) || '' }
async function shot(name) {
  const r = await send('Page.captureScreenshot', { format: 'png' })
  if (r.result?.data) writeFileSync(join(OUT, name), Buffer.from(r.result.data, 'base64'))
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`)
}

try {
  // 等待 CDP 端口就绪
  let targets
  for (let i = 0; i < 30; i++) {
    try { targets = await (await fetch('http://127.0.0.1:9222/json')).json(); if (targets?.length) break } catch {}
    await sleep(500)
  }
  const page = targets.find((t) => t.type === 'page')
  if (!page) throw new Error('无法连接 Edge CDP')
  ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject })
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id); pending.delete(msg.id)
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg)
    }
  }
  await send('Page.enable'); await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 1. 未登录路由守卫：先清空上次 profile 的 token 并整页重载，确保干净状态
  await goto('/login')
  await evalJs("localStorage.removeItem('adminToken')")
  await send('Page.reload')
  await sleep(1800)
  await goto('/workbench')
  const loginText = await text()
  check('未登录跳登录页', loginText.includes('管理后台登录'), loginText.slice(0, 60).replace(/\n/g, ' '))
  await shot('双端测试-Web-01-登录页.png')

  // 2. 注入真实登录 token 并整页重载（auth.ts 模块变量在加载时读取一次 localStorage）
  await evalJs(`localStorage.setItem('adminToken', ${JSON.stringify(TOKEN)})`)
  await send('Page.reload')
  await sleep(2200)
  const pages = [
    ['/workbench', '注册家庭', '双端测试-Web-02-工作台.png'],
    ['/user', '手机号(脱敏)', '双端测试-Web-03-用户管理.png'],
    ['/order', '订单号', null],
    ['/reconcile', '对账', null],
    ['/bank', '题干', '双端测试-Web-04-题库运营.png'],
    ['/review', '审核', null],
    ['/ticket', '申诉', null],
    ['/dashboard', '看板', null],
    ['/setting', '月卡价格', '双端测试-Web-05-系统配置.png'],
    ['/iam', '权限', null],
  ]
  for (const [route, keyword, screenshot] of pages) {
    await goto(route)
    const t = await text()
    check(`页面 ${route}`, t.includes(keyword),
        t.includes(keyword) ? `命中「${keyword}」` : `实际: ${t.slice(0, 70).replace(/\n/g, ' ')}`)
    if (screenshot) await shot(screenshot)
  }
} catch (e) {
  check('浏览器 E2E 执行', false, e.message)
} finally {
  try { ws?.close() } catch {}
  edge.kill()
}

const failed = results.filter((r) => !r.ok)
console.log(`== 汇总: PASS=${results.length - failed.length} FAIL=${failed.length} ==`)
process.exit(failed.length ? 1 : 0)
