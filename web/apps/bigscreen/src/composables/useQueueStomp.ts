/**
 * bigscreen 门诊叫号 STOMP 连接单例封装（镜像 useIotStomp 范式——web 宪法 B.3-3 逐条款落点，
 * 队列大屏唯一建连入口；与遥测页 useIotStomp 并存，两页演示面互斥使用不并发建连）：
 *
 * 1. 【单例 Client】模块级缓存、首次 connect() 惰性创建（App.spec 冒烟挂载零网络副作用）；
 * 2. 【重连与心跳全交库内建】reconnectDelay=10000 + 双向心跳 10s（禁自研循环，B.3-3）；
 * 3. 【订阅句柄统一管理】subscribeQueue 返回代理句柄（退订单出口），连接落地前登记待订阅、
 *    onConnect 转正；断线重连由 onConnect 无条件重订阅（stompjs 7.3.0 无自动重订阅，
 *    onWebSocketClose/onStompError 先将在册句柄置 null 作废——useIotStomp PR-5 Finding 2 同款）；
 * 4. 【令牌经 beforeConnect 动态填 connectHeaders】凭证=构建期 VITE_BIGSCREEN_TOKEN（受控演示面，
 *    计划 Interfaces 冻结口径；订阅级鉴权/匿名 STOMP 通道随 P2 演进注记）。令牌为空时 connect()
 *    拒绝建连（页面承载「未配置大屏令牌」横幅 + 整链路零出网）；令牌值禁入任何日志（web A.6）；
 * 5. 【onStompError / onWebSocketClose 统一挂接】经 utils/logger 输出（含主题与 traceId）。
 *
 * <p>连接状态机（页面消费）：disconnected → connecting → connected；deactivate() 承载主动断开。
 *
 * <p>帧载荷：后端 QueueCalledNotice（type/ticketNo/patientName/doctorId/room，脱敏出网冻结口径，
 * P1 恒 CALLED；后续类型随通道演进登记）。毒帧 warn 留痕不中断订阅。
 */
import { Client } from '@stomp/stompjs';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { ref } from 'vue';
import type { Ref } from 'vue';
import { error as logError, info, warn } from '@/utils/logger';

/** 连接状态机三态（页面消费口径，与 useIotStomp 同构） */
export type QueueConnectionState = 'disconnected' | 'connecting' | 'connected';

/**
 * 叫号帧载荷（后端 QueueCalledNotice record 镜像，脱敏出网冻结口径）：字段全可选——毒帧防御
 * 逐字段收窄，type 缺失视为不合法帧。
 */
export interface QueueCalledNotice {
  type: string | null;
  ticketNo: string | null;
  patientName: string | null;
  doctorId: string | null;
  room: string | null;
}

/** 订阅主题路径参数合法性：非空（诊区编码形如 DEPT-INT，无数字约束） */
function isValidDeptCode(deptCode: string): boolean {
  return deptCode.trim() !== '';
}

/**
 * 从 unknown 收窄叫号帧载荷（毒帧防御）：对象形态 + type 为非空字符串才算合法，
 * 其余字段放宽为 string | null（后端 room 解析失败即 null）。
 */
function parseQueueCalledNotice(input: unknown): QueueCalledNotice | null {
  if (typeof input !== 'object' || input === null) {
    return null;
  }
  const record = input as Record<string, unknown>;
  if (typeof record['type'] !== 'string' || record['type'] === '') {
    return null;
  }
  const nullableString = (value: unknown): string | null =>
    typeof value === 'string' ? value : null;
  return {
    type: record['type'],
    ticketNo: nullableString(record['ticketNo']),
    patientName: nullableString(record['patientName']),
    doctorId: nullableString(record['doctorId']),
    room: nullableString(record['room']),
  };
}

/** 库内建固定重连间隔（毫秒，宪法 B.3-3 数值 10s） */
const RECONNECT_DELAY_MS = 10000;

/** 双向心跳间隔（毫秒，与后端 STOMP 心跳协商的宪法数值） */
const HEARTBEAT_MS = 10000;

