/**
 * 大屏订阅令牌运行期获取（BUG-19：叫号大屏 WS 链路凭证改运行期签发——删除构建期
 * VITE_BIGSCREEN_TOKEN 内联红线缺陷，web 宪法 A.2-2「任何密钥/内部凭证禁止声明为
 * VITE_ 变量」零例外）。
 *
 * <p>端点 POST /api/v1/system/auth/bigscreen-token（匿名白名单）：后端按大屏匿名哨兵会话
 * 签发 5 分钟短期单 access 令牌（与登录 access 同构，/ws/outpatient STOMP CONNECT 帧鉴权
 * 链无差别校验）。失效由调用方按次重签（换发成本为一次匿名 HTTP，无 refresh 语义）。
 */
import { http } from './http';

/** 签发响应载荷（后端 BigscreenTokenVO record 镜像：无身份字段，脱敏出网冻结口径） */
export interface BigscreenToken {
  /** 访问令牌（typ=access，短期 TTL），非空；两段式 Base64Url 线格式，禁入任何日志 */
  accessToken: string;
  /** 令牌方案名，恒为 "Bearer"（RFC 6750，请求头拼接时后接空格） */
  tokenType: string;
  /** 有效期（秒），正值；调用方据此缓存到期重签 */
  expiresIn: number;
}

/**
 * 获取大屏订阅令牌（STOMP CONNECT 帧鉴权凭证的唯一运行期来源，useQueueStomp 消费）。
 *
 * @return 签发载荷（令牌值 + Bearer 方案名 + 有效期秒数）
 * @throws ScreenApiError 非 2xx 归一化错误（初始建连失败态整页横幅由页面承载）
 */
export async function fetchBigscreenToken(): Promise<BigscreenToken> {
  const resp = await http.post<BigscreenToken>('/v1/system/auth/bigscreen-token');
  return resp.data;
}
