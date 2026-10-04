/**
 * bigscreen 护士站大屏 STOMP 连接单例封装（Task 17，镜像 useQueueStomp 范式——web 宪法
 * B.3-3 逐条款落点，护士站大屏唯一建连入口；与 useIotStomp/useQueueStomp 并存，页面互斥
 * 使用不并发建连）：
 *
 * 1. 【单例 Client】模块级缓存、首次 connect() 惰性创建（App.spec 冒烟挂载零网络副作用）；
 * 2. 【重连与心跳全交库内建】reconnectDelay=10000 + 双向心跳 10s（禁自研循环，B.3-3）；
 * 3. 【订阅句柄统一管理】subscribeBoard 返回代理句柄（退订单出口），连接落地前登记待订阅、
 *    onConnect 转正；断线重连由 onConnect 无条件重订阅（stompjs 7.3.0 无自动重订阅，
 *    onWebSocketClose/onStompError 先将在册句柄置 null 作废——useIotStomp PR-5 Finding 2
 *    同款，防「徽标已连接、零帧流入」假连接）；换病区重订阅先退订旧订阅再落地新主题；
 * 4. 【匿名短期令牌运行期获取经 beforeConnect 动态填 connectHeaders】凭证=后端匿名签发的
 *    5 分钟短期单 access 令牌（POST /v1/system/auth/bigscreen-token?wardId=，按病区签发
 *    单病区哨兵会话——NursingConnectAuthInterceptor 两态通吃：登录 access 与匿名令牌同构
 *    同链校验）。初始建连取不到令牌时 connect() 拒建连（tokenFailed 置位，页面承载「护理
 *    实时链路令牌获取失败」横幅 + 零 WS 出网）；令牌缓存到期/换病区重签由 beforeConnect
 *    按次把关，重签失败本次尝试无凭证交服务端拒绝（库内建周期重连时再次尝试）；令牌值
 *    禁入任何日志（web A.6）；令牌缓存收敛至 api/bigscreenToken.ts 单源（W-68 附调改造
 *    ——HTTP 请求拦截器与 CONNECT 帧共用同一份缓存，本模块不再自持缓存字段）；
 * 5. 【onStompError / onWebSocketClose 统一挂接】经 utils/logger 输出（含主题与 traceId）。
 *
 * <p>帧载荷：后端 NurseBoardPushFrame 统一信封 {type, payload, occurredAt}（五值冻结词表
 * ——BED_PATIENT/TASK_OVERDUE/INFUSION_ESCALATION/ADVERSE_EVENT_REMIND/CALL_TRIGGERED），
 * 收窄归 utils/nursingMessage 纯函数；毒帧 warn 留痕不中断订阅。topic 尾段=路由病区
 * （护理病区编码或 CALL_TRIGGERED 的 iot 病区 id 数字串——Task 11 裁决两标识空间零映射，
 * 页面 query.wardId 原样直订）。
 *
 * <p>连接状态机（页面消费）：disconnected → connecting → connected；deactivate() 承载主动断开。
 */
import { Client } from '@stomp/stompjs';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { ref } from 'vue';
import type { Ref } from 'vue';
import { ensureBigscreenToken, getCachedBigscreenToken } from '@/api/bigscreenToken';
import { parseNursingBoardFrame } from '@/utils/nursingMessage';
import type { NursingBoardFrame } from '@/utils/nursingMessage';
import { error as logError, info, warn } from '@/utils/logger';

/** 连接状态机三态（页面消费口径，与 useIotStomp/useQueueStomp 同构） */
export type NursingConnectionState = 'disconnected' | 'connecting' | 'connected';

/** 库内建固定重连间隔（毫秒，宪法 B.3-3 数值 10s） */
const RECONNECT_DELAY_MS = 10000;

/** 双向心跳间隔（毫秒，与后端 STOMP 心跳协商的宪法数值） */
const HEARTBEAT_MS = 10000;

/**
 * 当前建连/订阅病区编码（connect/subscribeBoard 双入口更新）：令牌按病区签发，重连路径
 * beforeConnect 据此透传当前病区给 ensure 把关——换病区强制重签裁决在 api 层命中条件承载
 * （W-68 收敛），本模块零缓存字段。
 */
let currentWardId: string | undefined;

/** 初始建连令牌获取失败态可写源（模块内部翻转专用，禁止外泄） */
const tokenFailedRef = ref(false);

