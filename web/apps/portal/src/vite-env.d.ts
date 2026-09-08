/// <reference types="vite/client" />

// 环境变量类型增补（web A.2-3 三处同步：.env.example、本文件 ImportMetaEnv、消费代码）
interface ImportMetaEnv {
  /** 后端 API 基础路径（默认 /api：dev 经 vite proxy 转发，生产经 nginx 反代到 backend） */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
