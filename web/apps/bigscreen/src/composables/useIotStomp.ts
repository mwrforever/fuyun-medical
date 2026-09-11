/**
 * bigscreen 应用级 STOMP 连接单例封装（web 宪法 B.3-3 逐条款合规落点，全 app 唯一建连入口）：
 *
 * 1. 【单例 Client】模块级缓存、首次 connect() 时惰性创建（非模块加载期——保证 App.spec 冒烟
 *    挂载零网络副作用），禁止组件各自建连；
 * 2. 【重连与心跳全交库内建】reconnectDelay=10000（@stomp/stompjs 7.3.0 内建固定间隔重试，
 *    禁自研 setInterval/setTimeout 退避循环）+ 双向心跳 10s 与后端协商（宪法 B.3-3 括号内
 *    「指数退避」与该库实况存在措辞出入，随 P1 修宪，合规核心=禁自研循环，见 CHANGELOG
 *    2026-09-11 登记）；
 * 3. 【订阅句柄统一管理】subscribeTelemetrySummary 统一返回代理句柄（退订单出口），连接落地前
 *    登记待订阅、onConnect 转正；断线重连后由 onConnect 无条件重订阅——stompjs 7.3.0 在
 *    onWebSocketClose 时整体作废 _stompHandler 且库内无自动重订阅（T-R4-2 实测结论），故
 *    onWebSocketClose/onStompError 先将在册句柄置 null 作废（不置 null 会让重连跳过重订阅，
 *    形成「徽标已连接、零帧流入」假连接，PR-5 Finding 2）；组件卸载 unsubscribe 由
 *    useIotTelemetry 的 onUnmounted 承接，显式 disconnect() 先退订在册订阅再 deactivate；
 * 4. 【token 经 beforeConnect 动态注入】每次连接尝试（含断线自动重连）实时读 sessionStorage 键
 *    fy:bigscreen:iot-token 拼 Bearer 头进 STOMP CONNECT 帧（后端帧级鉴权唯一输入，PR-5
 *    Finding 1 迁移后口径）——重连自动携带最新令牌，禁构造参数固化一次性 token、禁 URL/query
 *    传递（web A.2-2/A.6 红线：令牌与该键值禁入任何日志）；
 * 5. 【onStompError / onWebSocketClose 统一挂接】经 utils/logger 输出（含 brokerURL、订阅主题、
 *    连接 traceId），统一置 disconnected 态。
 *
 * <p>连接状态机（页面消费）：disconnected → connecting → connected；onConnect 置 connected、
 * onWebSocketClose 置 disconnected、onStompError 置 disconnected 并 error 留痕；deactivate()
 * 取消库内建重连计划（stompjs 库语义），正好承载「用户主动断开」。
 *
 * <p>sessionStorage 生命周期说明：令牌仅随标签页存活（刷新/路由跳转保留，支撑断线重连与页面
 * 回填），标签页关闭即清除——医疗终端「换机即失效」语义与 workstation auth store 同口径；
 * 主动断开不清令牌（用户重连免重复粘贴，重新注入时新值覆盖写入）。
 */
import { Client } from '@stomp/stompjs';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { ref } from 'vue';
import type { Ref } from 'vue';
import type { TelemetrySummary } from '@/types/iot';
import { parseTelemetrySummary } from '@/utils/iotMessage';
import { error as logError, info, warn } from '@/utils/logger';

/** 连接状态机三态（页面消费口径） */
export type IotConnectionState = 'disconnected' | 'connecting' | 'connected';

/** 连接参数对象（web A.7-1：形参 >3 整体传参） */
export interface StompConnectOptions {
  /** 访问令牌（M01 access），非空；来源：用户在连接设置区粘贴（登录接口获取后复制）。
   * 本函数写入 sessionStorage 后由 beforeConnect 每次尝试实时读取，令牌禁入日志 */
  token: string;
  /** 病区 ID（订阅主题路径参数），纯数字字符串；来源：路由 query 或连接设置区输入 */
  wardId: string;
  /** 连接状态变更回调（可选）：每次状态翻转触发，供调用方观察状态机 */
  onStateChange?: (state: IotConnectionState) => void;
}

/** 令牌 sessionStorage 键（冒号分层，与 workstation fy:workstation:auth 命名风格一致） */
export const IOT_TOKEN_STORAGE_KEY = 'fy:bigscreen:iot-token';

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

/** 遥测摘要订阅在册记录（P0 唯一消费主题，单槽位；handle=null=待连接落地转正） */
let telemetrySubscription: {
  destination: string;
  onFrame: (summary: TelemetrySummary) => void;
  handle: StompSubscription | null;
} | null = null;

