// 病区输液看板单测（/ward/infusion-board，M16 WS+REST 混合面前端面）：REST 全量加载渲染
// 床卡列表与 15/10/5ml 三档着色（fuy-infusion-card--{level} 机器判据，档位判定归后端）、
// 挂载即建连并订阅遥测/告警双主题（路径精确等于契约值）、遥测摘要帧热刷新与残缺载荷毒帧
// 零刷新零异常、输液告急告警帧联动提示条渲染、WS 断开 REST 轮询降级（30s 周期）与连接后
// 停止轮询、页面隐藏暂停轮询与订阅消费（EX-41，恢复可见立即刷一轮）、病区切换退旧订新并重查。
// vi.mock('@/composables/useIotStomp') 桩化连接层（真实 ref 供 watch 触发），
// vi.mock('@/api/ward') 承载零出网，断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { infusionBoard } from '@/api/ward';
import type { InfusionBoardVO } from '@/api/ward';
import * as stompModule from '@/composables/useIotStomp';
import InfusionBoardView from './InfusionBoardView.vue';

/** stomp 桩内部状态（mock 工厂与用例共享：handlers 捕获订阅回调供用例投递帧） */
const stompState = vi.hoisted(() => ({
  handlers: [] as { destination: string; onFrame: (payload: unknown) => void }[],
}));

vi.mock('@/composables/useIotStomp', async () => {
  const { ref } = await import('vue');
  /** 连接状态真实 ref（可写：用例内翻转触发页面 watch——降级轮询的前提） */
  const state = ref<'disconnected' | 'connecting' | 'connected'>('disconnected');
  return {
    connectionState: state,
    telemetryTopicPath: (wardId: string) => `/topic/iot/telemetry/${wardId}`,
    alarmTopicPath: (wardId: string) => `/topic/iot/alarm/${wardId}`,
    connect: vi.fn(),
    disconnect: vi.fn(async () => {}),
    subscribeTopic: vi.fn((destination: string, onFrame: (payload: unknown) => void) => {
      stompState.handlers.push({ destination, onFrame });
      return { id: 'sub-0', destination, unsubscribe: vi.fn() };
    }),
    // 测试锚点：用例内读写连接状态（触发页面 watch）
    __state: state,
  };
});

