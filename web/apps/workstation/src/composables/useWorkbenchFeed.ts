/**
 * 运营工作台数据供给（首页报表构图的数据层，web B.2-6 数据获取逻辑一律进 composables）：
 * 两聚合域独立三态（overview 域=指标带/趋势/候诊表、events 域=事件流）+ 低频轮询兜底 +
 * STOMP 多端点实时联动。
 *
 * <p>实时界限（web B.3-4）：事件行的既有推送主题不存在（后端报告 §三6：事件流=两轮询源承载），
 * 故 REST 轮询为本体（30s 低频，visibilityState 隐藏时暂停）；STOMP 侧按 events 响应的
 * topics 指引清单订阅——其中仅门诊叫号主题（/topic/outpatient/queue/{deptCode}）可由
 * overview 候诊表的真实 deptCode 填充，device-status/nursing board 模板需病区 ID 而首页
 * 无真实病区清单来源，不猜参订阅（零伪数据边界）。叫号帧经载荷收窄后并入事件流（真实帧
 * 数据，患者姓名字段按脱敏口径不渲染），并触发 overview 即时刷新（候诊表实时对账）。
 *
 * <p>三态口径：loading=首拉在途（骨架）；error=最近一次刷新失败（重试按钮重拉本域）；
 * 内容=最近一次成功数据（轮询静默更新不回 loading，防骨架闪烁）。每组件实例独立状态
 * （B.2-7）；仅 setup 同步调用；订阅句柄与定时器经 onUnmounted 统一清理（B.2-6 卸载条款）。
 */
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue';
import type { Ref, ShallowRef } from 'vue';
import {
  dashboard,
  type WorkbenchEventsVO,
  type WorkbenchOverviewVO,
  type WorkEvent,
} from '@/api/dashboard';
import {
  connect as stompConnect,
  connectionStateOf,
  disconnect as stompDisconnect,
  fillTopicTemplate,
  subscribeTopic,
} from '@/composables/useIotStomp';
import type { IotConnectionState } from '@/composables/useIotStomp';
import { warn } from '@/utils/logger';
import { parseQueueCalledFrame } from '@/utils/workbenchDisplay';

/** REST 轮询间隔（毫秒）：低频快照兜底（后端 overview 有 Redis 5s 缓存窗，30s 不击穿） */
const POLL_INTERVAL_MS = 30000;

/** 事件流有界上限（与后端 EVENTS_LIMIT=50 对齐：帧并入行与 REST 行合并后统一截断） */
const STREAM_LIMIT = 50;

/** 事件流行一（视图渲染统一形态）：REST 源行与 STOMP 叫号帧行共用 */
export interface StreamItem {
  /** 行唯一键（v-for key；REST 行=后端 id，帧行=前端 wsq- 序号——仅作 key 非业务数据） */
  id: string;
  /** 发生时点（ISO 串；REST 行=后端 occurredAt，帧行=客户端收帧钟面——均为真实时点） */
  occurredAt: string;
  /** 来源域编码（REST 行=后端 source；帧行=叫号诊区 deptCode） */
  source: string;
  /** 事件类型编码（REST 行=后端 type；帧行恒 QUEUE_CALLED 前端词表） */
  type: string;
  /** 主题行（真实标题：费用项目名/处方号/票号） */
  title: string;
  /** 金额分值（string 承载，仅展示换算；无金额行为 null） */
  amountFen: string | null;
  /** 补充文本（帧行=诊室号；REST 行无） */
  room: string | null;
}

/** useWorkbenchFeed 返回态（数据源只读外泄，写面收敛在 composable 内部） */
export interface UseWorkbenchFeedReturn {
  /** 总览域数据（null=首拉未成功，骨架态） */
  overview: Readonly<ShallowRef<WorkbenchOverviewVO | null>>;
  /** 总览域加载态（仅首拉与手动重试在途为 true；轮询静默更新不置位） */
  overviewLoading: Readonly<Ref<boolean>>;
  /** 总览域失败态（null=正常；非 null=错误态可重试） */
  overviewError: Readonly<Ref<unknown>>;
  /** 事件域数据（null=首拉未成功，骨架态） */
  events: Readonly<ShallowRef<WorkbenchEventsVO | null>>;
  /** 事件域加载态（仅首拉与手动重试在途为 true） */
  eventsLoading: Readonly<Ref<boolean>>;
  /** 事件域失败态（null=正常；非 null=错误态可重试） */
  eventsError: Readonly<Ref<unknown>>;
  /** 事件流合并行（REST 源 ∪ STOMP 叫号帧源，occurredAt 降序 ≤50 条） */
  streamItems: Readonly<ShallowRef<StreamItem[]>>;
  /** 实时推送聚合连接态（全部已订端点 connected=connected；任一 connecting=connecting；否则 disconnected） */
  liveState: Readonly<Ref<IotConnectionState>>;
  /** 已订阅实时端点集合（空集=纯轮询模式，live 指示降级为轮询文案） */
  liveEndpoints: Readonly<Ref<string[]>>;
  /** 手动重试总览域（错误态按钮） */
  retryOverview: () => Promise<void>;
  /** 手动重试事件域（错误态按钮） */
  retryEvents: () => Promise<void>;
}