/** 初始建连令牌是否获取失败（页面「护理实时链路令牌获取失败」横幅唯一来源，只读消费；
 * 重连路径的瞬时重签失败不置位——该路径由服务端拒绝 + 库内建重连承载） */
export const tokenFailed: Readonly<Ref<boolean>> = tokenFailedRef;

/** 全应用唯一 Client 实例（惰性创建，null=尚未首次建连） */
let client: Client | null = null;

/** 当前连接的链路 traceId（每次 connect() 重新生成，作日志锚点；禁与令牌并列输出） */
let connectionTraceId = '';

/** board 订阅在册记录（单槽位；handle=null=待连接落地转正） */
let boardSubscription: {
  destination: string;
  onFrame: (frame: NursingBoardFrame) => void;
  handle: StompSubscription | null;
} | null = null;

/** connect() 传入的状态变更回调（disconnect 时解除，防悬挂引用） */
let stateChangeListener: ((state: NursingConnectionState) => void) | null = null;

/**
 * 建连代际计数（竞态防御）：connect 发起时快照当前代际，disconnect 递增作废所有在飞建连链——
 * 初始建连先异步取令牌再激活 Client，若取令牌在飞期间组件卸载（disconnect 时 client 尚为
 * null、无从 deactivate），失配的建连链必须放弃激活，否则 WS 在卸载后被激活且永不断开。
 */
let connectGeneration = 0;

/** 连接状态可写源（模块内部状态翻转专用，禁止外泄） */
const connectionStateRef = ref<NursingConnectionState>('disconnected');

/** 连接状态（模块级单例 ref：全 app 唯一护理连接故状态源唯一，页面只读消费） */
export const connectionState: Readonly<Ref<NursingConnectionState>> = connectionStateRef;

/**
 * 病区编码合法性：非空（BoardController @NotBlank 同口径；护理病区编码形态自由——
 * CALL_TRIGGERED 的 iot 数字串由调用方原样传入，本层零映射零格式假设）。
 */
function isValidWardId(wardId: string): boolean {
  return wardId.trim() !== '';
}

/**
 * 生成 board 订阅主题路径（与后端 NurseBoardPushListener.BOARD_TOPIC_PREFIX 契约逐字对齐；
 * topic 尾段=路由病区——护理病区编码或 CALL_TRIGGERED 的 iot 数字串，原样直订零映射）。
 *
 * @param wardId 病区编码（调用方已校验非空）
 * @return 主题路径，如 /topic/nursing/board/1001
 */
export function boardTopicPath(wardId: string): string {
  return `/topic/nursing/board/${wardId}`;
}

/** 状态翻转：去重后更新单例 ref 并触发调用方回调 */
function setConnectionState(state: NursingConnectionState): void {
  if (connectionStateRef.value === state) {
    return;
  }
  connectionStateRef.value = state;
  stateChangeListener?.(state);
}

/** brokerURL 同源推导：ws/wss 随页面协议，路径 /ws/nursing（dev 经 vite /ws 代理，生产经
 * nginx /ws/ 升级路由）——零新增 VITE_ 变量 */
function buildBrokerUrl(): string {
  const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
  return `${protocol}${location.host}/ws/nursing`;
}

/** 日志 traceId 锚点（未建连时以 - 占位） */
function traceTag(): string {
  return `traceId=${connectionTraceId || '-'}`;
}

/**
 * 生成连接链路 traceId（仅作日志锚点，非密码学用途）：安全上下文用 crypto.randomUUID，
 * HTTP 内网部署降级为时间戳+随机数组合串（useIotStomp PR-5 Finding 4 同款降级）。
 */
function generateTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 作废在册订阅句柄（置 null 是重连 onConnect 重订阅的前提，PR-5 Finding 2 同款） */
function invalidateSubscriptionHandle(): void {
  if (boardSubscription !== null) {
    boardSubscription.handle = null;
  }
}

