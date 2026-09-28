/**
 * workstation 应用级 STOMP 连接单例封装（bigscreen useIotStomp 同构移植，全 app 唯一建连入口，
 * web 宪法 B.3-3 逐条款合规落点）。与 bigscreen 范本的三点差异（移植申报面）：
 *
 * 1. 【令牌面换会话源】bigscreen 为用户粘贴令牌写 sessionStorage 专键；workstation 会话令牌
 *    已由 auth store 统一承载（sessionStorage 键 fy:workstation:auth 快照），故 beforeConnect
 *    每次连接尝试（含断线自动重连）实时读 useAuthStore().token 拼 Bearer 头进 CONNECT 帧——
 *    未登录（token=null）connect 直接拒绝建连；令牌轮换后重连自动携带最新值；禁 URL/query
 *    传递，令牌禁入任何日志（web A.2-2/A.6 红线）；
 * 2. 【单槽位改多主题登记】bigscreen 仅遥测摘要单主题单槽位；病区输液看板需遥测摘要与告警
 *    双主题并行订阅，故在册记录改为列表（按目的地替换语义：同目的地重订阅先退旧再订新），
 *    onConnect 无条件重订阅全部在册主题；
 * 3. 【语义收窄下沉消费方】本模块只保证帧体 JSON.parse 成功即投递 unknown，遥测摘要/告警
 *    载荷的逐字段收窄归 utils/iotMessage 纯函数承载（bigscreen 将收窄内联在订阅回调内）。
 *
 * 其余形态照抄范本：模块级惰性单例 Client（首 connect 时创建，禁组件各自建连）；重连与心跳
 * 全交库内建（reconnectDelay=10000 固定间隔 + 双向心跳 10s，禁自研退避循环，宪法 B.3-3）；
 * stompjs 7.3.0 断线重连不自动恢复订阅（T-R4-2 实测结论），onWebSocketClose/onStompError 先
 * 将在册句柄全部置 null 作废、onConnect 重订阅（防「徽标已连接、零帧流入」假连接）；组件卸载
 * unsubscribe 由调用方承接，显式 disconnect() 先退订全部在册订阅再 deactivate。
 *
 * <p>连接状态机（页面消费）：disconnected → connecting → connected；onConnect 置 connected、
 * onWebSocketClose/onStompError 置 disconnected；deactivate() 取消库内建重连计划，承载
 * 「用户主动断开」。
 */