/**
 * 组合工作台两域数据供给：挂载即并发首拉 + 启动轮询与 STOMP 联动，卸载全量清理
 * （轮询定时器/可见性监听/STOMP 订阅与连接）。
 *
 * @return 两域三态 + 合并事件流 + 实时连接态 + 重试入口
 */
export function useWorkbenchFeed(): UseWorkbenchFeedReturn {
  const overview = shallowRef<WorkbenchOverviewVO | null>(null);
  const overviewLoading = ref(false);
  const overviewError = ref<unknown>(null);
  const events = shallowRef<WorkbenchEventsVO | null>(null);
  const eventsLoading = ref(false);
  const eventsError = ref<unknown>(null);
  /** STOMP 叫号帧派生流行（与 REST 行在 computed 合并，互不覆写） */
  const wsItems = shallowRef<StreamItem[]>([]);
  /** 在册 STOMP 订阅句柄（退订单出口；键=「端点|目的地」防重复登记） */
  const stompHandles = new Map<string, { unsubscribe: () => void }>();
  /** 已订阅端点集合（live 聚合指示的数据面） */
  const liveEndpoints = ref<string[]>([]);

  /** 总览域拉取（失败静默落 error——弹错归响应拦截器，web A.3-2 口径） */
  async function fetchOverview(): Promise<void> {
    overviewLoading.value = overview.value === null;
    overviewError.value = null;
    try {
      overview.value = await dashboard.overview();
    } catch (cause) {
      overviewError.value = cause;
    } finally {
      overviewLoading.value = false;
    }
  }

  /** 事件域拉取（失败静默落 error） */
  async function fetchEvents(): Promise<void> {
    eventsLoading.value = events.value === null;
    eventsError.value = null;
    try {
      events.value = await dashboard.events();
    } catch (cause) {
      eventsError.value = cause;
    } finally {
      eventsLoading.value = false;
    }
  }

  // 首拉并发（两域互不阻塞：一域失败另一域照常呈现）
  void Promise.all([fetchOverview(), fetchEvents()]);

  // 轮询兜底（B.3-4：无推送主题的低频快照才轮询）：30s 双域刷新，页面隐藏时暂停
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  function startPolling(): void {
    if (pollTimer !== null) {
      return;
    }
    pollTimer = setInterval(() => {
      void Promise.all([fetchOverview(), fetchEvents()]);
    }, POLL_INTERVAL_MS);
  }
  function stopPolling(): void {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }
  function onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') {
      stopPolling();
      return;
    }
    // 回前台立即对账一次再续轮询（隐藏期间堆积的变更即时收敛）
    void Promise.all([fetchOverview(), fetchEvents()]);
    startPolling();
  }
  startPolling();
  document.addEventListener('visibilitychange', onVisibilityChange);

  /** 叫号帧到达：收窄入流 + 触发总览即时刷新（候诊计数/候诊表对账） */
  function handleQueueFrame(deptCode: string, payload: unknown): void {
    const frame = parseQueueCalledFrame(payload);
    if (frame === null) {
      // 载荷不合法：warn 留痕不中断订阅（下一帧自然恢复）
      warn('叫号帧载荷不合法，已忽略本帧', deptCode);
      return;
    }
    const item: StreamItem = {
      id: `wsq-${frame.ticketNo}-${Date.now()}`,
      occurredAt: new Date().toISOString(),
      source: deptCode,
      type: 'QUEUE_CALLED',
      title: frame.ticketNo,
      amountFen: null,
      room: frame.room === '' ? null : frame.room,
    };
    // 帧行头部插入后统一有界截断（大屏有界纪律，防长会话无界增长）
    wsItems.value = [item, ...wsItems.value].slice(0, STREAM_LIMIT);
    void fetchOverview();
  }

  // STOMP 联动：按候诊表真实 deptCode 填充叫号主题模板并订阅（指引内其余模板需病区 ID，
  // 首页无真实来源不猜参——见模块头注释）。dept 集变化时增量换订。
  // 订阅源必须同时纳入 events 的叫号模板：两域并发首拉到达时序不定，若仅依赖 dept 集，
  // overview 先到而 topics 未就绪的一次性求值会静默错过首屏订阅（topics 后到不触发本
  // watch，V5 真机实测坐实）——双源对象化后任一后到即重触发；回调内键去重保证幂等，
  // 轮询期重复触发零换订成本。
  watch(
    () => ({
      deptCodes: overview.value?.waitingTable?.map((row) => row.deptCode) ?? [],
      queueTemplate: events.value?.topics?.find((topic) => topic.topic?.includes('{deptCode}')),
    }),
    ({ deptCodes, queueTemplate }) => {
      if (queueTemplate?.endpoint === undefined || queueTemplate.topic === undefined) {
        return;
      }
      const endpoint = queueTemplate.endpoint;
      const nextKeys = new Set<string>();
      for (const deptCode of deptCodes) {
        if (deptCode === undefined || deptCode === '') {
          continue;
        }
        let destination: string;
        try {
          destination = fillTopicTemplate(queueTemplate.topic, { deptCode });
        } catch {
          // 模板填充被拒（占位符缺参/非法字符）：warn 留痕跳过该诊区，不中断其余订阅
          warn('叫号主题模板填充被拒，跳过该诊区订阅', queueTemplate.topic);
          continue;
        }
        const key = `${endpoint}|${destination}`;
        nextKeys.add(key);
        if (stompHandles.has(key)) {
          continue;
        }
        // 建连 + 订阅登记（fillTopicTemplate 已担保目的地合法；库内建重连承载断线）
        stompConnect(undefined, endpoint);
        stompHandles.set(
          key,
          subscribeTopic(destination, (payload) => handleQueueFrame(deptCode, payload), endpoint),
        );
      }
      // 已不在候诊表的诊区：退订换净（防幽灵订阅持续收帧）
      for (const [key, handle] of stompHandles) {
        if (!nextKeys.has(key)) {
          handle.unsubscribe();
          stompHandles.delete(key);
        }
      }
      liveEndpoints.value = [
        ...new Set([...stompHandles.keys()].map((key) => key.split('|')[0] ?? '')),
      ];
    },
    { immediate: true },
  );

  // 卸载全量清理（web B.2-6）：定时器/可见性监听/STOMP 全端点订阅与连接
  onUnmounted(() => {
    stopPolling();
    document.removeEventListener('visibilitychange', onVisibilityChange);
    void stompDisconnect();
    stompHandles.clear();
  });

  const streamItems = computed<StreamItem[]>(() => {
    const restItems: StreamItem[] = (events.value?.events ?? []).map((event: WorkEvent) => ({
      id: event.id ?? `rest-${event.occurredAt ?? ''}-${event.title ?? ''}`,
      occurredAt: event.occurredAt ?? '',
      source: event.source ?? '',
      type: event.type ?? '',
      title: event.title ?? '',
      amountFen: event.amountFen ?? null,
      room: null,
    }));
    // occurredAt 降序（时间戳比较：ISO 串时区偏移不同，字符串序不可靠；无效时点归 0 沉底）
    const epochOf = (iso: string): number => {
      const parsed = Date.parse(iso);
      return Number.isNaN(parsed) ? 0 : parsed;
    };
    return [...wsItems.value, ...restItems]
      .sort((a, b) => epochOf(b.occurredAt) - epochOf(a.occurredAt))
      .slice(0, STREAM_LIMIT);
  });

  // 实时聚合连接态：任一已订端点 connecting → connecting；全部 connected → connected；
  // 其余（含空集=纯轮询）→ disconnected（视图据此渲染「实时推送/连接中/轮询模式」）
  const liveState = computed<IotConnectionState>(() => {
    if (liveEndpoints.value.length === 0) {
      return 'disconnected';
    }
    const states = liveEndpoints.value.map((endpoint) => connectionStateOf(endpoint).value);
    if (states.some((state) => state === 'connecting')) {
      return 'connecting';
    }
    return states.every((state) => state === 'connected') ? 'connected' : 'disconnected';
  });

  return {
    overview,
    overviewLoading,
    overviewError,
    events,
    eventsLoading,
    eventsError,
    streamItems,
    liveState,
    liveEndpoints,
    retryOverview: fetchOverview,
    retryEvents: fetchEvents,
  };
}
