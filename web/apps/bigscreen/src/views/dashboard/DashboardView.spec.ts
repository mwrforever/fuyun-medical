// IoT 运营大屏单测（FU-M14-13 前端面）：api/STOMP/echarts 模块全 mock 承载【零出网】，
// 覆盖五区布局渲染（顶部指标带/风暴横幅/左状态墙/中告警列表/右趋势图容器+底部遥测摘要）、
// WS 三主题增量帧驱动（dashboard/global 摘要覆盖+风暴横幅、alarm 前插、telemetry 摘要渲染）、
// REST 10s 轮询降级（令牌缺失不建连、断连态轮询、已连接态跳过轮询）与卸载全量清理
// （订阅退订/断连/轮询停摆/图表 dispose）。fake timers 承载轮询断言，禁真实等待。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { Router } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dashboard, telemetry, alarms } from '@/api/iot';
import { initChart } from '@/utils/echarts';
import DashboardView from './DashboardView.vue';

/** mock 捕获状态（hoisted：订阅帧回调与退订句柄，逐用例手动复位） */
const h = vi.hoisted(() => ({
  onTelemetryFrame: null as null | ((summary: unknown) => void),
  onAlarmFrame: null as null | ((payload: unknown) => void),
  onDashboardFrame: null as null | ((payload: unknown) => void),
  unsubscribers: [] as ReturnType<typeof vi.fn>[],
}));

vi.mock('@/api/iot', () => ({
  dashboard: { summary: vi.fn(), wardWall: vi.fn() },
  telemetry: { series: vi.fn() },
  alarms: { list: vi.fn() },
}));

vi.mock('@/utils/echarts', () => ({
  initChart: vi.fn(),
}));

vi.mock('@/composables/useIotStomp', async () => {
  // connectionState 以真实 ref 承载（组件 computed/tick 消费其 .value 响应性，用例内可写翻转）
  const { ref } = await import('vue');
  return {
    IOT_TOKEN_STORAGE_KEY: 'fy:bigscreen:iot-token',
    DASHBOARD_GLOBAL_TOPIC: '/topic/iot/dashboard/global',
    telemetryTopicPath: (wardId: string) => `/topic/iot/telemetry/${wardId}`,
    alarmTopicPath: (wardId: string) => `/topic/iot/alarm/${wardId}`,
    connectionState: ref<'disconnected' | 'connecting' | 'connected'>('disconnected'),
    connect: vi.fn(),
    disconnect: vi.fn().mockResolvedValue(undefined),
    subscribeTelemetrySummary: vi.fn((_wardId: string, onFrame: (summary: unknown) => void) => {
      h.onTelemetryFrame = onFrame;
      const unsubscribe = vi.fn();
      h.unsubscribers.push(unsubscribe);
      return { unsubscribe };
    }),
    subscribeIotTopic: vi.fn(
      (
        destination: string,
        _parse: (raw: unknown) => unknown,
        onFrame: (payload: unknown) => void,
      ) => {
        if (destination === '/topic/iot/dashboard/global') {
          h.onDashboardFrame = onFrame;
        } else {
          h.onAlarmFrame = onFrame;
        }
        const unsubscribe = vi.fn();
        h.unsubscribers.push(unsubscribe);
        return { unsubscribe };
      },
    ),
  };
});

// 连接状态 ref 引用（用例内直接翻转驱动降级/跳过轮询断言；与 mock 工厂共享模块作用域）
// eslint 提示：mock 工厂与断言侧须同源 ref，非未使用导入
import { connectionState } from '@/composables/useIotStomp';

/** 构造全院摘要 mock 出参（六项全量；stormActive 用例内覆写） */
function summaryMock(stormActive = false): Record<string, unknown> {
  return {
    deviceTotal: '20',
    onlineCount: '13',
    offlineCount: '7',
    activeAlarmCount: '4',
    stormActive,
    backlogEstimate: 12,
    qualityScore: 98.5,
  };
}

