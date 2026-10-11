// 病区输液看板单测（/ward/infusion-board，M16 WS+REST 混合面前端面 · 暖纸卷宗丁-1 换脸重排）。
// 「换脸不换业务」：STOMP 订阅/帧节流/visibilityState 暂停/轮询降级/banner 联动链断言全数
// 保留，仅随 WARD_OPTIONS 假常量清零（G4→listOrgs 真数据三态）调整装载时序断言。
// 蓝图锚点（pages-2 丁-1.7）：
// ①grid 卡墙结构断言（旧换行错位载体清零，源读取值断言）；
// ②纸面卡三档规线类断言（fuy-infusion-card--* 契约类保留 + 纸面卡/左缘规线取值源读，旧白底
//   实卡取值清零——旧 spec 无白底字面断言可删，重写按双通道严格度不降并以源读补机器证据）；
// ③WS 状态胶囊两态与呼吸点断言（on=绿+呼吸点 / off=琥珀）；
// ④趋势抽屉三态（点卡出网/加载骨架/空序列退化）+ .fuy-drawer 520 档 + 双 polyline；
// ⑤listOrgs 三态（装配即拉/在途禁选/失败恢复）+ banner 洗底取色源读断言（状态朱 alpha 派生）。
// 另锚：>120 卡尾号段分组（⑨.4/G5，groupWallPatients 形态页内实现）、页脚快照时点批注、
// 契约 ⑦.4 根节点不挂 fuy-stagger、WARD_OPTIONS 假常量本页零消费反向断言、空态脸（G6）。
// vi.mock('@/composables/useIotStomp') 桩化连接层（真实 ref 供 watch 触发），
// vi.mock('@/api/ward')/vi.mock('@/api/system') 承载零出网，断言业务结果不绑定实现细节。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { getInfusionHistory, infusionBoard } from '@/api/ward';
import type { InfusionBoardVO, InfusionHistoryVO } from '@/api/ward';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
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

// WARD_OPTIONS 假常量随 G4 清零：mock 面不再提供该导出（页源零消费另有反向断言钉死）
vi.mock('@/api/ward', () => ({
  INFUSION_ALERT_LABELS: { NONE: '正常', YELLOW: '黄档预警', ORANGE: '橙档告急', RED: '红档危急' },
  infusionBoard: { byWard: vi.fn() },
  getInfusionHistory: vi.fn(),
}));

vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

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

/** 病区清单种子（listOrgs WARD 档出参；两病区供切病区用例，病区码与 WS 主题段同源纯数字串） */
const WARD_SEED: OrgVO[] = [
  { id: '1', orgCode: '1001', orgName: '一病区', orgType: 'WARD', sort: 1 },
  { id: '2', orgCode: '1002', orgName: '二病区', orgType: 'WARD', sort: 2 },
];

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
      // 真机 JSON null 形态（api 契约：无遥测设备余量为 null）：生成物 optional 类型未表达
      // null，运行时真态经宽化承载——G6 空值「—」占位红线随验段琢级打回补齐（null 与
      // undefined 各覆盖一例，严格度不降留痕）
      {
        deviceId: 'dev-missing',
        remainLatest: null as unknown as number,
        dropRateLatest: null as unknown as number,
        alertLevel: 'NONE',
      },
    ],
  };
}

