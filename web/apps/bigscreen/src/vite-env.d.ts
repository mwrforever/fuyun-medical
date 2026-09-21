/// <reference types="vite/client" />

// 环境变量类型增补（web A.2-3 三处同步：.env.example、本文件 ImportMetaEnv、消费代码）
interface ImportMetaEnv {
  /** 后端 API 基础路径（默认 /api：dev 经 vite proxy 转发，生产经 nginx 反代到 backend） */
  readonly VITE_API_BASE_URL?: string;
  /** 大屏叫号链路令牌（构建期注入的受控演示凭证；默认空=页面显示未配置横幅且零出网） */
  readonly VITE_BIGSCREEN_TOKEN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