/**
 * 大屏令牌（构建期 VITE_BIGSCREEN_TOKEN 注入；默认空=「未配置大屏令牌」横幅 + connect 拒建连）。
 * VITE_ 公开性红线（web A.2-2）由部署面承载：该值为受控演示凭证，随镜像构建注入、不入库真实值。
 */
export const bigscreenToken: string = import.meta.env.VITE_BIGSCREEN_TOKEN ?? '';

/** 令牌是否已配置（页面横幅与链路启用判定唯一来源） */
export function isQueueTokenConfigured(): boolean {
  return bigscreenToken !== '';
}

/** 全应用唯一 Client 实例（惰性创建，null=尚未首次建连） */
let client: Client | null = null;

/** 当前连接的链路 traceId（每次 connect() 重新生成，作日志锚点；禁与令牌并列输出） */
let connectionTraceId = '';

/** 叫号订阅在册记录（单槽位；handle=null=待连接落地转正） */
let queueSubscription: {
  destination: string;
  onFrame: (notice: QueueCalledNotice) => void;
  handle: StompSubscription | null;
} | null = null;

/** connect() 传入的状态变更回调（disconnect 时解除，防悬挂引用） */
let stateChangeListener: ((state: QueueConnectionState) => void) | null = null;

/** 连接状态可写源（模块内部状态翻转专用，禁止外泄） */
const connectionStateRef = ref<QueueConnectionState>('disconnected');

/** 连接状态（模块级单例 ref：全 app 唯一队列连接故状态源唯一，页面只读消费） */
export const connectionState: Readonly<Ref<QueueConnectionState>> = connectionStateRef;

/**
 * 生成叫号订阅主题路径（与后端 TriageServiceImpl.QUEUE_TOPIC_PREFIX 契约逐字对齐；
 * P0 客户端禁自加 /app 应用前缀）。
 *
 * @param deptCode 诊区编码（调用方已校验非空）
 * @return 主题路径，如 /topic/outpatient/queue/DEPT-INT
 */
export function queueTopicPath(deptCode: string): string {
  return `/topic/outpatient/queue/${deptCode}`;
}

/** 状态翻转：去重后更新单例 ref 并触发调用方回调 */
function setConnectionState(state: QueueConnectionState): void {
  if (connectionStateRef.value === state) {
    return;
  }
  connectionStateRef.value = state;
  stateChangeListener?.(state);
}

/** brokerURL 同源推导：ws/wss 随页面协议，路径 /ws/outpatient（dev 经 vite /ws 代理，生产经
 * nginx /ws/ 升级路由）——零新增 VITE_ 变量（§8.5 计划 Interfaces 冻结形态） */
