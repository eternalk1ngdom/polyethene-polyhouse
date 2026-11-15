import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginOxlint from 'eslint-plugin-oxlint'
import skipFormatting from 'eslint-config-prettier/flat'

// 同时覆盖包子路径、相对路径和 @ 别名；按依赖地址判断，避免把普通数据字段当作 UI 依赖。
const uiModulePattern =
  '^(vue|pinia|vue-router|element-plus)(/|$)|^@(vue|element-plus)/|\\.vue$|(^|/)(components|views|stores|composables|api|router)(/|$)'
const coreBoundaryMessage = '算法和 Worker 不依赖页面/UI/响应式状态，请通过参数和返回值传递数据。'
const mainThreadMessage = '算法和 Worker 不访问主线程页面对象，请由页面传入所需数据。'

// To allow more languages other than `ts` in `.vue` files, uncomment the following lines:
// import { configureVueProject } from '@vue/eslint-config-typescript'
// configureVueProject({ scriptLangs: ['ts', 'tsx'] })
// More info at https://github.com/vuejs/eslint-config-typescript/#advanced-setup

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,

  ...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),

  skipFormatting,

  {
    name: 'app/core-worker-boundaries',
    files: ['src/core/**/*.ts', 'src/workers/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        { patterns: [{ regex: uiModulePattern, message: coreBoundaryMessage }] },
      ],
      // 静态 import 规则不覆盖动态 import 和 TS import() 类型，补充字面依赖地址的检查。
      'no-restricted-syntax': [
        'error',
        {
          selector: `ImportExpression[source.value=/${uiModulePattern.replaceAll('/', '\\/')}/]`,
          message: coreBoundaryMessage,
        },
        {
          selector: `TSImportType[source.value=/${uiModulePattern.replaceAll('/', '\\/')}/]`,
          message: coreBoundaryMessage,
        },
      ],
      'no-restricted-globals': [
        'error',
        ...['window', 'document', 'navigator'].map((name) => ({ name, message: mainThreadMessage })),
      ],
      // 同时检查 globalThis/self 的点号、固定字符串下标及解构；不限制正常 Worker API。
      'no-restricted-properties': [
        'error',
        ...['globalThis', 'self'].flatMap((object) =>
          ['window', 'document', 'navigator'].map((property) => ({
            object,
            property,
            message: mainThreadMessage,
          })),
        ),
      ],
    },
  },
)
