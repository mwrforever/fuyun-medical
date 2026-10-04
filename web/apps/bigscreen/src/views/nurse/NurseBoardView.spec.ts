// 护士站大屏单测（Task 17）：api/useNursingStomp/useIotStomp 模块全 mock 承载【零出网】，
// 覆盖暗色五区渲染（床位墙/未确认告警列/输液动态条/任务逾期看板/出入院滚动条——危急值空段
// M07 预留不渲染）、书签化 wardId（query 传入 REST 与订阅、非法回退演示病区）、双端点 WS
// 增量（nursing board 五类型帧分发：BED_PATIENT 快照刷新/TASK_OVERDUE 前插升级闪烁/
// INFUSION_ESCALATION 执行单号幂等/CALL_TRIGGERED 呼叫行/ADVERSE_EVENT_REMIND 超时提醒；
// iot alarm 帧前插去重）与 REST 10s 轮询降级（nursing/iot 双通道独立门控、页面隐藏暂停
// EX-41、恢复可见立刷）、卸载全量清理（订阅退订/双断连/轮询停摆）及 D-4 WS 派生行 TTL
// 退役（逾期 wsOnly 行 10 分钟宽限窗/呼叫行 5 分钟/输注升级行 30 分钟——快照无「解除」帧，
// 前插行按首见时间戳在快照/帧入口统一清除，断言走渲染行为面经 fake timers 快进承载）。
// fake timers 承载轮询断言，禁真实等待。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import type { Router } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { alarms } from '@/api/iot';
import { nursing, ward } from '@/api/nursing';
import NurseBoardView from './NurseBoardView.vue';

/** mock 捕获状态（hoisted：各订阅帧回调与退订句柄，逐用例手动复位） */
const h = vi.hoisted(() => ({
  onNursingFrame: null as null | ((frame: unknown) => void),
  onAlarmFrame: null as null | ((payload: unknown) => void),
  onTelemetryFrame: null as null | ((summary: unknown) => void),
  onDeviceStatusFrame: null as null | ((payload: unknown) => void),
  unsubscribers: [] as ReturnType<typeof vi.fn>[],
}));

vi.mock('@/api/nursing', () => ({
  nursing: { board: vi.fn() },
  ward: { infusionBoard: vi.fn() },
}));

vi.mock('@/api/iot', () => ({
  alarms: { list: vi.fn() },
}));

vi.mock('@/composables/useNursingStomp', async () => {
  // connectionState/tokenFailed 以真实 ref 承载（组件 computed/tick 消费其 .value 响应性，
  // 用例内可写翻转）
  const { ref } = await import('vue');
  return {
    connectionState: ref<'disconnected' | 'connecting' | 'connected'>('disconnected'),
    tokenFailed: ref(false),
    connect: vi.fn(),
    disconnect: vi.fn().mockResolvedValue(undefined),
    boardTopicPath: (wardId: string) => `/topic/nursing/board/${wardId}`,
    subscribeBoard: vi.fn((_wardId: string, onFrame: (frame: unknown) => void) => {
      h.onNursingFrame = onFrame;
      const unsubscribe = vi.fn();
      h.unsubscribers.push(unsubscribe);
      return { unsubscribe };
    }),
  };
});

vi.mock('@/composables/useIotStomp', async () => {
  const { ref } = await import('vue');
  return {
    IOT_TOKEN_STORAGE_KEY: 'fy:bigscreen:iot-token',
    connectionState: ref<'disconnected' | 'connecting' | 'connected'>('disconnected'),
    alarmTopicPath: (wardId: string) => `/topic/iot/alarm/${wardId}`,
    telemetryTopicPath: (wardId: string) => `/topic/iot/telemetry/${wardId}`,
    deviceStatusTopicPath: (wardId: string) => `/topic/iot/device-status/${wardId}`,
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
        if (destination.includes('/alarm/')) {
          h.onAlarmFrame = onFrame;
        } else {
          h.onDeviceStatusFrame = onFrame;
        }
        const unsubscribe = vi.fn();
        h.unsubscribers.push(unsubscribe);
        return { unsubscribe };
      },
    ),
  };
});

// 双链路连接状态与令牌失败 ref 引用（用例内直接翻转驱动降级/跳过轮询断言；与 mock 工厂
// 共享模块作用域）
import { connectionState as iotConnectionState } from '@/composables/useIotStomp';
import {
  connectionState as nursingConnectionState,
  tokenFailed,
} from '@/composables/useNursingStomp';

