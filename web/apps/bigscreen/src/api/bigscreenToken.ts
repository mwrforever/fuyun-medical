/**
 * 大屏订阅令牌运行期获取与缓存单源（BUG-19 运行期签发 + W-68 附调改造收口）。
 *
 * <p>端点 POST /api/v1/system/auth/bigscreen-token（匿名白名单）：后端按大屏匿名哨兵会话
 * 签发 5 分钟短期单 access 令牌（与登录 access 同构，/ws/outpatient、/ws/nursing STOMP
 * CONNECT 帧鉴权链无差别校验）；携可选 query 参数 wardId 时按病区签发单病区哨兵会话
 * （后端空白归一 null=泛哨兵；@Pattern ^[A-Za-z0-9_-]{1,64}$）。
 *
 * <p>缓存三态（token / wardId / expiresAt）收敛本模块单源（W-68）：useNursingStomp /
 * useQueueStomp 原各自维护的模块级令牌缓存已删除，连接尝试统一经 ensureBigscreenToken
 * 把关；HTTP 请求拦截器（board 三端点令牌附调）经 getCachedBigscreenToken 同步读同一份
 * 缓存。仅模块内存缓存不入 sessionStorage——短期凭证随标签页周期即弃，缩小驻留面。
 */
import { http } from './http';
import { info, warn } from '@/utils/logger';
import type { components } from '@fuyun/shared/api';

/**
 * 签发响应载荷（生成物唯一来源 A.3-3：后端 BigscreenTokenVO record，禁本地手写镜像类型）。
 * 注意 expiresIn 出网为字符串——后端 Long 经全局 Long→String 序列化（backend A.3-8），与
 * 雪花 id 同口径；消费方须经 Number 显式收窄，禁依赖隐式乘法强转。
 */
export type BigscreenToken = components['schemas']['BigscreenTokenVO'];

/** 缓存到期安全余量（毫秒）：早于令牌 exp 重签，防「临界有效令牌被服务端判过期」 */
const TOKEN_REFRESH_SKEW_MS = 30000;

/** 大屏令牌缓存值（运行期匿名签发；空串=未持有） */
let cachedToken = '';

/** 缓存令牌对应的签发病区（undefined=泛哨兵签发）：换病区强制重签的比对锚点 */
let cachedWardId: string | undefined;

/** 缓存令牌到期时刻（epoch 毫秒；0=不可缓存——畸形 expiresIn 下次尝试立即重签） */
let tokenExpiresAt = 0;

/** 在飞令牌签发 Promise（并发连接尝试去重——防重复签发与并发竞态双写） */
let tokenFetchInFlight: Promise<boolean> | null = null;

/**
 * 获取大屏订阅令牌（每次直签出网原语，不读缓存；缓存把关走 ensureBigscreenToken）。
 *
 * @param wardId 病区编码（可选，来源=页面路由参数原样直传）：携带时后端按病区签发单病区
 *               哨兵会话；undefined 时 axios 自动省略该 query 参数=泛哨兵（后端空白归一
 *               null 对齐）；形态约束后端 @Pattern ^[A-Za-z0-9_-]{1,64}$
 * @return 签发载荷（令牌值 + Bearer 方案名 + 有效期秒数——expiresIn 为字符串线格式）；
 *         生成物字段全可选，缺省字段属畸形载荷，由 ensure 层按「签发失败/不可缓存」兜底
 * @throws ScreenApiError 非 2xx 归一化错误（ensure 层捕获归 false，不向连接链路上抛）
 */
export async function fetchBigscreenToken(wardId?: string): Promise<BigscreenToken> {
  const resp = await http.post<BigscreenToken>('/v1/system/auth/bigscreen-token', undefined, {
    params: { wardId },
  });
  return resp.data;
}

/**
 * 确保持有未过期大屏令牌（连接尝试与受保护面附调的唯一把关入口）：缓存命中条件=
 * 令牌非空 且 未到期（提前 TOKEN_REFRESH_SKEW_MS 余量） 且 病区一致（wardId===undefined
 * 放行任意缓存——泛哨兵消费面不触发重签；显式传病区时与缓存病区不一致即强制重签，
 * 裁决固化：禁复用旧病区令牌订新病区，WS 单病区防线会拒，前端提前重签防无谓失败重连）。
 *
 * <p>获取失败（网络/5xx/畸形载荷）清缓存返回 false 不抛出——语义承接 useNursingStomp/
 * useQueueStomp 既有令牌把关契约：connect 入口据此拒建连，beforeConnect 路径据此放弃
 * 凭证交服务端拒绝（库内建周期重连时再次尝试）。
 *
 * @param wardId 病区编码（可选，语义同 fetchBigscreenToken）
 * @return true=已持有有效令牌（getCachedBigscreenToken 必非空）；false=签发失败（缓存已清）
 */
export async function ensureBigscreenToken(wardId?: string): Promise<boolean> {
  if (
    cachedToken !== '' &&
    Date.now() < tokenExpiresAt - TOKEN_REFRESH_SKEW_MS &&
    (wardId === undefined || wardId === cachedWardId)
  ) {
    return true;
  }
  if (tokenFetchInFlight !== null) {
    return tokenFetchInFlight;
  }
  tokenFetchInFlight = (async () => {
    try {
      const granted = await fetchBigscreenToken(wardId);
      // 畸形载荷防御（生成物字段全可选）：缺令牌值视同签发失败——空值拼 Bearer 头必被服务端
      // 拒绝，交 catch 清缓存走拒建连/重签语义，防 undefined 混入凭证头
      if (granted.accessToken === undefined || granted.accessToken === '') {
        throw new Error('签发载荷缺少 accessToken');
      }
      cachedToken = granted.accessToken;
      cachedWardId = wardId;
      // expiresIn 出网为字符串（后端 Long→String 全局序列化）：显式收窄禁隐式乘法强转；
      // 缺失/非数值（Number→NaN）兜底为「不可缓存」（到期时刻归零使命中判定恒 false）——
      // 下次连接尝试立即重签（安全方向：宁可多签发一次，不静默持有未知有效期的令牌）
      const expiresInSeconds = Number(granted.expiresIn);
      tokenExpiresAt = Number.isFinite(expiresInSeconds) ? Date.now() + expiresInSeconds * 1000 : 0;
      // info 仅留痕有效期原文与病区锚点（令牌值禁入日志，web A.6 红线）
      info(
        '大屏订阅令牌已获取（运行期签发）',
        `expiresIn=${granted.expiresIn ?? '缺省'}s`,
        `wardId=${wardId ?? '泛哨兵'}`,
      );
      return true;
    } catch (fetchError) {
      // 签发失败：清缓存（下次连接尝试整体重签）；失败详情不含令牌值，可安全留痕
      cachedToken = '';
      cachedWardId = undefined;
      tokenExpiresAt = 0;
      warn(
        '大屏订阅令牌运行期获取失败',
        fetchError instanceof Error ? fetchError.message : String(fetchError),
      );
      return false;
    } finally {
      tokenFetchInFlight = null;
    }
  })();
  return tokenFetchInFlight;
}

/**
 * 同步读缓存令牌（bigscreen http.ts 请求拦截器注入 Authorization 的唯一来源；亦供
 * STOMP beforeConnect 拼头消费）。空串=未持有有效令牌，调用方据此保持匿名/无凭证语义。
 *
 * @return 缓存令牌值；未持有或上次签发失败时为空串（非 undefined，调用方免空值收窄）
 */
export function getCachedBigscreenToken(): string {
  return cachedToken;
}