import { Client } from '@stomp/stompjs';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { ref } from 'vue';
import type { Ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { error as logError, info, warn } from '@/utils/logger';

/** 连接状态机三态（页面消费口径） */
export type IotConnectionState = 'disconnected' | 'connecting' | 'connected';

/** 连接参数对象（web A.7-1：形参 >3 整体传参；令牌不再入参——会话源唯一归 auth store） */
export interface StompConnectOptions {
  /** 连接状态变更回调（可选）：每次状态翻转触发，供调用方观察状态机 */
  onStateChange?: (state: IotConnectionState) => void;
}

/** 在册订阅记录（handle=null=待连接落地转正，或已随断线作废） */
interface TopicSubscriptionRecord {
  /** 订阅目的地（STOMP 主题路径，与后端 IotMessagingConstants 契约逐字对齐） */
  destination: string;
  /** 帧回调（JSON.parse 成功的 unknown 载荷；语义收窄归消费方） */
  onFrame: (payload: unknown) => void;
  /** 库订阅句柄（null=未落地/已作废） */
  handle: StompSubscription | null;
}

/** 库内建固定重连间隔（毫秒，宪法 B.3-3 数值 10s） */
const RECONNECT_DELAY_MS = 10000;

/** 双向心跳间隔（毫秒，与后端 STOMP 心跳协商的宪法数值） */
const HEARTBEAT_MS = 10000;

/** 病区 ID 合法形态：纯数字字符串（主题路径参数以字符串承载禁数值化——web A.3-6 精神） */
const WARD_ID_PATTERN = /^\d+$/;

/** 全应用唯一 Client 实例（惰性创建，null=尚未首次建连） */
let client: Client | null = null;

/** 当前连接的链路 traceId（每次 connect() 重新生成，作日志锚点；禁与令牌并列输出） */
let connectionTraceId = '';

/** 在册订阅列表（多主题并行；同目的地替换语义，退订单出口保证在册一致） */
const subscriptions: TopicSubscriptionRecord[] = [];

/** connect() 传入的状态变更回调（disconnect 时解除，防悬挂引用） */
let stateChangeListener: ((state: IotConnectionState) => void) | null = null;

/** 连接状态可写源（模块内部状态翻转专用，禁止外泄） */
const connectionStateRef = ref<IotConnectionState>('disconnected');

/** 连接状态（模块级单例 ref：全 app 唯一连接故状态源唯一，页面只读消费） */
export const connectionState: Readonly<Ref<IotConnectionState>> = connectionStateRef;

/**
 * 生成遥测摘要订阅主题路径（与后端 IotMessagingConstants.TOPIC_TELEMETRY_PREFIX 契约逐字对齐；
 * P0 客户端禁自加 /app 应用前缀——后端未配置应用前缀且无客户端出站消息）。
 *
 * @param wardId 病区 ID，纯数字字符串；非法（含非数字字符）抛错拒绝（调用方先行校验锚点）
 * @return 主题路径，如 /topic/iot/telemetry/1001
 */
export function telemetryTopicPath(wardId: string): string {
  if (!WARD_ID_PATTERN.test(wardId)) {
    throw new Error('病区 ID 必须为纯数字字符串，已拒绝订阅');
  }
  return `/topic/iot/telemetry/${wardId}`;
}

/**
 * 生成病区告警订阅主题路径（与后端 IotMessagingConstants.TOPIC_ALARM_PREFIX 契约逐字对齐，
 * P2 PR-2 Task 7 推送面）。
 *
 * @param wardId 病区 ID，纯数字字符串；非法抛错拒绝
 * @return 主题路径，如 /topic/iot/alarm/1001
 */
export function alarmTopicPath(wardId: string): string {
  if (!WARD_ID_PATTERN.test(wardId)) {
    throw new Error('病区 ID 必须为纯数字字符串，已拒绝订阅');
  }
  return `/topic/iot/alarm/${wardId}`;
}

/** 状态翻转：去重后更新单例 ref 并触发调用方回调 */
function setConnectionState(state: IotConnectionState): void {
  if (connectionStateRef.value === state) {
    return;
  }
  connectionStateRef.value = state;
  stateChangeListener?.(state);
}

/** brokerURL 同源推导：ws/wss 随页面协议，路径 /ws/iot（dev 经 vite /ws 代理，生产经 nginx
 * /ws/ 升级路由）——零新增 VITE_ 变量，免 .env.example 与 ImportMetaEnv 三处同步 */
function buildBrokerUrl(): string {
  const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
  return `${protocol}${location.host}/ws/iot`;
}

/** 日志 traceId 锚点（未建连时以 - 占位） */
function traceTag(): string {
  return `traceId=${connectionTraceId || '-'}`;
}

/**
 * 生成连接链路 traceId（仅作日志锚点，非密码学用途）：安全上下文（HTTPS/localhost）用
 * crypto.randomUUID；HTTP 内网部署（仓库拓扑 nginx :80 无 TLS）下 randomUUID 因
 * [SecureContext] 限定为 undefined，降级为时间戳+随机数组合串（bigscreen PR-5 Finding 4 同口径）。
 */
function generateTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 作废全部在册订阅句柄（置 null 是重连 onConnect 重订阅的前提）：stompjs 7.3.0 连接关闭后
 * _stompHandler 整体作废、旧订阅句柄已随连接失效且库内无自动重订阅——不清句柄会导致重连
 * onConnect 跳过重订阅的假连接（bigscreen PR-5 Finding 2 移植结论）。
 */
function invalidateSubscriptionHandles(): void {
  for (const record of subscriptions) {
    record.handle = null;
  }
}

/**
 * 惰性创建全应用唯一 Client（仅首次 connect 时执行；配置参数为 web B.3-3 合规锚点）。
 */
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
    // 每次连接尝试（含断线自动重连）实时读会话令牌：轮换后重连自动携带最新值。
    // useAuthStore 运行时调用（此时 pinia 必已激活——建连仅由已挂载页面触发，web B.3-1 口径）
    beforeConnect: () => {
      if (client === null) {
        return;
      }
      const token = useAuthStore().token;
      if (token !== null && token !== '') {
        client.connectHeaders = { Authorization: `Bearer ${token}` };
      } else {
        // 会话令牌缺失：本次尝试无凭证（预期被后端 CONNECT 帧鉴权拒绝——ERROR 帧后连接关闭，
        // 按库内建周期重试）；warn 不含键值
        warn('STOMP 连接缺少会话令牌，本次尝试将被服务端拒绝', traceTag());
      }
    },
    onConnect: () => {
      setConnectionState('connected');
      // 无条件重订阅全部在册主题（stompjs 7.3.0 断线重连不自动恢复订阅，旧句柄已在
      // onWebSocketClose/onStompError 置 null 作废——重连路径与首连路径复用同一 doSubscribe
      // 落地方法，防两处订阅逻辑漂移）
      for (const record of subscriptions) {
        record.handle = doSubscribe(record);
        info('已订阅主题', record.destination, traceTag());
      }
      info('STOMP 已连接', buildBrokerUrl(), traceTag());
    },
    onStompError: (frame) => {
      // broker ERROR 帧：在册订阅句柄随连接作废置 null，置断开态留痕，库将按内建周期重试；
      // 日志含主题与 traceId、禁含令牌
      invalidateSubscriptionHandles();
      setConnectionState('disconnected');
      logError('STOMP broker 错误帧', frame.headers['message'] ?? '', traceTag());
    },
    onWebSocketClose: () => {
      // 连接关闭（含 CONNECT 帧鉴权被拒的 ERROR+PROTOCOL_ERROR 关闭）：在册订阅句柄置 null
      // 作废并置断开态；重连完全由库内建 reconnectDelay 接管
      invalidateSubscriptionHandles();
      setConnectionState('disconnected');
      warn('STOMP 连接已关闭，等待库内建自动重连', buildBrokerUrl(), traceTag());
    },
  });
  return client;
}

