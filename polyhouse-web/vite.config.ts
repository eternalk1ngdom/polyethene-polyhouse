import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 核心配置导出
export default defineConfig({
  // 1. 挂载 Vue 官方单文件组件 (.vue) 编译插件
  plugins: [vue()],

  // 2. 路径解析与别名系统
  resolve: {
    alias: {
      // 将 '@' 符号严格映射为项目根路径下的 /src 绝对目录
      // 允许使用 import xxx from '@/components/xxx.vue' 优雅导入模块
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  // 3. 本地开发服务器配置 (npm run dev 启动的行为)
  server: {
    // 前端本地开发页面运行的端口号 (默认: http://localhost:5173)
    port: 5173,

    // 反向代理规则 (彻底消灭本地联调时的 CORS 跨域报错)
    proxy: {
      // 匹配所有以 /api 开头的请求路径 (如: /api/auth/login, /api/replace/templates)
      '/api': {
        // 转发的目标真实后端地址 (对应 Spring Boot 的本地监听端口)
        target: 'http://localhost:8080',

        // 关键参数：是否修改请求头中的 Host 为目标地址
        // 设置为 true 时，Spring Boot 看到的 Host 头会变成 localhost:8080，保障安全校验与虚拟主机匹配
        changeOrigin: true

        // 注：这里不需要配置 rewrite，因为我们后端设计的标准接口路由本身就带 /api 前缀！
      }
    }
  },

  // 4. 多线程 Web Worker 构建模式配置
  worker: {
    // 指定将 Worker 线程编译为现代 ES Module 标准格式 (import/export)
    // 允许我们在 src/workers/*.worker.ts 中直接无缝引入 src/core/ 下的纯 TS 算法类
    format: 'es'
  }
})
