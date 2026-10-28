import assert from 'node:assert/strict'
import { fork } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { sep } from 'node:path'
import { Worker } from 'node:worker_threads'
import { once } from 'node:events'
import { unzipSync } from 'fflate'

const script = fileURLToPath(import.meta.url)
const targetRoot = new URL('../../polyhouse/target/', import.meta.url)
const mib = (value) => +(value / 1024 / 1024).toFixed(1)
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex')

// 每次样本使用独立进程，避免编译器堆和上一轮 GC 影响增量；RSS 是进程总驻留内存，含 Worker。
if (process.argv[2] === '--sample') {
  const [job] = await once(process, 'message')
  globalThis.gc?.()
  const baseline = process.memoryUsage.rss()
  let peak = baseline
  const sample = setInterval(() => {
    peak = Math.max(peak, process.memoryUsage.rss())
  }, 5)
  let worker
  const started = performance.now()
  try {
    const files = []
    for (const name of job.names) {
      const content = await readFile(new URL(name, job.root))
      files.push({ relativePath: `fixture/${name}`, content: new Uint8Array(content).buffer })
    }
    // 仅补上 Node 的 Worker 通信适配；替换、解码和 ZIP 均执行生产源码。
    const bootstrap = `
      const { parentPort, workerData } = require('node:worker_threads');
      globalThis.self = { postMessage: (value, options) => parentPort.postMessage(value, options?.transfer) };
      (async () => {
        await import(workerData.module);
        parentPort.on('message', data => self.onmessage({ data }));
        parentPort.postMessage({ ready: true });
      })();
    `
    worker = new Worker(bootstrap, { eval: true, workerData: { module: job.module } })
    await new Promise((resolve, reject) => {
      worker.once('error', reject)
      worker.once('message', (value) => {
        assert.equal(value.ready, true)
        resolve()
      })
    })
    const done = once(worker, 'message')
    worker.postMessage(
      { files, rules: job.rules, mode: 'directory', inputEncoding: 'utf-8' },
      files.map((file) => file.content),
    )
    const [reply] = await done
    assert.equal(reply.ok, true)
    const elapsedMs = +(performance.now() - started).toFixed(1)
    peak = Math.max(peak, process.memoryUsage.rss(), process.resourceUsage().maxRSS * 1024)
    clearInterval(sample)
    await worker.terminate()
    // 校验完整解压字节哈希而非只看成功标志；验证内存不计入前面流水线观察值。
    const entries = unzipSync(new Uint8Array(reply.result.bytes))
    assert.equal(Object.keys(entries).length, job.names.length)
    let outputBytes = 0
    for (const name of job.names) {
      const content = entries[`fixture/${name}`]
      assert.equal(hash(content), job.expectedHash)
      outputBytes += content.byteLength
    }
    const counts = reply.result.files.reduce((total, file) => total + file.count, 0)
    assert.equal(counts, job.expectedCount)
    process.send({
      elapsedMs,
      baselineMiB: mib(baseline),
      observedPeakMiB: mib(peak),
      increaseMiB: mib(peak - baseline),
      outputBytes,
      matches: counts,
    })
  } finally {
    clearInterval(sample)
    if (worker) await worker.terminate()
    process.disconnect()
  }
} else {
  const ts = (await import('typescript')).default
  const url = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
  async function module(path, replacements = {}) {
    let code = ts.transpileModule(await readFile(new URL(path, import.meta.url), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText
    for (const [from, to] of Object.entries(replacements))
      code = code.replaceAll(`from '${from}'`, `from '${to}'`)
    return url(code)
  }
  const core = await module('../src/core/acReplacer.ts')
  const batch = await module('../src/core/polyReplaceBatch.ts', { './acReplacer': core })
  const worker = await module('../src/workers/polyReplace.worker.ts', {
    '../core/polyReplaceBatch': batch,
    fflate: import.meta.resolve('fflate'),
  })
  const cases = [
    {
      label: '20 files / 5 MiB / no matches',
      count: 20,
      input: 'x'.repeat(256 * 1024),
      output: 'x'.repeat(256 * 1024),
      search: 'abc',
      replace: 'Z',
      matches: 0,
    },
    {
      label: '20 files / 5 MiB / dense matches',
      count: 20,
      input: 'abc '.repeat(64 * 1024),
      output: 'Z '.repeat(64 * 1024),
      search: 'abc',
      replace: 'Z',
      matches: 20 * 64 * 1024,
    },
    {
      label: '2 files / 2 MiB input / 16 MiB output',
      count: 2,
      input: 'a'.repeat(1024 * 1024),
      output: 'b'.repeat(8 * 1024 * 1024),
      search: 'a',
      replace: 'bbbbbbbb',
      matches: 2 * 1024 * 1024,
    },
  ]
  await mkdir(targetRoot, { recursive: true })
  const root = pathToFileURL(
    (await mkdtemp(fileURLToPath(new URL('polyreplace-capacity-', targetRoot)))) + sep,
  )
  const summary = []
  try {
    for (const item of cases) {
      const names = Array.from({ length: item.count }, (_, index) => `sample-${index}.txt`)
      for (const name of names) await writeFile(new URL(name, root), item.input)
      const rows = []
      for (let attempt = 0; attempt < 3; attempt++) {
        const child = fork(script, ['--sample'], {
          execArgv: ['--expose-gc'],
          stdio: ['ignore', 'ignore', 'inherit', 'ipc'],
        })
        const exit = once(child, 'exit')
        const answer = new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            child.kill()
            reject(new Error('Pipeline sample timed out'))
          }, 30000)
          child.once('error', (error) => {
            clearTimeout(timer)
            reject(error)
          })
          child.once('message', (value) => {
            clearTimeout(timer)
            resolve(value)
          })
          child.once('exit', (code) => {
            clearTimeout(timer)
            if (code !== 0) reject(new Error(`Pipeline child failed: ${code}`))
          })
        })
        child.send({
          root: root.href,
          module: worker,
          names,
          rules: Array.from({ length: 20 }, (_, index) => ({
            search: index ? `unmatched-${index}` : item.search,
            replace: index ? 'Z' : item.replace,
          })),
          expectedHash: hash(Buffer.from(item.output)),
          expectedCount: item.matches,
        })
        rows.push(await answer)
        assert.equal((await exit)[0], 0)
      }
      summary.push({
        case: item.label,
        medianMs: rows.map((row) => row.elapsedMs).sort((a, b) => a - b)[1],
        samples: rows,
      })
    }
    await writeFile(
      new URL('../../polyhouse/target/polyreplace-pipeline-results.json', import.meta.url),
      JSON.stringify(
        {
          runtime: process.version,
          method:
            'Isolated Node process + real worker_threads, 5ms RSS samples and OS maxRSS; includes read/startup/decode/replace/ZIP/transfer, excludes download/DOM. Not browser peak memory.',
          summary,
        },
        null,
        2,
      ),
    )
    for (const row of summary) console.log(JSON.stringify(row))
  } finally {
    // 固定在 target 下由本脚本创建的目录，清理前再次核对解析后的实际范围。
    const resolved = fileURLToPath(root)
    const target = fileURLToPath(targetRoot)
    assert.ok(resolved.startsWith(target) && resolved.length > target.length)
    await rm(root, { recursive: true, force: true })
  }
}