/** 惰性创建全应用唯一 Client（仅首次 connect 时执行；配置参数为 web B.3-3 合规锚点） */
function getOrCreateClient(): Client {
  if (client !== null) {
    return client;
  }
  client = new Client({
    brokerURL: buildBrokerUrl(),
    // 重连与心跳完全交库内建机制（B.3-3）：固定间隔重试 + 10s 双向心跳，禁自研重连循环
    reconnectDelay: RECONNECT_DELAY_MS,
    heartbeatIncoming: HEARTBEAT_MS,
    heartbeatOutgoing: HEARTBEAT_MS,
    // 每次连接尝试（含断线自动重连）先经 api 层单源 ensure 按当前病区把关（到期/换病区
    // 重签），再同步读缓存拼 Bearer 头进 CONNECT 帧——运行期获取实时读取不固化
    beforeConnect: async () => {
      if (client === null) {
        return;
      }
      const tokenReady = await ensureBigscreenToken(currentWardId);
      if (tokenReady) {
        const token = getCachedBigscreenToken();
        // ensure=true 契约上缓存必非空，空值防御仅防拼出「Bearer 」裸方案头被服务端误读
        if (token !== '') {
          client.connectHeaders = { Authorization: `Bearer ${token}` };
        }
      } else {
        // 重签失败（仅重连路径可达——connect 入口失败不激活）：本次尝试无凭证，预期被后端
        // CONNECT 帧鉴权拒绝后按库内建周期重试（重试时再次签发）；warn 不含键值
        warn('STOMP 连接缺少护理大屏令牌，本次尝试将被服务端拒绝', traceTag());
      }
    },
    onConnect: () => {
      setConnectionState('connected');
      // 无条件重订阅（PR-5 Finding 2 同款）：重连路径与首连路径复用同一 doSubscribe 落地方法
      const record = boardSubscription;
      if (record !== null) {
        record.handle = doSubscribe(record);
        info('已订阅护理 board 主题', record.destination, traceTag());
      }
      info('STOMP 已连接', buildBrokerUrl(), traceTag());
    },
    onStompError: (frame) => {
      // broker ERROR 帧：在册订阅句柄随连接作废置 null，置断开态留痕，库按内建周期重试；
      // 日志含主题与 traceId、禁含令牌
      invalidateSubscriptionHandle();
      setConnectionState('disconnected');
      logError('STOMP broker 错误帧', frame.headers['message'] ?? '', traceTag());
    },
    onWebSocketClose: () => {
      // 连接关闭（含 CONNECT 帧鉴权被拒）：在册订阅句柄置 null 作废并置断开态
      invalidateSubscriptionHandle();
      setConnectionState('disconnected');
      warn('STOMP 连接已关闭，等待库内建自动重连', buildBrokerUrl(), traceTag());
    },
  });
  return client;
}

/** 落地库订阅：帧回调内 try/catch 解析信封载荷，毒帧 warn 留痕不中断订阅 */
function doSubscribe(record: {
  destination: string;
  onFrame: (frame: NursingBoardFrame) => void;
}): StompSubscription {
  return getOrCreateClient().subscribe(record.destination, (message: IMessage) => {
    try {
      const frame = parseNursingBoardFrame(JSON.parse(message.body) as unknown);
      if (frame === null) {
        warn('护理 board 帧载荷不合法，已忽略本帧', record.destination, traceTag());
        return;
      }
      record.onFrame(frame);
    } catch {
      // 非法 JSON 毒帧：warn 留痕，订阅保持
      warn('护理 board 帧 JSON 解析失败，已忽略本帧', record.destination, traceTag());
    }
  });
}

/** 退订在册订阅（单出口：句柄未落地时仅清登记，防悬挂重复退订） */
function unsubscribeBoard(): void {
  if (boardSubscription === null) {
    return;
  }
  boardSubscription.handle?.unsubscribe();
  boardSubscription = null;
}

/**
 * 建连（护士站大屏唯一入口）：先运行期获取订阅令牌，取不到直接拒建连（tokenFailed 置位供
 * 页面整页横幅，零 Client 创建零 WS 出网——拒建连语义保持）；已连接态再次调用走「保持
 * connected + 不重复 activate」分支（useIotStomp PR-5 Finding 3 同款），订阅切换由紧随其后
 * 的 subscribeBoard 已连接分支承接。
 *
 * <p>状态时序：connect() 同步进入 connecting（含令牌签发阶段）；签发失败回落 disconnected
 * 并置 tokenFailed；刷新页面或换病区重连为失败态恢复路径（重试签发）。
 *
 * @param wardId 病区编码（topic 尾段直订——护理编码或 iot 数字串零映射），非空；非法拒建连
 * @param onStateChange 连接状态变更回调（可选）
 */
