/**
 * workstation 应用级 STOMP 连接封装（bigscreen useIotStomp 同构移植后升级为多端点登记面，
 * web 宪法 B.3-3 逐条款合规落点）。与单端点版的三点差异（批次 2 册 2 多端点改造申报面）：
 *
 * 1. 【单 Client 改端点注册表】事件流指引（/v1/ops/workbench/events topics 清单）覆盖
 *    /ws/iot、/ws/nursing、/ws/outpatient 三个既有 WS 端点——一条 STOMP 连接只能绑定一个
 *    brokerURL，故模块内改为「每端点一套 Client + 在册订阅 + 状态机」的注册表（Map），
 *    惰性建连不变；建连出口仍收敛在本模块（组件禁各自建连，B.3-3 铁律不松动）。
 * 2. 【缺省端点向后兼容】telemetryTopicPath/alarmTopicPath 消费方（输液看板）沿用
 *    /ws/iot：subscribeTopic/connect/disconnect 的端点参数均可缺省（= /ws/iot），导出的
 *    connectionState 亦恒指缺省端点——既有调用点零改动。
 * 3. 【主题模板填充函数】指引清单的主题为模板形态（/topic/outpatient/queue/{deptCode}），
 *    新增 fillTopicTemplate 纯函数承载占位符填充与残留校验（未填满/含非法字符即抛错拒绝），
 *    防调用方手拼主题路径漂移。
 *
 * 其余形态照抄范本：重连与心跳全交库内建（reconnectDelay=10000 固定间隔 + 双向心跳 10s，
 * 禁自研退避循环）；stompjs 7.3.0 断线重连不自动恢复订阅（T-R4-2 实测结论），
 * onWebSocketClose/onStompError 先将在册句柄全部置 null 作废、onConnect 重订阅（防「徽标已
 * 连接、零帧流入」假连接）；token 经 beforeConnect 每次连接尝试实时读 auth store 会话拼
 * Bearer 头（未登录拒绝建连；令牌禁入 URL 与任何日志）；订阅句柄退订由调用方在组件卸载时
 * 承接（统一代理句柄单出口）；显式 disconnect 不带端点参数时退订并断开全部端点（保守清理，
 * 防跨视图连接泄漏）。
 *
 * <p>连接状态机（页面消费，每端点独立）：disconnected → connecting → connected；onConnect 置
 * connected、onWebSocketClose/onStompError 置 disconnected；deactivate() 取消库内建重连计划，
 * 承载「用户主动断开」。
 */