/** 构造护理大屏四段快照 mock 出参（beds 含 bedNo null 双形态与风险标记；危急值空段） */
function boardMock(): Record<string, unknown> {
  return {
    wardId: '1001',
    beds: [
      {
        bedNo: '12',
        visitId: 'I20260920001',
        patientId: '1002',
        nursingLevel: 'SPECIAL',
        admittedAt: '2026-09-20T08:00:00+08:00',
        assigneeName: 'nurse-01',
        riskFlags: 'FALL,PRESSURE',
      },
      {
        bedNo: null,
        visitId: 'I20260920002',
        patientId: '1003',
        nursingLevel: 'NORMAL',
        admittedAt: '2026-10-01T09:00:00+08:00',
        assigneeName: null,
        riskFlags: null,
      },
    ],
    overdueTasks: [
      {
        taskNo: 'TK2026100300001',
        taskType: 'TURN',
        planTime: '2026-10-03T05:30:00+08:00',
        escalationCount: 1,
      },
    ],
    admissions: [
      {
        visitId: 'I20260920001',
        bedNo: '12',
        at: '2026-10-03T13:00:00+08:00',
        type: 'ADMIT',
      },
      {
        visitId: 'I20260920009',
        bedNo: '08',
        at: '2026-10-03T12:10:00+08:00',
        type: 'DISCHARGE',
      },
    ],
    criticalValues: [],
    generatedAt: '2026-10-03T14:00:00+08:00',
  };
}

/** 构造输液看板 mock 出参（两设备：余量充足与橙档告急——倒计时组合计算两形态） */
function infusionMock(): Record<string, unknown> {
  return {
    wardId: '1001',
    devices: [
      { deviceId: 'dev-inf-01', remainLatest: 20, dropRateLatest: 60, alertLevel: 'NONE' },
      { deviceId: 'dev-inf-02', remainLatest: 8, dropRateLatest: 48, alertLevel: 'ORANGE' },
    ],
  };
}

/** 构造无遥测数据输液快照 mock 出参（后端无数据落 null 非 undefined——JSON 反序列化 null 形态锚） */
function infusionNullMetricMock(): Record<string, unknown> {
  return {
    wardId: '1001',
    devices: [
      { deviceId: 'dev-inf-03', remainLatest: null, dropRateLatest: null, alertLevel: 'NONE' },
    ],
  };
}

/** 构造活跃告警分页 mock 出参（REST 兜底行） */
function alarmsPageMock(): Record<string, unknown> {
  return {
    content: [
      {
        id: '1',
        alarmNo: 'AL20261003001',
        deviceId: 'dev-icu-01',
        wardId: '1001',
        alarmLevel: 'CRITICAL',
        metricCode: 'MDC_ECG_HEART_RATE',
        triggerValue: '152',
        status: 'ACTIVE',
        lastTriggeredAt: '2026-10-03T10:00:00Z',
      },
    ],
    page: '1',
    size: '20',
    total: '1',
  };
}

/** REST 三资源 mock 一次性就绪 */
function mockRestReady(): void {
  vi.mocked(nursing.board).mockResolvedValue(boardMock());
  vi.mocked(ward.infusionBoard).mockResolvedValue(infusionMock());
  vi.mocked(alarms.list).mockResolvedValue(alarmsPageMock());
}

/** 挂载护士站大屏页（内存 history 路由 + query 书签化形态与生产一致） */
async function mountNurseBoard(query = ''): Promise<VueWrapper> {
  const memRouter: Router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/nurse-board', component: NurseBoardView },
    ],
  });
  await memRouter.push(`/nurse-board${query}`);
  await memRouter.isReady();
  const wrapper = mount(NurseBoardView, { global: { plugins: [memRouter] } });
  await flushPromises();
  return wrapper;
}