/** connect() 传入的状态变更回调（ disconnect 时解除，防悬挂引用） */
let stateChangeListener: ((state: IotConnectionState) => void) | null = null;

/** 连接状态可写源（模块内部状态翻转专用，禁止外泄） */
const connectionStateRef = ref<IotConnectionState>('disconnected');

/** 连接状态（模块级单例 ref：全 app 唯一连接故状态源唯一，页面只读消费） */
export const connectionState: Readonly<Ref<IotConnectionState>> = connectionStateRef;

/**
 * 生成遥测摘要订阅主题路径（与后端 IotMessagingConstants.TOPIC_TELEMETRY_PREFIX 契约逐字对齐；
 * P0 客户端禁自加 /app 应用前缀——后端未配置应用前缀且无客户端出站消息）。
 *
 * @param wardId 病区 ID，纯数字字符串（调用方已校验）
 * @return 主题路径，如 /topic/iot/telemetry/1001
 */
export function telemetryTopicPath(wardId: string): string {
  return `/topic/iot/telemetry/${wardId}`;
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
 * 作废在册订阅句柄（置 null 是重连 onConnect 重订阅的前提，PR-5 Finding 2）：stompjs 7.3.0
 * 连接关闭后 _stompHandler 整体作废、旧订阅句柄已随连接失效且库内无自动重订阅——不清句柄
 * 会导致重连 onConnect 跳过重订阅的假连接。
 */
function invalidateSubscriptionHandle(): void {
  if (telemetrySubscription !== null) {
    telemetrySubscription.handle = null;
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
    // 每次连接尝试（含断线自动重连）实时读 sessionStorage 令牌：重连自动携带最新值
    beforeConnect: () => {
      if (client === null) {
        return;
      }
      const token = sessionStorage.getItem(IOT_TOKEN_STORAGE_KEY);
      if (token !== null && token !== '') {
        client.connectHeaders = { Authorization: `Bearer ${token}` };
      } else {
        // 令牌缺失：本次尝试无凭证（预期被后端 CONNECT 帧鉴权拒绝——ERROR 帧后连接关闭，
        // 按库内建周期重试）；warn 不含键值
        warn('STOMP 连接缺少访问令牌，本次尝试将被服务端拒绝', traceTag());
      }
    },
    onConnect: () => {
      setConnectionState('connected');
      // 无条件重订阅（PR-5 Finding 2）：stompjs 7.3.0 断线重连不自动恢复订阅，旧句柄已在
      // onWebSocketClose/onStompError 置 null 作废——重连路径与首连路径复用同一 doSubscribe
      // 落地方法（与已连接态换病区重订阅共用，防两处订阅逻辑漂移）
      const record = telemetrySubscription;
      if (record !== null) {
        record.handle = doSubscribe(record);
        info('已订阅遥测摘要主题', record.destination, traceTag());
      }
      info('STOMP 已连接', buildBrokerUrl(), traceTag());
    },
    onStompError: (frame) => {
      // broker ERROR 帧：在册订阅句柄随连接作废置 null，置断开态留痕，库将按内建周期重试；
      // 日志含主题与 traceId、禁含令牌
      invalidateSubscriptionHandle();
      setConnectionState('disconnected');
      logError('STOMP broker 错误帧', frame.headers['message'] ?? '', traceTag());
    },
    onWebSocketClose: () => {
      // 连接关闭（含 CONNECT 帧鉴权被拒的 ERROR+PROTOCOL_ERROR 关闭）：在册订阅句柄置 null
      // 作废并置断开态；重连完全由库内建 reconnectDelay 接管
      invalidateSubscriptionHandle();
      setConnectionState('disconnected');
      warn('STOMP 连接已关闭，等待库内建自动重连', buildBrokerUrl(), traceTag());
    },
  });
  return client;
}

/**
 * 落地库订阅：帧回调内 try/catch 解析载荷，毒帧 warn 留痕不中断订阅（等待下一帧自然恢复）。
 */
function doSubscribe(record: {
  destination: string;
  onFrame: (summary: TelemetrySummary) => void;
}): StompSubscription {
  return getOrCreateClient().subscribe(record.destination, (message: IMessage) => {
    try {
      const summary = parseTelemetrySummary(JSON.parse(message.body) as unknown);
      if (summary === null) {
        // 残缺载荷（结构不合法）：warn 留痕，订阅保持
        warn('遥测摘要帧载荷不合法，已忽略本帧', record.destination, traceTag());
        return;
      }
      record.onFrame(summary);
    } catch {
      // 非法 JSON 毒帧：warn 留痕，订阅保持
      warn('遥测摘要帧 JSON 解析失败，已忽略本帧', record.destination, traceTag());
    }
  });
}

