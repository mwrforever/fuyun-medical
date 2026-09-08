/**
 * 跨应用共享契约与纯函数工具（web 宪法 B.1：本包纯 TS，禁依赖 vue/element-plus）。
 * openapi 契约生成物（api.d.ts）与生成脚本待 PR-3 后端 OpenAPI 端点可用后接入。
 */

/**
 * 通用分页契约（来源 backend A.3-6，前后端一致的页结构）。
 * 注意：total 为后端 COUNT 长整型，经 Jackson 全局 Long→String 序列化输出
 * （backend A.3-8），前端一律以 string 承载、禁按 number 处理（web A.3-6）。
 */
export interface PageResult<T> {
  /** 当前页数据列表 */
  content: T[];
  /** 页码（从 1 起） */
  page: number;
  /** 每页条数 */
  size: number;
  /** 总记录数（后端 Long 以字符串输出，string 承载防精度丢失） */
  total: string;
}