/** jsdom 页面可见性覆写（EX-41 断言承载；configurable 便于逐用例复位） */
function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.clearAllMocks();
  h.onNursingFrame = null;
  h.onAlarmFrame = null;
  h.onTelemetryFrame = null;
  h.onDeviceStatusFrame = null;
  h.unsubscribers = [];
  sessionStorage.clear();
  setHidden(false);
  (nursingConnectionState as { value: string }).value = 'disconnected';
  (iotConnectionState as { value: string }).value = 'disconnected';
  (tokenFailed as { value: boolean }).value = false;
  mockRestReady();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('护士站大屏（双端点订阅与五区视图）', () => {
  it('五区渲染：床位墙/告警列/输液动态/逾期看板/出入院滚动齐备，危急值空段不渲染', async () => {
    const wrapper = await mountNurseBoard('?wardId=1001');

    // ① 床位总览墙：床号/护理级别色阶词/责任护士（nurse_id 展示即所得）/风险标记 chips；
    //    bedNo null 双形态防御渲染「待排床」
    const beds = wrapper.find('[aria-label="床位总览墙"]');
    expect(beds.exists()).toBe(true);
    expect(beds.text()).toContain('12');
    expect(beds.text()).toContain('特级护理');
    expect(beds.text()).toContain('nurse-01');
    expect(beds.text()).toContain('跌倒');
    expect(beds.text()).toContain('压疮');
    expect(beds.text()).toContain('待排床');
    expect(beds.text()).toContain('普通护理');

    // ② 未确认告警列：REST 兜底行（告警号/级别词/触发值）
    const alerts = wrapper.find('[aria-label="未确认告警"]');
    expect(alerts.exists()).toBe(true);
    expect(alerts.text()).toContain('AL20261003001');
    expect(alerts.text()).toContain('危急');
    expect(alerts.text()).toContain('152');

    // ③ 输液动态条：设备行余量/滴速/档位 + 余量倒计时组合计算（20ml÷60ml/h=20 分钟）
    const infusion = wrapper.find('[aria-label="输液动态"]');
    expect(infusion.exists()).toBe(true);
    expect(infusion.text()).toContain('dev-inf-01');
    expect(infusion.text()).toContain('dev-inf-02');
    expect(infusion.text()).toContain('20');
    expect(infusion.text()).toContain('橙档');
    expect(infusion.text()).toContain('20 分钟');

    // ④ 任务逾期看板：任务号/类型词/计划时点/档位词（REST 行无闪烁类）
    const overdue = wrapper.find('[aria-label="任务逾期看板"]');
    expect(overdue.exists()).toBe(true);
    expect(overdue.text()).toContain('TK2026100300001');
    expect(overdue.text()).toContain('翻身');
    expect(overdue.text()).toContain('责任护士档');
    expect(wrapper.find('.nurse-overdue.is-escalated').exists()).toBe(false);

    // ⑤ 出入院动态滚动条：入科/出院双行（时点 HH:mm 格式化）
    const admissions = wrapper.find('[aria-label="出入院动态"]');
    expect(admissions.exists()).toBe(true);
    expect(admissions.text()).toContain('入科');
    expect(admissions.text()).toContain('出院');
    expect(admissions.text()).toContain('13:00');
    expect(admissions.text()).toContain('12:10');

    // 危急值段（M07 预留空段）：不渲染任何危急值区
    expect(wrapper.text()).not.toContain('危急值');
    wrapper.unmount();
  });

  it('输液动态无遥测数据（null 形态）：余量/滴速占位 —，不渲染 null 字面量', async () => {
    // 后端 InfusionBoardDeviceVO 无数据明示 null（JSON 落 null 非 undefined）——覆盖
    // remainLatest/dropRateLatest 双 null 行的占位渲染（D-2 回归锚）
    vi.mocked(ward.infusionBoard).mockResolvedValue(infusionNullMetricMock());
    const wrapper = await mountNurseBoard('?wardId=1001');

    const metric = wrapper.find('.nurse-infusion-metric');
    expect(metric.exists()).toBe(true);
    expect(metric.text()).toContain('—');
    expect(metric.text()).not.toContain('null');
    // 倒计时组合计算对 null 同样占位（unknown 守卫既有安全面一并锚定）
    expect(wrapper.find('.nurse-infusion-remain').text()).toContain('—');
    wrapper.unmount();
  });

  it('书签化 wardId：query 值直传 REST 与双端点订阅（数字串直订零映射）；非法/缺失回退演示病区', async () => {
    const wrapper = await mountNurseBoard('?wardId=2002');
    expect(vi.mocked(nursing.board)).toHaveBeenCalledWith('2002');
    expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledWith('2002');
    expect(vi.mocked(alarms.list)).toHaveBeenCalledWith(
      expect.objectContaining({ wardId: '2002', status: 'ACTIVE' }),
    );
    const { connect: nursingConnect } = await import('@/composables/useNursingStomp');
    expect(vi.mocked(nursingConnect)).toHaveBeenCalledWith('2002');
    // iot 侧订阅在令牌缺失时未发生（telemetry 单槽位订阅未挂接）
    const { subscribeTelemetrySummary } = await import('@/composables/useIotStomp');
    expect(vi.mocked(subscribeTelemetrySummary)).not.toHaveBeenCalled();
    wrapper.unmount();

    // 非法 query（非纯数字）回退演示病区 1001
    const fallback = await mountNurseBoard('?wardId=ward-a');
    expect(vi.mocked(nursing.board)).toHaveBeenCalledWith('1001');
    fallback.unmount();
  });

  it('nursing 帧增量：逾期前插升级闪烁、输注升级执行单号幂等、呼叫行入告警列、床位动态触发节流快照刷新', async () => {
    const wrapper = await mountNurseBoard('?wardId=1001');
    expect(h.onNursingFrame).not.toBeNull();

    // TASK_OVERDUE 帧：新任务前插置顶，escalationCount=2 落护士长档闪烁类
    h.onNursingFrame?.({
      type: 'TASK_OVERDUE',
      payload: {
        taskNo: 'TK2026100300002',
        taskType: 'PATROL',
        planTime: '2026-10-03T05:00:00+08:00',
        escalationCount: 2,
        wardId: '1001',
      },
      occurredAt: '2026-10-03T06:00:00Z',
    });
    await flushPromises();
    const firstOverdue = wrapper.findAll('.nurse-overdue')[0];
    expect(firstOverdue?.text()).toContain('TK2026100300002');
    expect(firstOverdue?.text()).toContain('护士长档');
    expect(firstOverdue?.classes()).toContain('is-escalated');
    // REST 快照行仍在列（前插不覆盖既有行）
    expect(wrapper.find('[aria-label="任务逾期看板"]').text()).toContain('TK2026100300001');

    // INFUSION_ESCALATION 帧：执行单号清单前插；重复帧（Task 11 minor③ 可重复携带）幂等去重
    const escalationFrame = {
      type: 'INFUSION_ESCALATION',
      payload: {
        alarmNo: 'AL20261003002',
        executionNos: ['EX20261003001', 'EX20261003002'],
        escalatedCount: 2,
        taskEscalatedCount: 1,
      },
      occurredAt: '2026-10-03T06:01:00Z',
    };
    h.onNursingFrame?.(escalationFrame);
    h.onNursingFrame?.(escalationFrame);
    await flushPromises();
    expect(wrapper.findAll('.nurse-escalation')).toHaveLength(2);
    expect(wrapper.find('[aria-label="输液动态"]').text()).toContain('AL20261003002');
    expect(wrapper.find('[aria-label="输液动态"]').text()).toContain('EX20261003001');

    // CALL_TRIGGERED 帧：呼叫行前插告警列首位
    h.onNursingFrame?.({
      type: 'CALL_TRIGGERED',
      payload: {
        callNo: 'CALL2026100300001',
        deviceId: 'dev-call-01',
        callType: 'NURSE_CALL',
        bedId: '12',
        wardId: '1001',
        triggeredAt: '2026-10-03T05:58:00Z',
      },
      occurredAt: '2026-10-03T05:58:00Z',
    });
    await flushPromises();
    const firstAlert = wrapper.findAll('.nurse-alert')[0];
    expect(firstAlert?.text()).toContain('CALL2026100300001');
    expect(firstAlert?.text()).toContain('呼叫');
    // REST 兜底告警行仍在列
    expect(wrapper.find('[aria-label="未确认告警"]').text()).toContain('AL20261003001');

    // ADVERSE_EVENT_REMIND 帧：逾期看板渲染上报超时提醒行
    h.onNursingFrame?.({
      type: 'ADVERSE_EVENT_REMIND',
      payload: { wardId: '1001', overdueCount: 2, sampleEventNos: ['AE1', 'AE2'] },
      occurredAt: '2026-10-03T06:02:00Z',
    });
    await flushPromises();
    expect(wrapper.find('[aria-label="任务逾期看板"]').text()).toContain('上报超时');
    expect(wrapper.find('[aria-label="任务逾期看板"]').text()).toContain('AE1');

    // BED_PATIENT 帧：定位键信号触发节流快照刷新（board 第二次拉取）
    expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(1);
    h.onNursingFrame?.({
      type: 'BED_PATIENT',
      payload: { visitId: 'I20260920001', patientId: '1002', bedNo: null, wardId: '1001' },
      occurredAt: '2026-10-03T06:03:00Z',
    });
    await flushPromises();
    expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(2);
    // 节流窗内第二帧不重复拉取（2s 合并，工作站先例）
    h.onNursingFrame?.({
      type: 'BED_PATIENT',
      payload: { visitId: 'I20260920002', patientId: '1003', bedNo: '15', wardId: '1001' },
      occurredAt: '2026-10-03T06:03:01Z',
    });
    await flushPromises();
    expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('iot 帧增量：令牌缺失不建连横幅提示；注入后 alarm 帧前插告警列并按 alarmNo 去重', async () => {
    // 第一段：令牌缺失（sessionStorage 空）——iot WS 不建连不订阅，横幅提示 REST 承载；
    // nursing 链路匿名令牌自动建连不受影响
    const bare = await mountNurseBoard('?wardId=1001');
    const { connect: iotConnect } = await import('@/composables/useIotStomp');
    const { connect: nursingConnect } = await import('@/composables/useNursingStomp');
    expect(vi.mocked(iotConnect)).not.toHaveBeenCalled();
    expect(bare.text()).toContain('未注入访问令牌');
    expect(vi.mocked(nursingConnect)).toHaveBeenCalledWith('1001');
    bare.unmount();

    // 第二段：注入令牌后 iot 主题订阅在位——alarm 帧（AlarmTriggeredPayload 同构）前插
    // 列表首位；重复 alarmNo 帧去重单行
    sessionStorage.setItem('fy:bigscreen:iot-token', 'tok-1');
    const wrapper = await mountNurseBoard('?wardId=1001');
    expect(vi.mocked(iotConnect)).toHaveBeenCalledWith({ token: 'tok-1', wardId: '1001' });
    const alarmFrame = {
      alarmNo: 'AL20261003003',
      deviceId: 'dev-icu-02',
      patientId: null,
      visitId: null,
      wardId: '1001',
      alarmLevel: 'WARNING',
      metricCode: 'MDC_PULSE_OXIM_SPO2',
      triggerValue: '89',
      ruleId: '3002',
      occurredAt: '2026-10-03T10:05:00Z',
    };
    h.onAlarmFrame?.(alarmFrame);
    h.onAlarmFrame?.(alarmFrame);
    await flushPromises();
    const rows = wrapper.findAll('.nurse-alert');
    expect(rows[0]?.text()).toContain('AL20261003003');
    expect(rows[0]?.text()).toContain('警告');
    expect(rows.filter((row) => row.text().includes('AL20261003003'))).toHaveLength(1);
    wrapper.unmount();
  });

  it('REST 轮询降级：断连态 10s 轮询三资源，护理/设备双通道独立门控跳过，页面隐藏暂停（EX-41）', async () => {
    vi.useFakeTimers();
    try {
      const wrapper = await mountNurseBoard('?wardId=1001');
      // 首屏 1 次三资源加载；双通道断连推进 10s 触发第二轮
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(2);
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(2);
      expect(vi.mocked(alarms.list)).toHaveBeenCalledTimes(2);

      // 护理通道已连接：board 快照由 WS 帧驱动，轮询跳过；设备通道仍断连继续轮询
      (nursingConnectionState as { value: string }).value = 'connected';
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(2);
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(3);
      expect(vi.mocked(alarms.list)).toHaveBeenCalledTimes(3);

      // 设备通道已连接：输液/告警由 WS 信号驱动，轮询跳过
      (iotConnectionState as { value: string }).value = 'connected';
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(3);
      expect(vi.mocked(alarms.list)).toHaveBeenCalledTimes(3);

      // 页面隐藏：轮询整体暂停（EX-41 后台标签页零请求）
      (nursingConnectionState as { value: string }).value = 'disconnected';
      (iotConnectionState as { value: string }).value = 'disconnected';
      setHidden(true);
      await vi.advanceTimersByTimeAsync(20000);
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(2);
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(3);

      // 恢复可见：立刷一轮兜底（隐藏期变更一次补齐）
      setHidden(false);
      await flushPromises();
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(3);
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('页面隐藏期信号帧丢弃（EX-41 零出网）；恢复可见后新帧恢复驱动', async () => {
    vi.useFakeTimers();
    try {
      const wrapper = await mountNurseBoard('?wardId=1001');
      setHidden(true);
      // BED_PATIENT 信号帧隐藏期丢弃：不出网（board 拉取计数不变）
      h.onNursingFrame?.({
        type: 'BED_PATIENT',
        payload: { visitId: 'I20260920001', patientId: '1002', bedNo: '12', wardId: '1001' },
        occurredAt: '2026-10-03T06:05:00Z',
      });
      await flushPromises();
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(1);
      // 遥测信号帧（输液刷新信号）隐藏期同样丢弃
      h.onTelemetryFrame?.({ count: 1, occurredAtUpperBound: 't', items: [] });
      await flushPromises();
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(1);
      // 恢复可见后新信号帧恢复驱动刷新（board 计数：首屏 1 + 恢复可见立刷 1 + 新信号帧 1）
      setHidden(false);
      h.onNursingFrame?.({
        type: 'BED_PATIENT',
        payload: { visitId: 'I20260920001', patientId: '1002', bedNo: '13', wardId: '1001' },
        occurredAt: '2026-10-03T06:06:00Z',
      });
      await flushPromises();
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(3);
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('iot 令牌注入后四主题订阅（board+alarm+telemetry+device-status）；卸载全量退订双断连轮询停摆', async () => {
    vi.useFakeTimers();
    try {
      sessionStorage.setItem('fy:bigscreen:iot-token', 'tok-1');
      const wrapper = await mountNurseBoard('?wardId=1001');
      const { connect: iotConnect, disconnect: iotDisconnect } =
        await import('@/composables/useIotStomp');
      const { disconnect: nursingDisconnect } = await import('@/composables/useNursingStomp');
      expect(vi.mocked(iotConnect)).toHaveBeenCalledWith({ token: 'tok-1', wardId: '1001' });

      wrapper.unmount();
      // 四订阅句柄全量退订（nursing board + telemetry + alarm + device-status）
      expect(h.unsubscribers).toHaveLength(4);
      for (const unsubscribe of h.unsubscribers) {
        expect(unsubscribe).toHaveBeenCalled();
      }
      expect(vi.mocked(nursingDisconnect)).toHaveBeenCalled();
      expect(vi.mocked(iotDisconnect)).toHaveBeenCalled();
      // 轮询停摆：卸载后推进 10s 不再触发 REST（定时器已清理）
      await vi.advanceTimersByTimeAsync(10000);
      expect(vi.mocked(nursing.board)).toHaveBeenCalledTimes(1);
      expect(vi.mocked(ward.infusionBoard)).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('护理链路令牌签发失败：tokenFailed 横幅承载，REST 轮询继续兜底', async () => {
    (tokenFailed as { value: boolean }).value = true;
    const wrapper = await mountNurseBoard('?wardId=1001');
    expect(wrapper.text()).toContain('令牌获取失败');
    wrapper.unmount();
  });

  it('D-4 TTL：wsOnly 逾期行超 10 分钟宽限窗退役，快照逾期行不受影响', async () => {
    // 档位值=裁决固化（10 分钟），测试以字面量锚定档位防擅自调档
    vi.useFakeTimers();
    try {
      const wrapper = await mountNurseBoard('?wardId=1001');
      // WS 逾期帧前插新任务号（快照 mock 不含该号——wsOnly 保留语义的成立前提）
      h.onNursingFrame?.({
        type: 'TASK_OVERDUE',
        payload: {
          taskNo: 'TK2026100300003',
          taskType: 'PATROL',
          planTime: '2026-10-03T05:00:00+08:00',
          escalationCount: 1,
          wardId: '1001',
        },
        occurredAt: '2026-10-03T06:00:00Z',
      });
      await flushPromises();
      const overduePanel = () => wrapper.find('[aria-label="任务逾期看板"]');
      expect(overduePanel().text()).toContain('TK2026100300003');

      // 宽限窗内（1 分钟）：多轮快照合并（10s 轮询）均未获快照确认，wsOnly 行保留不误伤
      await vi.advanceTimersByTimeAsync(60 * 1000);
      expect(overduePanel().text()).toContain('TK2026100300003');

      // 快进越过 10 分钟宽限窗：下一轮快照合并触发统一清理，WS 前插行退役；
      // 快照行 TK2026100300001 生命周期归快照，恒在列不受 TTL 管
      await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
      expect(overduePanel().text()).not.toContain('TK2026100300003');
      expect(overduePanel().text()).toContain('TK2026100300001');
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('D-4 TTL：WS 呼叫行超 5 分钟 TTL 从告警列退役，快照告警行不受影响', async () => {
    // 档位值=裁决固化（5 分钟），测试以字面量锚定档位防擅自调档
    vi.useFakeTimers();
    try {
      const wrapper = await mountNurseBoard('?wardId=1001');
      h.onNursingFrame?.({
        type: 'CALL_TRIGGERED',
        payload: {
          callNo: 'CALL2026100300002',
          deviceId: 'dev-call-02',
          callType: 'NURSE_CALL',
          bedId: '12',
          wardId: '1001',
          triggeredAt: '2026-10-03T05:58:00Z',
        },
        occurredAt: '2026-10-03T05:58:00Z',
      });
      await flushPromises();
      const alertPanel = () => wrapper.find('[aria-label="未确认告警"]');
      expect(alertPanel().text()).toContain('CALL2026100300002');

      // TTL 内（4 分钟）：多轮告警快照合并不清除呼叫行
      await vi.advanceTimersByTimeAsync(4 * 60 * 1000);
      expect(alertPanel().text()).toContain('CALL2026100300002');

      // 快进越过 5 分钟 TTL（累计 6 分钟）：呼叫行从告警列退役；
      // REST 快照告警行 AL20261003001 随每轮快照重装载，不受 TTL 管
      await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
      expect(alertPanel().text()).not.toContain('CALL2026100300002');
      expect(alertPanel().text()).toContain('AL20261003001');
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('D-4 TTL：输注升级行超 30 分钟退役，容量截断语义保持', async () => {
    // 档位值=裁决固化（30 分钟），测试以字面量锚定档位防擅自调档
    vi.useFakeTimers();
    try {
      const wrapper = await mountNurseBoard('?wardId=1001');
      h.onNursingFrame?.({
        type: 'INFUSION_ESCALATION',
        payload: {
          alarmNo: 'AL20261003005',
          executionNos: ['EX20261003011', 'EX20261003012'],
          escalatedCount: 2,
          taskEscalatedCount: 1,
        },
        occurredAt: '2026-10-03T06:00:00Z',
      });
      await flushPromises();
      expect(wrapper.findAll('.nurse-escalation')).toHaveLength(2);

      // 快进越过 30 分钟 TTL：轮询驱动的统一清理使升级行退役（清单空则整列不渲染）
      await vi.advanceTimersByTimeAsync(30 * 60 * 1000 + 30 * 1000);
      expect(wrapper.findAll('.nurse-escalation')).toHaveLength(0);
      expect(wrapper.find('[aria-label="输液动态"]').text()).not.toContain('EX20261003011');

      // 新升级帧到达：过期行先清、新行前插，容量 20 截断语义保持（25 个执行单号只保留前 20）
      const executionNos: string[] = [];
      for (let i = 1; i <= 25; i += 1) {
        executionNos.push(`EX20261003${100 + i}`);
      }
      h.onNursingFrame?.({
        type: 'INFUSION_ESCALATION',
        payload: {
          alarmNo: 'AL20261003006',
          executionNos,
          escalatedCount: 25,
          taskEscalatedCount: 1,
        },
        occurredAt: '2026-10-03T06:40:00Z',
      });
      await flushPromises();
      const rows = wrapper.findAll('.nurse-escalation');
      expect(rows).toHaveLength(20);
      expect(rows[0]?.text()).toContain('EX20261003101');
      expect(rows[19]?.text()).toContain('EX20261003120');
      // 容量外（第 21~25 个）与已退役旧行均不出渲染面
      expect(wrapper.text()).not.toContain('EX20261003125');
      expect(wrapper.text()).not.toContain('EX20261003012');
      wrapper.unmount();
    } finally {
      vi.useRealTimers();
    }
  });
});