vi.mock('@/api/ward', () => ({
  WARD_OPTIONS: [
    { code: '1001', label: '1001 演示病区' },
    { code: '1002', label: '1002 演示病区' },
  ],
  INFUSION_ALERT_LABELS: { NONE: '正常', YELLOW: '黄档预警', ORANGE: '橙档告急', RED: '红档危急' },
  infusionBoard: { byWard: vi.fn() },
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** stomp 桩连接状态 ref（mock 工厂内创建，经命名导出取回） */
const stateRef = (stompModule as unknown as { __state: { value: string } }).__state;

/** 看板快照（三档设备 + 一台正常 + 一台无遥测） */
function boardMock(): InfusionBoardVO {
  return {
    wardId: '1001',
    devices: [
      { deviceId: 'dev-yellow', remainLatest: 12.5, dropRateLatest: 3.2, alertLevel: 'YELLOW' },
      { deviceId: 'dev-orange', remainLatest: 8, dropRateLatest: 2.1, alertLevel: 'ORANGE' },
      { deviceId: 'dev-red', remainLatest: 3.5, dropRateLatest: 0, alertLevel: 'RED' },
      { deviceId: 'dev-none', remainLatest: 400, dropRateLatest: 5.5, alertLevel: 'NONE' },
      {
        deviceId: 'dev-nodata',
        remainLatest: undefined,
        dropRateLatest: undefined,
        alertLevel: 'NONE',
      },
    ],
  };
}

/** 取指定主题的帧回调（未订阅即抛错，防静默断链） */
function frameHandler(destination: string) {
  const handler = stompState.handlers.find((item) => item.destination === destination);
  if (!handler) {
    throw new Error(`未找到订阅：${destination}`);
  }
  return handler.onFrame;
}

/** 当前在挂看板实例（EX-41 后组件持有 document 级 visibilitychange 监听：用例不退挂会跨
 * 用例串扰事件分发——mountBoard 统一先退挂上一实例，保证任一时刻单实例在挂） */
let activeBoard: VueWrapper | null = null;

/** 挂载并等待首屏加载完成（统一入口，返回持引用供换病区用例操作） */
async function mountBoard(): Promise<VueWrapper> {
  // 先退挂上一用例残留实例（清其 visibilitychange 监听与轮询定时器，防事件分发串扰）
  activeBoard?.unmount();
  activeBoard = null;
  const wrapper = mount(InfusionBoardView);
  activeBoard = wrapper;
  await flushPromises();
  return wrapper;
}

describe('病区输液看板', () => {
  beforeEach(() => {
    vi.mocked(infusionBoard.byWard).mockReset();
    vi.mocked(stompModule.connect).mockClear();
    vi.mocked(stompModule.disconnect).mockClear();
    vi.mocked(stompModule.subscribeTopic).mockClear();
    stompState.handlers.length = 0;
    stateRef.value = 'disconnected';
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(infusionBoard.byWard).mockResolvedValue({ wardId: '1001', devices: [] });
  });

  it('REST 全量加载渲染床卡列表与 15/10/5ml 三档着色（档位徽标机器判据）', async () => {
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const wrapper = await mountBoard();
    expect(infusionBoard.byWard).toHaveBeenCalledWith('1001');
    const text = wrapper.text();
    expect(text).toContain('dev-yellow');
    expect(text).toContain('dev-red');
    expect(text).toContain('12.5');
    // 无遥测设备余量占位不炸渲染
    expect(text).toContain('dev-nodata');
    // 三档 + 正常态着色类契约（机器判据；色值经语义 token 承载，档位判定归后端）
    expect(wrapper.find('.fuy-infusion-card--yellow').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--orange').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--red').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--none').exists()).toBe(true);
    // 档位中文词表可见
    expect(text).toContain('黄档预警');
    expect(text).toContain('红档危急');
  });

  it('挂载即建连并订阅遥测/告警双主题（路径精确等于契约值）', async () => {
    await mountBoard();
    expect(stompModule.connect).toHaveBeenCalledTimes(1);
    expect(stompModule.subscribeTopic).toHaveBeenCalledTimes(2);
    expect(stompState.handlers.map((item) => item.destination)).toEqual([
      '/topic/iot/telemetry/1001',
      '/topic/iot/alarm/1001',
    ]);
  });

  it('遥测摘要帧触发热刷新，残缺载荷毒帧零刷新零异常（订阅保持）', async () => {
    await mountBoard();
    expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
    const onTelemetry = frameHandler('/topic/iot/telemetry/1001');
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    // 合法摘要帧 → 热刷新（byWard 第二次调用）
    onTelemetry({
      count: 2,
      occurredAtUpperBound: '2026-09-26T02:00:00Z',
      items: [{ deviceId: 'dev-yellow', metricCode: 'INFUSION_SHORTAGE' }],
    });
    await flushPromises();
    expect(infusionBoard.byWard).toHaveBeenCalledTimes(2);
    // 残缺载荷毒帧：不抛异常、零刷新（收窄守卫拒绝）
    expect(() => onTelemetry({ count: 1 })).not.toThrow();
    expect(() => onTelemetry('not-a-payload')).not.toThrow();
    await flushPromises();
    expect(infusionBoard.byWard).toHaveBeenCalledTimes(2);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('输液告急告警帧渲染联动提示条（告警号/级别/触发值可见）', async () => {
    const wrapper = await mountBoard();
    const onAlarm = frameHandler('/topic/iot/alarm/1001');
    onAlarm({
      alarmNo: 'AL20260926001',
      deviceId: 'dev-red',
      patientId: null,
      visitId: null,
      wardId: '1001',
      alarmLevel: 'CRITICAL',
      metricCode: 'INFUSION_SHORTAGE',
      triggerValue: '3.5',
      ruleId: '901',
      occurredAt: '2026-09-26T02:00:00Z',
    });
    await flushPromises();
    const banner = wrapper.find('[data-test="call-linkage-banner"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('AL20260926001');
    expect(banner.text()).toContain('危急');
    expect(banner.text()).toContain('3.5');
  });

  it('WS 断开时 REST 轮询降级（30s 周期），连接后停止轮询', async () => {
    vi.useFakeTimers();
    try {
      await mountBoard();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
      // 断开态：30s 轮询降级
      await vi.advanceTimersByTimeAsync(30000);
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(30000);
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(3);
      // 连接落地：轮询停止（WS 增量接管）
      stateRef.value = 'connected';
      await flushPromises();
      await vi.advanceTimersByTimeAsync(60000);
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it('页面隐藏暂停轮询降级出网，恢复可见立即刷一轮再续（EX-41）', async () => {
    vi.useFakeTimers();
    // jsdom document.hidden 为原型 getter（默认 false）：spyOn get 换桩控制隐藏态
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');
    try {
      await mountBoard();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
      // 隐藏：两拍 30s 轮询周期均不再出网（后台标签页零请求）
      hiddenSpy.mockReturnValue(true);
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(60000);
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
      // 恢复可见：立即刷一轮（不等下一拍轮询兜底）
      hiddenSpy.mockReturnValue(false);
      document.dispatchEvent(new Event('visibilitychange'));
      await flushPromises();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(2);
      // 再续：下一拍 30s 轮询恢复出网
      await vi.advanceTimersByTimeAsync(30000);
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(3);
    } finally {
      hiddenSpy.mockRestore();
      vi.useRealTimers();
    }
  });

  it('页面隐藏暂停订阅消费：遥测/告警帧零热刷新（告急提示条仍落），恢复可见立即刷一轮', async () => {
    // 隐藏态换桩同上：真实时间轴（帧驱动刷新走节流时钟，不用假定时器）
    const hiddenSpy = vi.spyOn(document, 'hidden', 'get');
    try {
      const wrapper = await mountBoard();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
      const onTelemetry = frameHandler('/topic/iot/telemetry/1001');
      const onAlarm = frameHandler('/topic/iot/alarm/1001');
      hiddenSpy.mockReturnValue(true);
      document.dispatchEvent(new Event('visibilitychange'));
      // 合法遥测摘要帧到达：隐藏态丢弃信号零热刷新
      onTelemetry({
        count: 1,
        occurredAtUpperBound: '2026-09-26T02:00:00Z',
        items: [{ deviceId: 'dev-red', metricCode: 'INFUSION_SHORTAGE' }],
      });
      // 输液告急帧到达：热刷新暂停但联动提示条仍落（恢复可见即见，告警不因隐藏丢失）
      onAlarm({
        alarmNo: 'AL20260926002',
        deviceId: 'dev-red',
        patientId: null,
        visitId: null,
        wardId: '1001',
        alarmLevel: 'CRITICAL',
        metricCode: 'INFUSION_SHORTAGE',
        triggerValue: '3.5',
        ruleId: '901',
        occurredAt: '2026-09-26T02:00:00Z',
      });
      await flushPromises();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(1);
      expect(wrapper.find('[data-test="call-linkage-banner"]').exists()).toBe(true);
      // 恢复可见：立即刷一轮补齐隐藏期变更
      hiddenSpy.mockReturnValue(false);
      document.dispatchEvent(new Event('visibilitychange'));
      await flushPromises();
      expect(infusionBoard.byWard).toHaveBeenCalledTimes(2);
    } finally {
      hiddenSpy.mockRestore();
    }
  });

  it('病区切换退旧订新（两主题路径随病区切换）并重查看板', async () => {
    const wrapper = await mountBoard();
    await wrapper.find('select[aria-label="病区"]').setValue('1002');
    await flushPromises();
    // 看板重查（byWard 第二次调用携新病区）
    expect(infusionBoard.byWard).toHaveBeenLastCalledWith('1002');
    // 新病区双主题落地（旧主题句柄已退订，新主题重新登记）
    expect(stompState.handlers.at(-2)?.destination).toBe('/topic/iot/telemetry/1002');
    expect(stompState.handlers.at(-1)?.destination).toBe('/topic/iot/alarm/1002');
  });
});
