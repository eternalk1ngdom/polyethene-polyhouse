import assert from 'node:assert/strict'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { ESLint } from 'eslint'

// 使用项目真实配置验证边界是否生效；lintText 不写临时源码，不影响构建或测试发现。
const frontendRoot = fileURLToPath(new URL('../../', import.meta.url))
const eslint = new ESLint({ cwd: frontendRoot })
const forbidden = [
  ['Vue', "import { ref } from 'vue'; export const value = ref(0)"],
  ['Vue 子包', "export { reactive } from '@vue/reactivity'"],
  ['Pinia', "export { defineStore } from 'pinia'"],
  ['路由包', "export { useRouter } from 'vue-router'"],
  ['Element Plus 子路径', "import Button from 'element-plus/es/components/button'; export { Button }"],
  ['图标包', "export { Crop } from '@element-plus/icons-vue'"],
  ['相对组件', "export { default } from '../components/TargetKbPanel.vue'"],
  ['别名状态', "export { useUserStore } from '@/stores/useUserStore'"],
  ['别名路由入口', "export { default } from '@/router'"],
  ['响应式类型', "import type { Ref } from 'vue'; export type State = Ref<number>"],
  ['动态 UI 导入', "export const load = () => import('vue')"],
  ['动态组件导入', "export const load = () => import('../components/TargetKbPanel.vue')"],
  ['TS 导入类型', "export type State = import('vue').Ref<number>"],
  ['window', 'export const value = window.location.href'],
  ['document', 'export const value = document.title'],
  ['navigator', 'export const value = navigator.language'],
  ['globalThis 点号', 'export const value = globalThis.document.title'],
  ['globalThis 固定下标', "export const value = globalThis['navigator'].language"],
  ['globalThis 解构', 'const { document: page } = globalThis; export const value = page.title'],
  ['Worker self', 'export const value = self.navigator.language'],
]
const allowed = [
  ['算法和普通数据字段', 'export const sum = (document: number, navigator: number) => document + navigator'],
  ['本模块类型', "import type { ReplaceRule } from './acReplacer'; export type Rules = ReplaceRule[]"],
  ['纯辅助函数', "export { formatBytes } from '../utils/byteSize'"],
  ['第三方纯模块', "export { zipSync } from 'fflate'"],
  ['动态纯模块', "export const load = () => import('fflate')"],
  ['纯模块导入类型', "export type Rule = import('./acReplacer').ReplaceRule"],
]
const boundaryRules = new Set([
  'no-restricted-imports',
  'no-restricted-syntax',
  'no-restricted-globals',
  'no-restricted-properties',
])

for (const directory of ['core', 'workers']) {
  test(`${directory} 拒绝页面依赖和主线程对象`, async () => {
    for (const [label, code] of forbidden) {
      const [result] = await eslint.lintText(code, {
        filePath: path.join(frontendRoot, 'src', directory, 'boundary-probe.ts'),
      })
      assert.ok(result.messages.some((entry) => boundaryRules.has(entry.ruleId)), label)
      assert.equal(result.fatalErrorCount, 0, `样例必须被正常解析：${label}`)
    }
  })
  test(`${directory} 保留正常依赖与模块逻辑`, async () => {
    // 编码和消息属于 Worker 适配职责，不把这类副作用写成算法内核的推荐用法。
    const samples = directory === 'workers'
      ? [...allowed, ['Worker 编码和消息', 'self.postMessage(new OffscreenCanvas(1, 1)); export const decode = createImageBitmap']]
      : allowed
    for (const [label, code] of samples) {
      const [result] = await eslint.lintText(code, {
        filePath: path.join(frontendRoot, 'src', directory, 'boundary-probe.ts'),
      })
      assert.equal(result.errorCount, 0, `${label}: ${JSON.stringify(result.messages)}`)
    }
  })
}

test('边界规则不限制页面适配代码', async () => {
  const [result] = await eslint.lintText(
    "import { ref } from 'vue'; export const title = ref(document.title)",
    { filePath: path.join(frontendRoot, 'src', 'composables', 'boundary-probe.ts') },
  )
  assert.equal(result.errorCount, 0, JSON.stringify(result.messages))
})
