/* U4 专用本机桥接：复用随机隔离 Spring Boot，不模拟业务响应、不启动浏览器。 */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const webRoot = path.resolve(__dirname, '..')
const targetRoot = path.resolve(webRoot, '../polyhouse/target')
const configPath = path.resolve(process.env.POLYHOUSE_WORKSPACE_CONFIG || '')
assert.ok(
  configPath.startsWith(targetRoot + path.sep) &&
    path.basename(configPath) === 'workspace-browser.json',
)
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
const backend = new URL(config.backend)
assert.equal(backend.protocol, 'http:')
assert.equal(backend.hostname, '127.0.0.1')
assert.ok(backend.port && backend.port !== '8080')
const entry = path.join(webRoot, 'temp/u4-entry.ts')
fs.mkdirSync(path.dirname(entry), { recursive: true })
fs.copyFileSync(path.join(__dirname, 'workspace-regression-entry.ts'), entry)
let mode = 'normal'
const requests = []
/** 媒体/JSON 均保留后端状态；有界读取避免验收代理接受任意大请求。 */
async function body(req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > 1024 * 1024) throw new Error('Fixture request limit exceeded')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks)
}
/** 页面仅取脱离凭据的请求事实，结果未知模式只丢响应，不重发真实写操作。 */
function json(res, value) {
  res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(value))
}
const server = http.createServer(async (req, res) => {
  try {
    // 本机监听仍需拒绝陌生 Host，避免 DNS 重绑定访问临时测试会话。
    if (!['localhost:5190', '127.0.0.1:5190'].includes(req.headers.host)) {
      res.writeHead(403)
      res.end()
      return
    }
    const url = new URL(req.url, 'http://localhost:5190')
    const origin = req.headers.origin
    if (origin && !['http://localhost:5190', 'http://127.0.0.1:5190'].includes(origin)) {
      res.writeHead(403)
      res.end()
      return
    }
    if (url.pathname === '/__fixture/sessions' && req.method === 'GET')
      return json(res, config.sessions)
    if (url.pathname === '/__fixture/metrics' && req.method === 'GET')
      return json(res, { mode, requests })
    if (url.pathname === '/__fixture/network' && req.method === 'POST') {
      const next = JSON.parse((await body(req)).toString('utf8')).mode
      assert.ok(['normal', 'offline', 'delay', 'lose-next-write'].includes(next))
      mode = next
      return json(res, { mode })
    }
    if (url.pathname === '/__fixture/finish' && req.method === 'POST') {
      fs.writeFileSync(
        path.join(path.dirname(configPath), 'workspace-requests.json'),
        JSON.stringify(requests),
      )
      fs.writeFileSync(
        path.join(path.dirname(configPath), 'workspace-complete.json'),
        JSON.stringify({ passed: true }),
      )
      json(res, { passed: true })
      server.close(() => process.exit(0))
      return
    }
    if (url.pathname.startsWith('/api/')) {
      if (mode === 'offline') {
        res.writeHead(503)
        res.end()
        return
      }
      if (mode === 'delay' && url.pathname === '/api/auth/me')
        await new Promise((resolve) => setTimeout(resolve, 1500))
      const method = req.method
      const lose = mode === 'lose-next-write' && method === 'POST'
      if (lose) mode = 'normal'
      const headers = {}
      for (const name of ['authorization', 'content-type'])
        if (req.headers[name]) headers[name] = req.headers[name]
      const response = await fetch(new URL(url.pathname + url.search, backend), {
        method,
        headers,
        ...(method === 'GET' || method === 'HEAD' ? {} : { body: await body(req) }),
        signal: AbortSignal.timeout(20000),
      })
      const bytes = Buffer.from(await response.arrayBuffer())
      requests.push({ method, path: url.pathname, status: response.status, responseLost: lose })
      if (lose) {
        // 先确认收到 HTTP 响应，再截断实体；直接 reset 空连接可能被浏览器透明重放。
        // 实际业务已提交，客户端拿不到有效结果，必须显式 GET 核对而非再次 POST。
        res.writeHead(response.status, {
          'Content-Type': 'application/json',
          'Cache-Control': 'no-store',
          'Content-Length': bytes.length + 1,
          Connection: 'close',
        })
        res.end(bytes.subarray(0, 1))
        return
      }
      const forwarded = {}
      for (const name of ['content-type', 'cache-control', 'x-content-type-options']) {
        const value = response.headers.get(name)
        if (value) forwarded[name] = value
      }
      res.writeHead(response.status, forwarded)
      res.end(bytes)
      return
    }
    if (url.pathname === '/polyhouse-mark.svg') {
      res.writeHead(200, { 'Content-Type': 'image/svg+xml' })
      res.end(fs.readFileSync(path.join(webRoot, 'public/polyhouse-mark.svg')))
      return
    }
    res.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(
      '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>U4 隔离验收</title></head><body><div id="app"></div><script type="module" src="http://localhost:5175/temp/u4-entry.ts"></script></body></html>',
    )
  } catch {
    if (!res.headersSent) res.writeHead(502)
    res.end('Isolated verification request failed')
  }
})
// 临时入口只能随本次代理存在，避免误留一个看似可用的测试登录入口。
process.on('exit', () => {
  fs.rmSync(entry, { force: true })
})
server.listen(5190, '127.0.0.1', () =>
  console.log('U4 isolated workspace ready at http://localhost:5190'),
)