import { Client } from '@stomp/stompjs';
import type { IMessage, StompSubscription } from '@stomp/stompjs';
import { ref } from 'vue';
import type { Ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { error as logError, info, warn } from '@/utils/logger';

/** 连接状态机三态（页面消费口径） */
export type IotConnectionState = 'disconnected' | 'connecting' | 'connected';

/** 缺省 STOMP 端点（既有遥测/告警消费面；IoT 管理域 WebSocket 配置的唯一路径） */
export const DEFAULT_STOMP_ENDPOINT = '/ws/iot';

/** 连接参数对象（web A.7-1：形参 >3 整体传参；令牌不再入参——会话源唯一归 auth store） */
export interface StompConnectOptions {
  /** 连接状态变更回调（可选）：每次状态翻转触发，供调用方观察状态机 */
  onStateChange?: (state: IotConnectionState) => void;
}

/** 在册订阅记录（handle=null=待连接落地转正，或已随断线作废） */
interface TopicSubscriptionRecord {
  /** 订阅目的地（STOMP 主题路径，与后端消息常量/事件流指引契约逐字对齐） */
  destination: string;
  /** 帧回调（JSON.parse 成功的 unknown 载荷；语义收窄归消费方） */
  onFrame: (payload: unknown) => void;
  /** 库订阅句柄（null=未落地/已作废） */
  handle: StompSubscription | null;
}

/** 单端点连接条目：每 WS 端点独立一套 Client、链路 traceId、状态机与在册订阅 */
interface EndpointEntry {
  /** WS 端点路径（如 /ws/iot、/ws/outpatient，作 brokerURL 尾段与注册表键） */
  endpoint: string;
  /** 库 Client 实例（null=尚未首次建连；惰性创建禁组件各自建连） */
  client: Client | null;
  /** 当前连接的链路 traceId（每次 connect() 重新生成，作日志锚点；禁与令牌并列输出） */
  traceId: string;
  /** 连接状态可写源（模块内部状态翻转专用，禁止外泄可写引用） */
  stateRef: Ref<IotConnectionState>;
  /** connect() 传入的状态变更回调（disconnect 时解除，防悬挂引用） */
  stateChangeListener: ((state: IotConnectionState) => void) | null;
  /** 在册订阅列表（多主题并行；同目的地替换语义，退订单出口保证在册一致） */
  subscriptions: TopicSubscriptionRecord[];
}

/** 库内建固定重连间隔（毫秒，宪法 B.3-3 数值 10s） */
const RECONNECT_DELAY_MS = 10000;

/** 双向心跳间隔（毫秒，与后端 STOMP 心跳协商的宪法数值） */
const HEARTBEAT_MS = 10000;

/** 病区 ID 合法形态：纯数字字符串（主题路径参数以字符串承载禁数值化——web A.3-6 精神） */
const WARD_ID_PATTERN = /^\d+$/;

/** 主题路径片段非法字符（空白/控制符/通配符：STOMP 目的地禁空格，通配符只属订阅语义） */
const TOPIC_FRAGMENT_PATTERN = /[\s{}*#]/;

/** 端点注册表（每 WS 端点一套条目；键=端点路径） */
const endpointEntries = new Map<string, EndpointEntry>();

/**
 * 取（或惰性创建）端点条目：创建仅分配状态对象（零网络副作用），Client 仍留待首次 connect。
 *
 * @param endpoint WS 端点路径（/ws/iot 等，非空）
 * @return 该端点的连接条目（注册表内稳定复用）
 */
function entryOf(endpoint: string): EndpointEntry {
  let entry = endpointEntries.get(endpoint);
  if (entry === undefined) {
    entry = {
      endpoint,
      client: null,
      traceId: '',
      stateRef: ref<IotConnectionState>('disconnected'),
      stateChangeListener: null,
      subscriptions: [],
    };
    endpointEntries.set(endpoint, entry);
  }
  return entry;
}

/** 缺省端点条目（模块载入即建：导出的 connectionState 需要稳定 ref，建条目零连接副作用） */
const defaultEntry = entryOf(DEFAULT_STOMP_ENDPOINT);

/** 缺省端点连接状态（模块级单例 ref：输液看板等既有消费方只读消费，向后兼容面） */
export const connectionState: Readonly<Ref<IotConnectionState>> = defaultEntry.stateRef;

/**
 * 读任意端点的连接状态（只读）：首页事件流按指引端点聚合「实时推送已连接/轮询模式」指示。
 * 读态会惰性创建条目（零网络副作用），与 subscribeTopic 的条目同一份（状态同源）。
 *
 * @param endpoint WS 端点路径（如 /ws/outpatient）
 * @return 该端点连接状态只读 ref
 */
export function connectionStateOf(endpoint: string): Readonly<Ref<IotConnectionState>> {
  return entryOf(endpoint).stateRef;
}

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

/**
 * 填充主题路径模板（多端点改造新增：事件流指引清单的主题为 {参数} 模板形态）。
 * 纯函数：占位符键全量供给才放行——任一占位符缺失（填充后残留 { }）或参数值含空白/通配符
 * 等非法字符即抛错拒绝，防手拼漂移与目的地注入。
 *
 * @param template 主题模板（来源：/v1/ops/workbench/events 响应 topics[].topic，后端契约）
 * @param params 占位符取值表（键=模板占位符名如 deptCode，值=真实业务编码如候诊表 deptCode）
 * @return 填充后的可订阅主题路径
 * @throws Error 占位符未填满或参数值含非法字符（调用方应捕获后跳过该订阅并 warn 留痕）
 */
export function fillTopicTemplate(template: string, params: Record<string, string>): string {
  const filled = template.replace(/\{(\w+)\}/g, (placeholder, key: string) => {
    const value = params[key];
    if (value === undefined || value === '') {
      // 占位符缺参：原样保留（由下方残留校验统一拒绝），不猜测语义填充
      return placeholder;
    }
    return value;
  });
  if (filled.includes('{') || filled.includes('}')) {
    throw new Error(`主题模板占位符未填满，拒绝订阅：${template}`);
  }
  if (TOPIC_FRAGMENT_PATTERN.test(filled)) {
    throw new Error(`主题路径含非法字符（空白/通配符），拒绝订阅：${template}`);
  }
  return filled;
}

/**
 * 单端点状态翻转：去重后更新条目状态 ref 并触发调用方回调。
 */
function setConnectionState(entry: EndpointEntry, state: IotConnectionState): void {
  if (entry.stateRef.value === state) {
    return;
  }
  entry.stateRef.value = state;
  entry.stateChangeListener?.(state);
}

/** brokerURL 同源推导：ws/wss 随页面协议，路径=端点参数（dev 经 vite /ws 代理，生产经 nginx
 * /ws/ 升级路由）——零新增 VITE_ 变量，免 .env.example 与 ImportMetaEnv 三处同步 */
function buildBrokerUrl(endpoint: string): string {
  const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
  return `${protocol}${location.host}${endpoint}`;
}

/** 日志 traceId 锚点（未建连时以 - 占位） */
function traceTag(entry: EndpointEntry): string {
  return `traceId=${entry.traceId || '-'}`;
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
 * 作废端点全部在册订阅句柄（置 null 是重连 onConnect 重订阅的前提）：stompjs 7.3.0 连接关闭后
 * _stompHandler 整体作废、旧订阅句柄已随连接失效且库内无自动重订阅——不清句柄会导致重连
 * onConnect 跳过重订阅的假连接（bigscreen PR-5 Finding 2 移植结论）。
 */
function invalidateSubscriptionHandles(entry: EndpointEntry): void {
  for (const record of entry.subscriptions) {
    record.handle = null;
  }
}

/**
 * 惰性创建端点 Client（仅该端点首次 connect 时执行；配置参数为 web B.3-3 合规锚点）。
 */
function getOrCreateClient(entry: EndpointEntry): Client {
  if (entry.client !== null) {
    return entry.client;
  }
  entry.client = new Client({
    brokerURL: buildBrokerUrl(entry.endpoint),
    // 重连与心跳完全交库内建机制（B.3-3）：固定间隔重试 + 10s 双向心跳，禁自研重连循环
    reconnectDelay: RECONNECT_DELAY_MS,
    heartbeatIncoming: HEARTBEAT_MS,
    heartbeatOutgoing: HEARTBEAT_MS,
    // 每次连接尝试（含断线自动重连）实时读会话令牌：轮换后重连自动携带最新值。
    // useAuthStore 运行时调用（此时 pinia 必已激活——建连仅由已挂载页面触发，web B.3-1 口径）
    beforeConnect: () => {
      if (entry.client === null) {
        return;
      }
      const token = useAuthStore().token;
      if (token !== null && token !== '') {
        entry.client.connectHeaders = { Authorization: `Bearer ${token}` };
      } else {
        // 会话令牌缺失：本次尝试无凭证（预期被后端 CONNECT 帧鉴权拒绝——ERROR 帧后连接关闭，
        // 按库内建周期重试）；warn 不含键值
        warn('STOMP 连接缺少会话令牌，本次尝试将被服务端拒绝', traceTag(entry));
      }
    },
    onConnect: () => {
      setConnectionState(entry, 'connected');
      // 无条件重订阅该端点全部在册主题（stompjs 7.3.0 断线重连不自动恢复订阅，旧句柄已在
      // onWebSocketClose/onStompError 置 null 作废——重连路径与首连路径复用同一 doSubscribe
      // 落地方法，防两处订阅逻辑漂移）
      for (const record of entry.subscriptions) {
        record.handle = doSubscribe(entry, record);
        info('已订阅主题', record.destination, traceTag(entry));
      }
      info('STOMP 已连接', buildBrokerUrl(entry.endpoint), traceTag(entry));
    },
    onStompError: (frame) => {
      // broker ERROR 帧：在册订阅句柄随连接作废置 null，置断开态留痕，库将按内建周期重试；
      // 日志含端点/主题与 traceId、禁含令牌
      invalidateSubscriptionHandles(entry);
      setConnectionState(entry, 'disconnected');
      logError('STOMP broker 错误帧', frame.headers['message'] ?? '', traceTag(entry));
    },
    onWebSocketClose: () => {
      // 连接关闭（含 CONNECT 帧鉴权被拒的 ERROR+PROTOCOL_ERROR 关闭）：在册订阅句柄置 null
      // 作废并置断开态；重连完全由库内建 reconnectDelay 接管
      invalidateSubscriptionHandles(entry);
      setConnectionState(entry, 'disconnected');
      warn('STOMP 连接已关闭，等待库内建自动重连', buildBrokerUrl(entry.endpoint), traceTag(entry));
    },
  });
  return entry.client;
}

/**
 * 落地库订阅：帧回调内 try/catch 解析载荷，非法 JSON 毒帧 warn 留痕不中断订阅（等待下一帧
 * 自然恢复）；载荷语义收窄归消费方 utils/iotMessage 纯函数承载。
 */
function doSubscribe(entry: EndpointEntry, record: TopicSubscriptionRecord): StompSubscription {
  const activeClient = entry.client;
  if (activeClient === null) {
    // 仅由 onConnect/已连接分支调用（调用前 Client 必已创建）：防御分支显式失败优于静默
    throw new Error(`STOMP 客户端未初始化，订阅落地失败：${record.destination}`);
  }
  return activeClient.subscribe(record.destination, (message: IMessage) => {
    try {
      record.onFrame(JSON.parse(message.body) as unknown);
    } catch {
      // 非法 JSON 毒帧：warn 留痕，订阅保持
      warn('STOMP 帧体 JSON 解析失败，已忽略本帧', record.destination, traceTag(entry));
    }
  });
}

/** 退订在册订阅（单出口：句柄未落地时仅清登记，防悬挂重复退订） */
function unsubscribeRecord(entry: EndpointEntry, record: TopicSubscriptionRecord): void {
  record.handle?.unsubscribe();
  const index = entry.subscriptions.indexOf(record);
  if (index >= 0) {
    entry.subscriptions.splice(index, 1);
  }
}

/**
 * 建连（全 app 唯一建连出口，端点可指定）：状态进入 connecting 并激活库连接；令牌取自
 * auth store 会话（未登录即 null 时拒绝建连并 warn——后端 CONNECT 帧鉴权硬约束的前置拦截，
 * 登录由路由守卫承载）。已连接态再次调用为 no-op 保持 connected——stompjs activate() 对已
 * 激活 Client 为 no-op，无条件置 connecting 会让状态机卡死在 connecting（bigscreen PR-5
 * Finding 3 同口径）。
 *
 * @param options 连接参数对象（可选，onStateChange 观察状态机）
 * @param endpoint WS 端点路径（可选，缺省 /ws/iot；事件流指引端点如 /ws/outpatient 显式传入）
 */
export function connect(options?: StompConnectOptions, endpoint = DEFAULT_STOMP_ENDPOINT): void {
  const entry = entryOf(endpoint);
  const token = useAuthStore().token;
  if (token === null || token === '') {
    warn('STOMP 连接被拒绝：会话无访问令牌，请先登录');
    return;
  }
  entry.stateChangeListener = options?.onStateChange ?? null;
  const activeClient = getOrCreateClient(entry);
  if (activeClient.connected) {
    // 已连接：保持 connected 态、不置 connecting、不重复 activate（no-op）——traceId 保持
    // 当前连接会话值（未新建传输层），新主题订阅由 subscribeTopic 已连接分支立即落地
    setConnectionState(entry, 'connected');
    info('STOMP 已连接，保持连接，新主题订阅直接落地', buildBrokerUrl(endpoint), traceTag(entry));
    return;
  }
  // 每次连接生成链路 traceId 作日志锚点（不与令牌同帧输出；兼容非安全上下文降级）
  entry.traceId = generateTraceId();
  setConnectionState(entry, 'connecting');
  activeClient.activate();
  info('STOMP 连接发起', buildBrokerUrl(endpoint), traceTag(entry));
}

/**
 * 订阅主题（多端点多主题并行，按「端点+目的地」替换语义）：同端点同目的地重复调用自动退旧
 * 订新，其余各自独立在册。连接未落地时登记待订阅，onConnect 转正；统一返回代理句柄，退订
 * 单出口保证在册一致。
 *
 * @param destination 主题路径（经 topic path 助手/fillTopicTemplate 契约构造，禁手拼漂移）
 * @param onFrame 帧回调（JSON.parse 成功的 unknown 载荷；语义收窄归消费方）
 * @param endpoint WS 端点路径（可选，缺省 /ws/iot；须与主题归属端点一致，跨端点订阅将被
 *                 后端拒绝——指引清单 topics[].endpoint 为准）
 * @return 订阅代理句柄（unsubscribe 幂等，组件卸载必须调用——web B.3-3 卸载条款）
 */
export function subscribeTopic(
  destination: string,
  onFrame: (payload: unknown) => void,
  endpoint = DEFAULT_STOMP_ENDPOINT,
): StompSubscription {
  const entry = entryOf(endpoint);
  // 同端点同目的地替换：先退订在册同主题订阅，防旧回调重复消费同帧
  const existing = entry.subscriptions.find((record) => record.destination === destination);
  if (existing !== undefined) {
    unsubscribeRecord(entry, existing);
  }
  const record: TopicSubscriptionRecord = { destination, onFrame, handle: null };
  entry.subscriptions.push(record);
  if (entry.client !== null && entry.client.connected) {
    // 已连接：立即落地真实订阅
    record.handle = doSubscribe(entry, record);
    info('已订阅主题', destination, traceTag(entry));
  } else {
    info('已登记主题待订阅（连接建立后自动落地）', destination, traceTag(entry));
  }
  return {
    id: `iot-topic-${endpoint}-${destination}`,
    unsubscribe: () => {
      unsubscribeRecord(entry, record);
    },
  };
}

/**
 * 主动断开（组件卸载/页面离开共用）：先退订目标范围全部在册订阅再 deactivate——库语义为
 * deactivate 取消内建重连计划，正好承载「用户主动断开」；状态回归 disconnected。
 * 不带端点参数时断开全部端点（保守清理：离开页面的视图可能持有任一指引端点的订阅，
 * 全量回收防跨视图连接泄漏；仍在线的其他视图由其自身挂载流程重新建连）。
 *
 * @param endpoint WS 端点路径（可选；缺省断开全部端点）
 */
export async function disconnect(endpoint?: string): Promise<void> {
  const targets = endpoint === undefined ? [...endpointEntries.values()] : [entryOf(endpoint)];
  for (const entry of targets) {
    while (entry.subscriptions.length > 0) {
      unsubscribeRecord(entry, entry.subscriptions[0]);
    }
    entry.stateChangeListener = null;
    if (entry.client !== null) {
      await entry.client.deactivate();
      setConnectionState(entry, 'disconnected');
      // 仅对真实建连过的端点留痕（惰性条目无连接可断，禁误导性「已断开」日志）
      info('STOMP 连接已主动断开', buildBrokerUrl(entry.endpoint), traceTag(entry));
    }
  }
}
