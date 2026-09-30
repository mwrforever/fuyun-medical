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
import type { components } from '@fuyun/shared/api';

/**
 * 签发响应载荷（生成物唯一来源 A.3-3：后端 BigscreenTokenVO record，禁本地手写镜像类型）。
 * 注意 expiresIn 出网为字符串——后端 Long 经全局 Long→String 序列化（backend A.3-8），与
 * 雪花 id 同口径；消费方须经 Number 显式收窄，禁依赖隐式乘法强转。
 */
export type BigscreenToken = components['schemas']['BigscreenTokenVO'];

/**
 * 获取大屏订阅令牌（STOMP CONNECT 帧鉴权凭证的唯一运行期来源，useQueueStomp 消费）。
 *
 * @return 签发载荷（令牌值 + Bearer 方案名 + 有效期秒数——expiresIn 为字符串线格式）；生成物
 *         字段全可选，缺省字段属畸形载荷，由消费方按「签发失败 / 不可缓存」兜底
 * @throws ScreenApiError 非 2xx 归一化错误（初始建连失败态整页横幅由页面承载）
 */
export async function fetchBigscreenToken(): Promise<BigscreenToken> {
  const resp = await http.post<BigscreenToken>('/v1/system/auth/bigscreen-token');
  return resp.data;
}