/** 构造病区状态墙 mock 出参（一绑定一行：床位+设备+状态+最新值） */
function wardWallMock(): Record<string, unknown> {
  return {
    wardId: '1001',
    items: [
      {
        bedId: '12',
        deviceId: 'dev-icu-01',
        deviceName: '多参数监护仪',
        patientId: '1002',
        visitId: 'I20260920001',
        status: 'ONLINE',
        lastOnlineAt: '2026-09-26T01:00:00Z',
        latestValues: [
          {
            deviceId: 'dev-icu-01',
            metricCode: 'MDC_ECG_HEART_RATE',
            value: 88,
            occurredAt: '2026-09-26T01:00:00Z',
          },
        ],
      },
    ],
  };
}

/** 构造遥测曲线 mock 出参（1min 桶聚合点列） */
function seriesMock(): Record<string, unknown>[] {
  return [
    {
      time: '2026-09-26T01:00:00Z',
      min: 60,
      max: 100,
      avg: 80,
      first: 61,
      last: 79,
      sampleCount: '60',
    },
    {
      time: '2026-09-26T01:01:00Z',
      min: 62,
      max: 96,
      avg: 78,
      first: 63,
      last: 77,
      sampleCount: '58',
    },
  ];
}

/** 构造活跃告警分页 mock 出参（REST 兜底列表行） */
function alarmsPageMock(): Record<string, unknown> {
  return {
    content: [
      {
        id: '1',
        alarmNo: 'AL20260926001',
        deviceId: 'dev-icu-01',
        wardId: '1001',
        alarmLevel: 'CRITICAL',
        metricCode: 'MDC_ECG_HEART_RATE',
        triggerValue: '152',
        status: 'ACTIVE',
        lastTriggeredAt: '2026-09-26T02:00:00Z',
      },
    ],
    page: '1',
    size: '20',
    total: '1',
  };
}

/** REST 四资源 mock 一次性就绪（stormActive 可翻转为风暴态用例服务） */
function mockRestReady(stormActive = false): void {
  vi.mocked(dashboard.summary).mockResolvedValue(summaryMock(stormActive));
  vi.mocked(dashboard.wardWall).mockResolvedValue(wardWallMock());
  vi.mocked(telemetry.series).mockResolvedValue(seriesMock());
  vi.mocked(alarms.list).mockResolvedValue(alarmsPageMock());
}

/** 图表实例桩（dispose/resize/setOption 供卸载与渲染断言） */
function chartInstanceStub(): {
  setOption: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
} {
  return { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() };
}

/** 挂载大屏页（内存 history 路由 + query 书签化形态与生产一致） */
async function mountDashboard(
  query = '?wardId=1001',
  router?: Router,
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const memRouter =
    router ??
    createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/dashboard', component: DashboardView },
      ],
    });
  await memRouter.push(`/dashboard${query}`);
  await memRouter.isReady();
  const wrapper = mount(DashboardView, { global: { plugins: [memRouter] } });
  await flushPromises();
  return { wrapper, router: memRouter };
}