/** 输液历史 24h 双序列（遥测聚合点：余量取桶末值、滴速取桶均值，与页内取值口径一致） */
function historyMock(): InfusionHistoryVO {
  return {
    deviceId: 'dev-yellow',
    remainSeries: [
      { deviceId: 'dev-yellow', time: '2026-10-10T08:00:00Z', last: 420 },
      { deviceId: 'dev-yellow', time: '2026-10-10T12:00:00Z', last: 260 },
      { deviceId: 'dev-yellow', time: '2026-10-10T16:00:00Z', last: 90 },
    ],
    dropSeries: [
      { deviceId: 'dev-yellow', time: '2026-10-10T08:00:00Z', avg: 3.2 },
      { deviceId: 'dev-yellow', time: '2026-10-10T12:00:00Z', avg: 2.8 },
      { deviceId: 'dev-yellow', time: '2026-10-10T16:00:00Z', avg: 0 },
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

/** 挂载并等待首屏装载完成（统一入口：病区清单到达→落默认病区→拉快照，全链 flush） */
async function mountBoard(): Promise<VueWrapper> {
  // 先退挂上一用例残留实例（清其 visibilitychange 监听与轮询定时器，防事件分发串扰）
  activeBoard?.unmount();
  activeBoard = null;
  setActivePinia(createPinia());
  const wrapper = mount(InfusionBoardView);
  activeBoard = wrapper;
  await flushPromises();
  return wrapper;
}

/** 点击指定设备床卡开趋势抽屉（按设备号 data-test 定位防跨卡误中） */
async function openTrend(wrapper: VueWrapper, deviceId: string): Promise<void> {
  await wrapper.find(`button[data-test="infusion-card-${deviceId}"]`).trigger('click');
  await flushPromises();
}

describe('病区输液看板（暖纸卷宗丁-1）', () => {
  beforeEach(() => {
    vi.mocked(infusionBoard.byWard).mockReset();
    vi.mocked(getInfusionHistory).mockReset();
    vi.mocked(listOrgs).mockReset();
    vi.mocked(stompModule.connect).mockClear();
    vi.mocked(stompModule.disconnect).mockClear();
    vi.mocked(stompModule.subscribeTopic).mockClear();
    stompState.handlers.length = 0;
    stateRef.value = 'disconnected';
    // 只读面兜底：病区清单默认落种子（listOrgs 真数据三态可复现）、快照兜底空设备
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
    vi.mocked(infusionBoard.byWard).mockResolvedValue({ wardId: '1001', devices: [] });
  });

  it('锚点⑤ listOrgs 装载三态（装载/在途）：装配即拉真病区清单，在途禁选零出网，到达落默认病区并接线', async () => {
    let resolveOrgs!: (value: OrgVO[]) => void;
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          resolveOrgs = resolve;
        }),
    );
    setActivePinia(createPinia());
    const wrapper = mount(InfusionBoardView);
    activeBoard = wrapper;
    await flushPromises();
    // 装配即拉真数据（G4：WARD_OPTIONS 假常量本页零消费）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    // 在途：病区下拉禁用；快照与订阅零出网（出网值必须锚定真实病区编码，禁假病区路径）
    expect(wrapper.find('select[aria-label="病区"]').attributes('disabled')).toBeDefined();
    expect(infusionBoard.byWard).not.toHaveBeenCalled();
    expect(stompModule.subscribeTopic).not.toHaveBeenCalled();
    // 建连不依赖病区清单（装配即建连）
    expect(stompModule.connect).toHaveBeenCalledTimes(1);
    // 清单到达：落默认首项病区 → 订阅双主题 + 拉快照
    resolveOrgs(WARD_SEED);
    await flushPromises();
    expect(wrapper.find('select[aria-label="病区"]').attributes('disabled')).toBeUndefined();
    expect(infusionBoard.byWard).toHaveBeenCalledWith('1001');
    expect(stompState.handlers.map((item) => item.destination)).toEqual([
      '/topic/iot/telemetry/1001',
      '/topic/iot/alarm/1001',
    ]);
  });

  it('锚点⑤ listOrgs 失败恢复：说问题给恢复+订阅与快照零出网，重试同路落默认病区', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    const wrapper = await mountBoard();
    // 失败：说问题给恢复（G4/G6，不伪装空态）；病区下拉禁用、零订阅零快照
    expect(wrapper.text()).toContain('病区清单加载失败');
    expect(wrapper.find('select[aria-label="病区"]').attributes('disabled')).toBeDefined();
    expect(stompModule.subscribeTopic).not.toHaveBeenCalled();
    expect(infusionBoard.byWard).not.toHaveBeenCalled();
    // 重试：同路落默认病区并接线（恢复条撤下）
    vi.mocked(listOrgs).mockResolvedValueOnce(WARD_SEED);
    await wrapper.find('[data-test="ward-retry"]').trigger('click');
    await flushPromises();
    expect(infusionBoard.byWard).toHaveBeenCalledWith('1001');
    expect(wrapper.text()).not.toContain('病区清单加载失败');
  });

  it('病区清单为空：下拉禁用+墙区空态「暂无可用病区」（诚实数据态，与设备空态区分）', async () => {
    vi.mocked(listOrgs).mockResolvedValue([]);
    const wrapper = await mountBoard();
    expect(wrapper.find('select[aria-label="病区"]').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('暂无可用病区');
    expect(infusionBoard.byWard).not.toHaveBeenCalled();
  });

  it('门牌页首三锚 + 契约⑦.4 根节点不挂 fuy-stagger（flow 包裹层承接级联）', async () => {
    const wrapper = await mountBoard();
    const head = wrapper.find('.fuy-page-head');
    expect(head.exists()).toBe(true);
    expect(head.find('.fuy-page-title').text()).toBe('病区输液看板');
    expect(head.find('.fuy-page-en').text()).toBe('INFUSION BOARD');
    // 批注行：刷新机制批注 + 「谁·时刻」（空会话以 — 占位零伪数据）
    const note = head.find('.fuy-page-note').text();
    expect(note).toContain('遥测摘要帧节流热刷新');
    expect(note).toContain('REST 30s 轮询降级');
    expect(note).toContain('签认人');
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger（stagger 挂页内 flow 包裹层，防双重进场节奏）
    const root = wrapper.find('.infusion-board');
    expect(root.classes()).toContain('fuy-page');
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.infusion-flow').classes()).toContain('fuy-stagger');
  });

  it('锚点①② grid 卡墙纸面卡：三档契约类保留、五卡 grid 直出，旧换行错位载体与白底实卡清零（源读）', async () => {
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const wrapper = await mountBoard();
    const wall = wrapper.find('.infusion-wall');
    expect(wall.exists()).toBe(true);
    expect(wrapper.findAll('.infusion-card')).toHaveLength(6);
    // 三档 + 正常态档位契约类（机器判据；色值经语义 token 承载，档位判定归后端）
    expect(wrapper.find('.fuy-infusion-card--yellow').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--orange').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--red').exists()).toBe(true);
    expect(wrapper.find('.fuy-infusion-card--none').exists()).toBe(true);
    expect(wrapper.text()).toContain('黄档预警');
    expect(wrapper.text()).toContain('红档危急');
    // 无遥测设备余量占位不炸渲染（undefined 缺字段形态）
    expect(wrapper.text()).toContain('dev-nodata');
    // 真机 JSON null 形态：空值「—」占位（G6 红线），裸 'null' 串零漏出
    expect(wrapper.text()).toContain('dev-missing');
    expect(wrapper.text()).not.toContain('null');

    // 源读取值断言（样式回归轻量通道，AlarmRuleView.spec 同款惯例）
    const source = readFileSync(join(import.meta.dirname, 'InfusionBoardView.vue'), 'utf-8');
    // 锚点①：grid 卡墙（auto-fill 180px 轨道）+ 旧换行错位载体清零
    const wallBlock = source.match(/\.infusion-wall\s*\{[^}]*\}/);
    expect(wallBlock).not.toBeNull();
    expect(wallBlock?.[0]).toContain('display: grid');
    expect(wallBlock?.[0]).toContain('repeat(auto-fill, minmax(180px, 1fr))');
    expect(source).not.toContain('inline-block');
    // 锚点②：纸面卡取值（卡面亮纸 + 发丝描边），旧白底实卡清零
    const cardBlock = source.match(/\.infusion-card\s*\{[^}]*\}/);
    expect(cardBlock).not.toBeNull();
    expect(cardBlock?.[0]).toContain('background: var(--fuy-surface-card)');
    expect(cardBlock?.[0]).toContain('border: var(--fuy-border-hairline)');
    // G3 三通道之一：左缘 4px 档位规线（三档各取 infusion 语义通道色）
    expect(source).toContain('border-left: 4px solid var(--fuy-color-infusion-yellow)');
    expect(source).toContain('border-left: 4px solid var(--fuy-color-infusion-orange)');
    expect(source).toContain('border-left: 4px solid var(--fuy-color-infusion-red)');
  });

  it('锚点③ WS 状态胶囊两态：未连接=琥珀降级态零呼吸点，连接=绿+呼吸点（实时语义专属）', async () => {
    const wrapper = await mountBoard();
    // 未连接（桩默认态）：琥珀胶囊 + 降级文案，零呼吸点
    let pill = wrapper.find('[data-test="ws-pill"]');
    expect(pill.classes()).toContain('fuy-status-pill--amber');
    expect(pill.classes()).not.toContain('fuy-status-pill--success');
    expect(pill.text()).toContain('WS 未连接（REST 轮询降级）');
    expect(pill.find('.fuy-live-dot').exists()).toBe(false);
    // 连接落地：绿胶囊 + 呼吸点
    stateRef.value = 'connected';
    await flushPromises();
    pill = wrapper.find('[data-test="ws-pill"]');
    expect(pill.classes()).toContain('fuy-status-pill--success');
    expect(pill.find('.fuy-live-dot').exists()).toBe(true);
    expect(pill.text()).toContain('WS 已连接');
  });

  it('REST 全量空设备：墙区空态脸「暂无输液设备遥测」（G6 主句+说明给下一步，默认插画清零）', async () => {
    const wrapper = await mountBoard();
    const empty = wrapper.find('.infusion-wall .fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.text()).toContain('暂无输液设备遥测');
    expect(wrapper.findComponent({ name: 'ElEmpty' }).exists()).toBe(false);
  });

  it('挂载建连、病区清单到达后订阅遥测/告警双主题（路径精确等于契约值）', async () => {
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

  it('输液告急告警帧渲染联动提示条（告警号/级别/触发值可见），洗底取状态朱 alpha 派生（锚点⑤源读）', async () => {
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

    // banner 洗底取色源读断言（蓝图锚点⑤）：印泥朱描边 + 状态朱 alpha 派生洗底，旧中性灰底清零
    const source = readFileSync(join(import.meta.dirname, 'InfusionBoardView.vue'), 'utf-8');
    const bannerBlock = source.match(/\.infusion-linkage-banner\s*\{[^}]*\}/);
    expect(bannerBlock).not.toBeNull();
    expect(bannerBlock?.[0]).toContain('rgba(180, 35, 24, 0.06)');
    expect(bannerBlock?.[0]).toContain('var(--fuy-color-infusion-red)');
    expect(bannerBlock?.[0]).not.toContain('gray-50');
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

  it('锚点④ 趋势抽屉（出网态）：点床卡出网拉 24h 历史，抽屉挂 .fuy-drawer 520px 档出双 polyline', async () => {
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    vi.mocked(getInfusionHistory).mockResolvedValue(historyMock());
    const wrapper = await mountBoard();
    await openTrend(wrapper, 'dev-yellow');
    // 开抽屉即出网（deviceId 路径锚定）
    expect(vi.mocked(getInfusionHistory)).toHaveBeenCalledWith('dev-yellow');
    // 抽屉脸锚（G2/⑤#10）：挂 .fuy-drawer + 520px 统一档
    expect(wrapper.find('.fuy-drawer').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('size')).toBe('520px');
    // 双序列双 polyline（余量/滴速）+ 图例双通道文字（色必配文字）
    expect(wrapper.findAll('.infusion-trend-chart polyline')).toHaveLength(2);
    const text = wrapper.text();
    expect(text).toContain('余量 ml');
    expect(text).toContain('滴速 ml/h');
    expect(text).toContain('dev-yellow');
  });

  it('锚点④ 趋势抽屉（加载态）：历史在途出骨架占位，空态脸不提前；到达后骨架撤下曲线落位', async () => {
    let resolveHistory!: (value: InfusionHistoryVO) => void;
    vi.mocked(getInfusionHistory).mockImplementation(
      () =>
        new Promise<InfusionHistoryVO>((resolve) => {
          resolveHistory = resolve;
        }),
    );
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const wrapper = await mountBoard();
    await openTrend(wrapper, 'dev-yellow');
    // 在途：骨架占位可见，空态脸不提前出现（禁 loading 期伪装空序列）
    expect(wrapper.find('.fuy-drawer .fuy-sk').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('暂无遥测历史');
    resolveHistory(historyMock());
    await flushPromises();
    expect(wrapper.find('.fuy-drawer .fuy-sk').exists()).toBe(false);
    expect(wrapper.findAll('.infusion-trend-chart polyline')).toHaveLength(2);
  });

  it('锚点④ 趋势抽屉（空序列退化态）：双序列皆空出空态脸，禁裸 polyline 空图', async () => {
    vi.mocked(getInfusionHistory).mockResolvedValue({
      deviceId: 'dev-none',
      remainSeries: [],
      dropSeries: [],
    });
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const wrapper = await mountBoard();
    await openTrend(wrapper, 'dev-none');
    expect(wrapper.find('.fuy-drawer .fuy-empty').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无遥测历史');
    expect(wrapper.findAll('.infusion-trend-chart polyline')).toHaveLength(0);
  });

  it('卡墙 >120 台按设备尾号段分组出组头签行（⑨.4/G5），常规量零分组头', async () => {
    // 121 台设备（尾号 0001-0121）：跨过分组阈值，按尾号十位段出组头
    const many = Array.from({ length: 121 }, (_, i) => ({
      deviceId: `dev-${String(i + 1).padStart(4, '0')}`,
      remainLatest: 100,
      dropRateLatest: 2,
      alertLevel: 'NONE',
    }));
    vi.mocked(infusionBoard.byWard).mockResolvedValue({ wardId: '1001', devices: many });
    const grouped = await mountBoard();
    expect(grouped.find('.infusion-wall').text()).toContain('尾号 0000-0009');
    // 尾号 1-121 落 0/10/…/120 共 13 段（算法确定性锚点）
    expect(grouped.findAll('.infusion-group-head')).toHaveLength(13);
    // 常规量（≤120）零分组头（单组直出零分组开销）
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const regular = await mountBoard();
    expect(regular.findAll('.infusion-group-head')).toHaveLength(0);
  });

  it('页脚批注：快照时点回填（客户端记录的取数时点，零伪数据）', async () => {
    vi.mocked(infusionBoard.byWard).mockResolvedValue(boardMock());
    const wrapper = await mountBoard();
    const foot = wrapper.find('.fuy-page-foot');
    expect(foot.exists()).toBe(true);
    // 快照时点=成功取数后的本地钟面（HH:mm:ss），非 — 占位
    expect(foot.text()).toMatch(/快照时点 \d{2}:\d{2}:\d{2}/);
  });

  it('WARD_OPTIONS 假常量本页零消费（G4 收口归域：源读反向断言）', () => {
    const source = readFileSync(join(import.meta.dirname, 'InfusionBoardView.vue'), 'utf-8');
    expect(source).not.toContain('WARD_OPTIONS');
  });
});