function buildBrokerUrl(): string {
  const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
  return `${protocol}${location.host}/ws/outpatient`;
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
  if (queueSubscription !== null) {
    queueSubscription.handle = null;
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
    // 每次连接尝试（含断线自动重连）实时读模块级令牌常量拼 Bearer 头（构建期值，运行期恒定）
    beforeConnect: () => {
      if (client === null) {
        return;
      }
      if (bigscreenToken !== '') {
        client.connectHeaders = { Authorization: `Bearer ${bigscreenToken}` };
      } else {
        // 令牌缺失：本次尝试无凭证（预期被后端 CONNECT 帧鉴权拒绝）；warn 不含键值
        warn('STOMP 连接缺少大屏令牌，本次尝试将被服务端拒绝', traceTag());
      }
    },
    onConnect: () => {
      setConnectionState('connected');
      // 无条件重订阅（PR-5 Finding 2 同款）：重连路径与首连路径复用同一 doSubscribe 落地方法
      const record = queueSubscription;
      if (record !== null) {
        record.handle = doSubscribe(record);
        info('已订阅叫号主题', record.destination, traceTag());
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

/** 落地库订阅：帧回调内 try/catch 解析载荷，毒帧 warn 留痕不中断订阅 */
function doSubscribe(record: {
  destination: string;
  onFrame: (notice: QueueCalledNotice) => void;
}): StompSubscription {
  return getOrCreateClient().subscribe(record.destination, (message: IMessage) => {
    try {
      const notice = parseQueueCalledNotice(JSON.parse(message.body) as unknown);
      if (notice === null) {
        warn('叫号帧载荷不合法，已忽略本帧', record.destination, traceTag());
        return;
      }
      record.onFrame(notice);
    } catch {
      // 非法 JSON 毒帧：warn 留痕，订阅保持
      warn('叫号帧 JSON 解析失败，已忽略本帧', record.destination, traceTag());
    }
  });
}

/** 退订在册订阅（单出口：句柄未落地时仅清登记，防悬挂重复退订） */
function unsubscribeQueue(): void {
  if (queueSubscription === null) {
    return;
  }
  queueSubscription.handle?.unsubscribe();
  queueSubscription = null;
}

/**
 * 建连（队列大屏唯一入口）：令牌未配置直接拒建连（页面横幅承载，零网络副作用）；已连接态
 * 再次调用走「保持 connected + 不重复 activate」分支（useIotStomp PR-5 Finding 3 同款），
 * 订阅切换由紧随其后的 subscribeQueue 已连接分支承接。
 *
 * @param deptCode 诊区编码，非空字符串；非法拒建连（页面入口已先行校验）
 * @param onStateChange 连接状态变更回调（可选）
 */
export function connect(
  deptCode: string,
  onStateChange?: (state: QueueConnectionState) => void,
): void {
  if (!isValidDeptCode(deptCode)) {
    warn('连接被拒绝：诊区编码不得为空');
    return;
  }
  if (!isQueueTokenConfigured()) {
    // 未配置大屏令牌：拒绝建连且不创建 Client（页面显示未配置横幅，整链路零出网）
    warn('连接被拒绝：未配置大屏令牌（VITE_BIGSCREEN_TOKEN 为空）');
    return;
  }
  stateChangeListener = onStateChange ?? null;
  const activeClient = getOrCreateClient();
  if (activeClient.connected) {
    // 已连接：保持 connected 态、不重复 activate（no-op）；换诊区重订阅由 subscribeQueue 承接
    setConnectionState('connected');
    info('STOMP 已连接，保持连接并按新参数切换订阅', buildBrokerUrl(), traceTag());
    return;
  }
  connectionTraceId = generateTraceId();
  setConnectionState('connecting');
  activeClient.activate();
  info('STOMP 连接发起', buildBrokerUrl(), traceTag());
}

/**
 * 订阅叫号主题（单槽位）：重复调用自动替换在册订阅（换诊区重订阅语义）。连接未落地时登记
 * 待订阅，onConnect 转正；统一返回代理句柄，退订单出口保证在册一致。
 *
 * @param deptCode 诊区编码，非空；非法抛错拒绝订阅（页面入口已先行校验）
 * @param onFrame 合法叫号帧回调（当前叫号卡由调用方按 ticketNo 变化驱动三段编排）
 * @return 订阅代理句柄（unsubscribe 幂等，组件卸载必须调用——web B.3-3 卸载条款）
 */
export function subscribeQueue(
  deptCode: string,
  onFrame: (notice: QueueCalledNotice) => void,
): StompSubscription {
  if (!isValidDeptCode(deptCode)) {
    throw new Error('诊区编码不得为空，已拒绝订阅');
  }
  const destination = queueTopicPath(deptCode);
  // 换诊区重订阅：先退订在册订阅，防旧主题帧继续流入页面
  unsubscribeQueue();
  const record = { destination, onFrame, handle: null as StompSubscription | null };
  queueSubscription = record;
  if (client !== null && client.connected) {
    // 已连接：立即落地真实订阅
    record.handle = doSubscribe(record);
    info('已订阅叫号主题', destination, traceTag());
  } else {
    info('已登记叫号待订阅（连接建立后自动落地）', destination, traceTag());
  }
  return {
    id: 'outpatient-queue-called',
    unsubscribe: () => {
      unsubscribeQueue();
    },
  };
}

/**
 * 主动断开（用户切换/组件卸载共用）：先退订在册订阅再 deactivate——deactivate 取消库内建
 * 重连计划；状态回归 disconnected。
 */
export async function disconnect(): Promise<void> {
  unsubscribeQueue();
  stateChangeListener = null;
  if (client !== null) {
    await client.deactivate();
  }
  setConnectionState('disconnected');
  info('STOMP 连接已主动断开', traceTag());
}
