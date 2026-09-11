/**
 * bigscreen 统一日志工具（web A.6「运行日志经统一 logger 或按环境裁剪」最小落法）：
 * debug 仅 DEV 构建输出（生产零残留），info/warn/error 全量 console 输出；
 * 消息全中文并带 [bigscreen] 前缀定位来源，全应用禁止散落 console 直调。
 *
 * <p>红线（web A.6 / 根定位层 §7 安全红线）：访问令牌（含 sessionStorage 键
 * fy:bigscreen:iot-token 的键值）与患者敏感数据禁止进入任何日志参数；STOMP 运行日志
 * 仅输出 brokerURL、订阅主题路径与连接 traceId 等非敏感锚点。
 */

/** 输出前缀（定位 bigscreen 应用日志来源） */
const LOG_PREFIX = '[bigscreen]';

/** 调试日志：仅 DEV 构建输出，生产构建经环境裁剪零残留 */
export function debug(message: string, ...details: unknown[]): void {
  if (import.meta.env.DEV) {
    console.debug(`${LOG_PREFIX} ${message}`, ...details);
  }
}

/** 信息日志：连接建立、订阅落地等核心状态变更留痕 */
export function info(message: string, ...details: unknown[]): void {
  console.info(`${LOG_PREFIX} ${message}`, ...details);
}

/** 警告日志：毒帧、令牌缺失、连接关闭等异常但可自愈场景留痕 */
export function warn(message: string, ...details: unknown[]): void {
  console.warn(`${LOG_PREFIX} ${message}`, ...details);
}

/** 错误日志：broker 错误帧等系统异常留痕 */
export function error(message: string, ...details: unknown[]): void {
  console.error(`${LOG_PREFIX} ${message}`, ...details);
}