export function connect(
  wardId: string,
  onStateChange?: (state: NursingConnectionState) => void,
): void {
  if (!isValidWardId(wardId)) {
    warn('连接被拒绝：病区编码不得为空');
    return;
  }
  stateChangeListener = onStateChange ?? null;
  if (client !== null && client.connected) {
    // 已连接：保持 connected 态、不重复 activate（no-op）；换病区重订阅由 subscribeBoard 承接
    setConnectionState('connected');
    info('STOMP 已连接，保持连接并按新参数切换订阅', buildBrokerUrl(), traceTag());
    return;
  }
  // 病区锚点更新：后续重连路径 beforeConnect 据此透传当前病区签发（换病区强制重签）
  currentWardId = wardId;
  // 先行进入 connecting（覆盖令牌签发阶段，页面呼吸点承载过渡）；令牌就绪后才创建 Client
  // 并激活——异步链内聚，调用方（页面）无需感知
  setConnectionState('connecting');
  // 代际快照：本链以发起时刻的代际为准，取令牌在飞期间 disconnect 递增代际即判失配作废
  const generation = connectGeneration;
  void ensureBigscreenToken(wardId).then((tokenReady) => {
    // 代际失配=发起后已发生 disconnect（组件卸载/切换）：本次建连链整体作废——不建 Client
    // 不激活不置失败态，防「卸载后 WS 被激活且永不再断开」的连接泄漏
    if (generation !== connectGeneration) {
      return;
    }
    if (!tokenReady) {
      // 初始建连取不到令牌：拒绝建连（零 Client 创建零激活），置失败横幅态
      tokenFailedRef.value = true;
      setConnectionState('disconnected');
      warn('连接被拒绝：护理大屏订阅令牌运行期获取失败，数据链路已禁用');
      return;
    }
    tokenFailedRef.value = false;
    connectionTraceId = generateTraceId();
    getOrCreateClient().activate();
    info('STOMP 连接发起', buildBrokerUrl(), traceTag());
  });
}

/**
 * 订阅护理 board 主题（单槽位）：重复调用自动替换在册订阅（换病区重订阅语义）。连接未落地
 * 时登记待订阅，onConnect 转正；统一返回代理句柄，退订单出口保证在册一致。
 *
 * @param wardId 病区编码（topic 尾段直订零映射），非空；非法抛错拒绝订阅（页面入口已先行校验）
 * @param onFrame 合法信封帧回调（五类型判别式分发由调用方承载）
 * @return 订阅代理句柄（unsubscribe 幂等，组件卸载必须调用——web B.3-3 卸载条款）
 */
export function subscribeBoard(
  wardId: string,
  onFrame: (frame: NursingBoardFrame) => void,
): StompSubscription {
  if (!isValidWardId(wardId)) {
    throw new Error('病区编码不得为空，已拒绝订阅');
  }
  const destination = boardTopicPath(wardId);
  // 病区锚点更新：换病区重订阅后重连路径必须按新病区签发（禁复用旧病区令牌订新病区）
  currentWardId = wardId;
  // 换病区重订阅：先退订在册订阅，防旧主题帧继续流入页面
  unsubscribeBoard();
  const record = { destination, onFrame, handle: null as StompSubscription | null };
  boardSubscription = record;
  if (client !== null && client.connected) {
    // 已连接：立即落地真实订阅
    record.handle = doSubscribe(record);
    info('已订阅护理 board 主题', destination, traceTag());
  } else {
    info('已登记护理 board 待订阅（连接建立后自动落地）', destination, traceTag());
  }
  return {
    id: 'nursing-board',
    unsubscribe: () => {
      unsubscribeBoard();
    },
  };
}

/**
 * 主动断开（用户切换/组件卸载共用）：先递增代际作废在飞建连链（异步取令牌未落地的 connect
 * 不得在本次断开后激活 WS——disconnect 时 client 尚为 null 的窗口由代际比对兜底），再退订
 * 在册订阅后 deactivate——deactivate 取消库内建重连计划；状态回归 disconnected。
 */
export async function disconnect(): Promise<void> {
  connectGeneration += 1;
  unsubscribeBoard();
  stateChangeListener = null;
  if (client !== null) {
    await client.deactivate();
  }
  setConnectionState('disconnected');
  info('STOMP 连接已主动断开', traceTag());
}