/** 退订在册订阅（单出口：句柄未落地时仅清登记，防悬挂重复退订） */
function unsubscribeTelemetry(): void {
  if (telemetrySubscription === null) {
    return;
  }
  telemetrySubscription.handle?.unsubscribe();
  telemetrySubscription = null;
}

/**
 * 建连（全 app 唯一入口）：令牌写入 sessionStorage 后激活库连接，状态进入 connecting；
 * 已连接态再次调用（换病区场景）走「保持 connected + 不重复 activate」分支——stompjs
 * activate() 对已激活 Client 为 no-op，无条件置 connecting 会让状态机卡死在 connecting
 * （断开按钮 v-if connected 消失，PR-5 Finding 3），订阅切换由紧随其后的
 * subscribeTelemetrySummary 已连接分支承接。令牌为空时拒绝建连并 warn 提示（后端 CONNECT
 * 帧鉴权硬约束的前置拦截；提示注入令牌由页面承载）。
 *
 * @param options 连接参数对象（token/wardId/onStateChange），见 StompConnectOptions
 */
export function connect(options: StompConnectOptions): void {
  if (options.token === '') {
    warn('连接被拒绝：请先在连接设置区注入访问令牌');
    return;
  }
  // 令牌写入 sessionStorage（仅标签页周期存活）；beforeConnect 每次尝试实时读取，禁构造期固化
  sessionStorage.setItem(IOT_TOKEN_STORAGE_KEY, options.token);
  stateChangeListener = options.onStateChange ?? null;
  const activeClient = getOrCreateClient();
  if (activeClient.connected) {
    // 已连接：保持 connected 态、不置 connecting、不重复 activate（no-op）——traceId 保持
    // 当前连接会话值（未新建传输层），换病区重订阅由 subscribeTelemetrySummary 立即落地
    setConnectionState('connected');
    info('STOMP 已连接，保持连接并按新参数切换订阅', buildBrokerUrl(), traceTag());
    return;
  }
  // 每次连接生成链路 traceId 作日志锚点（不与令牌同帧输出）
  connectionTraceId = crypto.randomUUID();
  setConnectionState('connecting');
  activeClient.activate();
  info('STOMP 连接发起', buildBrokerUrl(), traceTag());
}

/**
 * 订阅遥测摘要主题（P0 唯一消费主题）：重复调用自动替换在册订阅（换病区重订阅语义）。
 * 连接未落地时登记待订阅，onConnect 转正；统一返回代理句柄，退订单出口保证在册一致。
 *
 * @param wardId 病区 ID，纯数字字符串；非法（含非数字字符）抛错拒绝订阅（页面入口已先行校验）
 * @param onFrame 合法摘要帧回调（新帧覆盖由调用方自行承载）
 * @return 订阅代理句柄（unsubscribe 幂等，组件卸载必须调用——web B.3-3 卸载条款）
 */
export function subscribeTelemetrySummary(
  wardId: string,
  onFrame: (summary: TelemetrySummary) => void,
): StompSubscription {
  if (!WARD_ID_PATTERN.test(wardId)) {
    throw new Error('病区 ID 必须为纯数字字符串，已拒绝订阅');
  }
  const destination = telemetryTopicPath(wardId);
  // 换病区重订阅：先退订在册订阅，防旧主题帧继续流入页面
  unsubscribeTelemetry();
  const record = { destination, onFrame, handle: null as StompSubscription | null };
  telemetrySubscription = record;
  if (client !== null && client.connected) {
    // 已连接：立即落地真实订阅
    record.handle = doSubscribe(record);
    info('已订阅遥测摘要主题', destination, traceTag());
  } else {
    info('已登记遥测摘要待订阅（连接建立后自动落地）', destination, traceTag());
  }
  return {
    id: 'iot-telemetry-summary',
    unsubscribe: () => {
      unsubscribeTelemetry();
    },
  };
}

/**
 * 主动断开（用户点击「断开」/组件卸载共用）：先退订在册订阅再 deactivate——库语义为
 * deactivate 取消内建重连计划，正好承载「用户主动断开」；状态回归 disconnected。
 */
export async function disconnect(): Promise<void> {
  unsubscribeTelemetry();
  stateChangeListener = null;
  if (client !== null) {
    await client.deactivate();
  }
  setConnectionState('disconnected');
  info('STOMP 连接已主动断开', traceTag());
}