/**
 * 落地库订阅：帧回调内 try/catch 解析载荷，非法 JSON 毒帧 warn 留痕不中断订阅（等待下一帧
 * 自然恢复）；载荷语义收窄归消费方 utils/iotMessage 纯函数承载。
 */
function doSubscribe(record: TopicSubscriptionRecord): StompSubscription {
  return getOrCreateClient().subscribe(record.destination, (message: IMessage) => {
    try {
      record.onFrame(JSON.parse(message.body) as unknown);
    } catch {
      // 非法 JSON 毒帧：warn 留痕，订阅保持
      warn('STOMP 帧体 JSON 解析失败，已忽略本帧', record.destination, traceTag());
    }
  });
}

/** 退订在册订阅（单出口：句柄未落地时仅清登记，防悬挂重复退订） */
function unsubscribeRecord(record: TopicSubscriptionRecord): void {
  record.handle?.unsubscribe();
  const index = subscriptions.indexOf(record);
  if (index >= 0) {
    subscriptions.splice(index, 1);
  }
}

/**
 * 建连（全 app 唯一入口）：状态进入 connecting 并激活库连接；令牌取自 auth store 会话
 * （未登录即 null 时拒绝建连并 warn——后端 CONNECT 帧鉴权硬约束的前置拦截，登录由路由守卫
 * 承载）。已连接态再次调用为 no-op 保持 connected——stompjs activate() 对已激活 Client 为
 * no-op，无条件置 connecting 会让状态机卡死在 connecting（bigscreen PR-5 Finding 3 同口径）。
 *
 * @param options 连接参数对象（可选，onStateChange 观察状态机）
 */
export function connect(options?: StompConnectOptions): void {
  const token = useAuthStore().token;
  if (token === null || token === '') {
    warn('STOMP 连接被拒绝：会话无访问令牌，请先登录');
    return;
  }
  stateChangeListener = options?.onStateChange ?? null;
  const activeClient = getOrCreateClient();
  if (activeClient.connected) {
    // 已连接：保持 connected 态、不置 connecting、不重复 activate（no-op）——traceId 保持
    // 当前连接会话值（未新建传输层），新主题订阅由 subscribeTopic 已连接分支立即落地
    setConnectionState('connected');
    info('STOMP 已连接，保持连接，新主题订阅直接落地', buildBrokerUrl(), traceTag());
    return;
  }
  // 每次连接生成链路 traceId 作日志锚点（不与令牌同帧输出；兼容非安全上下文降级）
  connectionTraceId = generateTraceId();
  setConnectionState('connecting');
  activeClient.activate();
  info('STOMP 连接发起', buildBrokerUrl(), traceTag());
}

/**
 * 订阅主题（多主题并行，按目的地替换语义）：同目的地重复调用自动退旧订新，不同目的地各自
 * 独立在册。连接未落地时登记待订阅，onConnect 转正；统一返回代理句柄，退订单出口保证在册
 * 一致。
 *
 * @param destination 主题路径（经 telemetryTopicPath/alarmTopicPath 契约构造，禁手拼漂移）
 * @param onFrame 帧回调（JSON.parse 成功的 unknown 载荷；语义收窄归消费方）
 * @return 订阅代理句柄（unsubscribe 幂等，组件卸载必须调用——web B.3-3 卸载条款）
 */
export function subscribeTopic(
  destination: string,
  onFrame: (payload: unknown) => void,
): StompSubscription {
  // 同目的地替换：先退订在册同主题订阅，防旧回调重复消费同帧
  const existing = subscriptions.find((record) => record.destination === destination);
  if (existing !== undefined) {
    unsubscribeRecord(existing);
  }
  const record: TopicSubscriptionRecord = { destination, onFrame, handle: null };
  subscriptions.push(record);
  if (client !== null && client.connected) {
    // 已连接：立即落地真实订阅
    record.handle = doSubscribe(record);
    info('已订阅主题', destination, traceTag());
  } else {
    info('已登记主题待订阅（连接建立后自动落地）', destination, traceTag());
  }
  return {
    id: `iot-topic-${destination}`,
    unsubscribe: () => {
      unsubscribeRecord(record);
    },
  };
}

/**
 * 主动断开（组件卸载/页面离开共用）：先退订全部在册订阅再 deactivate——库语义为 deactivate
 * 取消内建重连计划，正好承载「用户主动断开」；状态回归 disconnected。
 */
export async function disconnect(): Promise<void> {
  while (subscriptions.length > 0) {
    unsubscribeRecord(subscriptions[0]);
  }
  stateChangeListener = null;
  if (client !== null) {
    await client.deactivate();
  }
  setConnectionState('disconnected');
  info('STOMP 连接已主动断开', traceTag());
}