beforeEach(() => {
  vi.clearAllMocks();
  h.onTelemetryFrame = null;
  h.onAlarmFrame = null;
  h.onDashboardFrame = null;
  h.unsubscribers = [];
  sessionStorage.clear();
  (connectionState as { value: string }).value = 'disconnected';
  // 图表实例桩：每次 mount 的 init 均返回独立桩（dispose 断言按实例隔离）
  vi.mocked(initChart).mockImplementation(chartInstanceStub as never);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('IoT 运营大屏', () => {
  it('五区布局渲染：REST 首屏指标带/状态墙/告警列表齐备，趋势图容器经 mock init 初始化，摘要占位', async () => {
    mockRestReady();
    const { wrapper } = await mountDashboard();

    // 顶部指标带（六项聚合中的展示五项：在线比/离线/告警活跃/积压水位/质量分）
    expect(wrapper.text()).toContain('设备在线');
    expect(wrapper.text()).toContain('13/20');
    expect(wrapper.text()).toContain('设备离线');
    expect(wrapper.text()).toContain('告警活跃');
    expect(wrapper.text()).toContain('12');
    expect(wrapper.text()).toContain('质量分');
    expect(wrapper.text()).toContain('98.5');
    // 风暴态未激活：通栏横幅不渲染
    expect(wrapper.text()).not.toContain('告警风暴进行中');

    // 左病区设备状态墙：床位条目 + 设备名 + 状态词 + 最新值
    expect(wrapper.text()).toContain('病区设备状态墙');
    expect(wrapper.text()).toContain('多参数监护仪');
    expect(wrapper.text()).toContain('在线');
    expect(wrapper.text()).toContain('MDC_ECG_HEART_RATE');

    // 中告警列表：REST 兜底行渲染（告警号/级别/触发值）
    expect(wrapper.text()).toContain('AL20260926001');
    expect(wrapper.text()).toContain('危急');
    expect(wrapper.text()).toContain('152');

    // 右趋势图：容器在位且经 mock initChart 初始化（dom 指向容器元素）、首渲染 setOption 触达
    expect(wrapper.find('.dashboard-chart').exists()).toBe(true);
    expect(vi.mocked(initChart)).toHaveBeenCalledTimes(1);
    const initArgs = vi.mocked(initChart).mock.calls[0]?.[0] as { dom: Element };
    expect(initArgs.dom).toBe(wrapper.find('.dashboard-chart').element);
    expect(
      (vi.mocked(initChart).mock.results[0]?.value as { setOption: ReturnType<typeof vi.fn> })
        .setOption,
    ).toHaveBeenCalled();

    // 底部遥测摘要：无 WS 帧前占位
    expect(wrapper.text()).toContain('暂无遥测数据');

    // REST 首屏调用参数：ward 作用域 1min 桶曲线 + 活跃告警分页
    expect(vi.mocked(dashboard.wardWall)).toHaveBeenCalledWith('1001');
    expect(vi.mocked(telemetry.series)).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: 'ward',
        wardId: '1001',
        metricCode: 'MDC_ECG_HEART_RATE',
        granularity: '1min',
      }),
    );
    expect(vi.mocked(alarms.list)).toHaveBeenCalledWith(
      expect.objectContaining({ wardId: '1001', status: 'ACTIVE' }),
    );
    wrapper.unmount();
  });

  it('风暴态横幅：REST 摘要 stormActive=true 时通栏风暴横幅渲染', async () => {
    mockRestReady(true);
    const { wrapper } = await mountDashboard();
    expect(wrapper.text()).toContain('告警风暴进行中');
    wrapper.unmount();
  });

  it('WS 增量：dashboard/global 帧覆盖指标带并点亮风暴横幅，alarm 帧前插告警列表，telemetry 帧渲染底部摘要', async () => {
    mockRestReady();
    sessionStorage.setItem('fy:bigscreen:iot-token', 'tok-1');
    const { wrapper } = await mountDashboard();
    expect(h.onDashboardFrame).not.toBeNull();
    expect(h.onAlarmFrame).not.toBeNull();
    expect(h.onTelemetryFrame).not.toBeNull();

    // dashboard/global 帧（与 REST DashboardSummaryVO 同构）：指标带数值覆盖 + 风暴横幅点亮
    h.onDashboardFrame?.({
      deviceTotal: '20',
      onlineCount: '13',
      offlineCount: '7',
      activeAlarmCount: '4',
      stormActive: true,
      backlogEstimate: 15,
      qualityScore: 97.2,
    });
    await flushPromises();
    expect(wrapper.text()).toContain('告警风暴进行中');
    expect(wrapper.text()).toContain('97.2');

    // alarm 帧（AlarmTriggeredPayload 同构，Long 已字符串化）：前插列表首位
    h.onAlarmFrame?.({
      alarmNo: 'AL20260926002',
      deviceId: 'dev-icu-02',
      patientId: null,
      visitId: null,
      wardId: '1001',
      alarmLevel: 'WARNING',
      metricCode: 'MDC_PULSE_OXIM_SPO2',
      triggerValue: '89',
      ruleId: '3002',
      occurredAt: '2026-09-26T02:05:00Z',
    });
    await flushPromises();
    const firstAlarmRow = wrapper.findAll('.dashboard-alarm')[0];
    expect(firstAlarmRow?.text()).toContain('AL20260926002');
    expect(firstAlarmRow?.text()).toContain('警告');
    // REST 兜底行仍在列（前插不覆盖既有行）
    expect(wrapper.text()).toContain('AL20260926001');

    // telemetry 摘要帧：底部摘要区渲染本批条数与明细，占位文案消失
    h.onTelemetryFrame?.({
      count: 7,
      occurredAtUpperBound: '2026-09-26T02:06:00Z',
      items: [{ deviceId: 'dev-icu-02', metricCode: 'MDC_PULSE_OXIM_SPO2' }],
    });
    await flushPromises();
    expect(wrapper.text()).toContain('本批');
    expect(wrapper.text()).toContain('2026-09-26T02:06:00Z');
    expect(wrapper.text()).toContain('dev-icu-02');
    expect(wrapper.text()).not.toContain('暂无遥测数据');
    wrapper.unmount();
  });

  it('REST 轮询降级：令牌缺失不建连且横幅提示，10s 周期轮询，已连接态跳过轮询', async () => {
    vi.useFakeTimers();
    try {
      mockRestReady();
      // 令牌缺失（sessionStorage 空）：WS 建连拒绝，REST 轮询承载
      const { wrapper } = await mountDashboard();
      const { connect } = await import('@/composables/useIotStomp');
      expect(vi.mocked(connect)).not.toHaveBeenCalled();
      expect(wrapper.text()).toContain('访问令牌');
      expect(wrapper.text()).toContain('REST');

      // 首屏 1 次四资源加载；推进 10s 触发第二轮（断连态轮询）
      expect(vi.mocked(dashboard.summary)).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(dashboard.summary)).toHaveBeenCalledTimes(2);

      // 已连接态：WS 驱动承载，轮询跳过
      (connectionState as { value: string }).value = 'connected';
      await vi.advanceTimersByTimeAsync(10000);
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(dashboard.summary)).toHaveBeenCalledTimes(2);
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('卸载全量清理：三订阅退订 + 断连 + 轮询停摆 + 图表 dispose', async () => {
    vi.useFakeTimers();
    try {
      mockRestReady();
      sessionStorage.setItem('fy:bigscreen:iot-token', 'tok-1');
      const { wrapper } = await mountDashboard();
      const { disconnect, connect } = await import('@/composables/useIotStomp');
      expect(vi.mocked(connect)).toHaveBeenCalledWith({ token: 'tok-1', wardId: '1001' });

      // 已连接态记录轮询基线（1 次）
      (connectionState as { value: string }).value = 'connected';
      expect(vi.mocked(dashboard.summary)).toHaveBeenCalledTimes(1);

      wrapper.unmount();
      // 三主题订阅句柄全量退订（telemetry + alarm + dashboard/global）
      expect(h.unsubscribers).toHaveLength(3);
      for (const unsubscribe of h.unsubscribers) {
        expect(unsubscribe).toHaveBeenCalled();
      }
      expect(vi.mocked(disconnect)).toHaveBeenCalled();
      // 图表实例 dispose（web B.3-5 页面卸载必须 dispose）
      const instance = vi.mocked(initChart).mock.results[0]?.value as {
        dispose: ReturnType<typeof vi.fn>;
      };
      expect(instance.dispose).toHaveBeenCalled();
      // 轮询停摆：卸载后推进 10s 不再触发 REST（定时器已清理）
      (connectionState as { value: string }).value = 'disconnected';
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(dashboard.summary)).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
