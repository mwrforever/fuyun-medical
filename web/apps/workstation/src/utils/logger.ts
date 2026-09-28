/**
 * workstation 统一日志工具（web A.6「运行日志经统一 logger 或按环境裁剪」最小落法，
 * bigscreen 同构移植换前缀）：info/warn/error 全量 console 输出，消息全中文并带
 * [workstation] 前缀定位来源，全应用禁止散落 console 直调。
 *
 * <p>红线（web A.6 / 根定位层 §7 安全红线）：访问令牌（含 sessionStorage 键
 * fy:workstation:auth 快照内的会话凭据）与患者敏感数据禁止进入任何日志参数；STOMP 运行
 * 日志仅输出 brokerURL、订阅主题路径与连接 traceId 等非敏感锚点。
 */

/** 输出前缀（定位 workstation 应用日志来源） */
const LOG_PREFIX = '[workstation]';

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
