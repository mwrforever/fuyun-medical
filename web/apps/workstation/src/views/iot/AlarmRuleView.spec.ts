// 告警规则页单测（/iot/alarm-rules，M14 FU-M14-08 告警引擎前端面）：规则列表加载与三类
// 源徽标渲染（fuy-rule-tag--{type} 机器判据）、THRESHOLD 规则缺持续时长/恢复带零出网
// 显式校验、THRESHOLD 完整提交出网携阈值参数并刷新、模拟回放出网并在弹窗展示扫描行数
// 与触发清单、活跃告警等级徽标渲染与确认出网、关闭缺原因零出网拦截与携原因出网。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { alarmRules, alarms } from '@/api/iot';
import type { AlarmRuleVO, AlarmVO } from '@/api/iot';
import AlarmRuleView from './AlarmRuleView.vue';

vi.mock('@/api/iot', () => ({
  RULE_TYPE_LABELS: {
    DEVICE_ALARM: '设备报警透传',
    THRESHOLD: '平台阈值',
    OFFLINE: '离线',
  },
  ALARM_LEVEL_LABELS: {
    INFO: '提示',
    WARNING: '警告',
    CRITICAL: '危急',
  },
  ALARM_STATUS_LABELS: {
    ACTIVE: '活跃',
    ACKNOWLEDGED: '已确认',
    CLOSED: '已关闭',
  },
  alarmRules: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    simulate: vi.fn(),
  },
  alarms: { list: vi.fn(), acknowledge: vi.fn(), close: vi.fn() },
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

/** 规则行（三类源可覆写） */
function ruleMock(partial: Partial<AlarmRuleVO> = {}): AlarmRuleVO {
  return {
    id: 'rule-2',
    ruleName: '心率超阈告警',
    ruleType: 'THRESHOLD',
    metricCode: 'MDC_ECG_HEART_RATE',
    compareOp: '>',
    thresholdValue: 150,
    durationSecs: 60,
    recoveryBand: 10,
    alarmLevel: 'CRITICAL',
    enabled: true,
    createdAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 三类源齐全的规则清单 */
function threeTypeRules(): AlarmRuleVO[] {
  return [
    ruleMock({
      id: 'rule-1',
      ruleName: '血氧设备报警透传',
      ruleType: 'DEVICE_ALARM',
      metricCode: 'MDC_PULS_OXIM_SAT',
      compareOp: undefined,
      thresholdValue: undefined,
      durationSecs: undefined,
      recoveryBand: undefined,
    }),
    ruleMock({ id: 'rule-2', ruleName: '心率超阈告警', ruleType: 'THRESHOLD' }),
    ruleMock({
      id: 'rule-3',
      ruleName: '设备离线告警',
      ruleType: 'OFFLINE',
      metricCode: undefined,
      compareOp: undefined,
      thresholdValue: undefined,
      durationSecs: undefined,
      recoveryBand: undefined,
      offlineSecs: 300,
    }),
  ];
}

/** 告警行（活跃/已确认两态） */
function alarmMock(partial: Partial<AlarmVO> = {}): AlarmVO {
  return {
    id: 'a-1',
    alarmNo: 'ALM-20260926-0001',
    ruleId: 'rule-2',
    deviceId: 'dev-101',
    patientId: '1932000000000000001',
    wardId: 'W01',
    alarmLevel: 'CRITICAL',
    metricCode: 'MDC_ECG_HEART_RATE',
    triggerValue: '168',
    status: 'ACTIVE',
    triggerCount: 3,
    lastTriggeredAt: '2026-09-26T09:30:00+08:00',
    ...partial,
  };
}

/** 空告警分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行 */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 在指定行内按按钮文案点击（行作用域操作，防跨行误中） */
async function clickRowButton(
  wrapper: VueWrapper,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = findRow(wrapper, rowText);
  const button = row?.findAll('button').find((b) => b.text() === buttonText);
  if (!button) {
    throw new Error(`未找到行内按钮：${buttonText}`);
  }
  await button.trigger('click');
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('告警规则页', () => {
  beforeEach(() => {
    for (const fn of [
      alarmRules.list,
      alarmRules.create,
      alarmRules.update,
      alarmRules.remove,
      alarmRules.simulate,
      alarms.list,
      alarms.acknowledge,
      alarms.close,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(alarmRules.list).mockResolvedValue([]);
    vi.mocked(alarms.list).mockResolvedValue(emptyPage());
  });

  it('规则列表加载渲染三类源徽标（状态类机器判据）', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    const text = wrapper.text();
    // 三类源中文词表与规则名可见
    expect(text).toContain('血氧设备报警透传');
    expect(text).toContain('心率超阈告警');
    expect(text).toContain('设备离线告警');
    expect(text).toContain('设备报警透传');
    expect(text).toContain('平台阈值');
    expect(text).toContain('离线');
    // 三类源色标状态类契约（机器判据；色值经 --fuy-color-rule-* 语义 token 承载）
    expect(wrapper.find('.fuy-rule-tag--device_alarm').exists()).toBe(true);
    expect(wrapper.find('.fuy-rule-tag--threshold').exists()).toBe(true);
    expect(wrapper.find('.fuy-rule-tag--offline').exists()).toBe(true);
  });

  it('新建 THRESHOLD 规则缺持续时长/恢复带零出网显式校验拦截', async () => {
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await wrapper.find('input[aria-label="规则名称"]').setValue('心率超阈告警');
    await wrapper.find('select[aria-label="规则类型"]').setValue('THRESHOLD');
    await wrapper.find('input[aria-label="指标编码"]').setValue('MDC_ECG_HEART_RATE');
    await wrapper.find('input[aria-label="阈值"]').setValue('150');
    // 持续时长/恢复带留空（THRESHOLD 必填项）
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写持续时长（秒）');
    expect(alarmRules.create).not.toHaveBeenCalled();
  });

  it('新建 THRESHOLD 规则完整提交出网携阈值参数并刷新规则列表', async () => {
    vi.mocked(alarmRules.create).mockResolvedValue(ruleMock());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await wrapper.find('input[aria-label="规则名称"]').setValue('心率超阈告警');
    await wrapper.find('select[aria-label="规则类型"]').setValue('THRESHOLD');
    await wrapper.find('input[aria-label="指标编码"]').setValue('MDC_ECG_HEART_RATE');
    await wrapper.find('select[aria-label="比较符"]').setValue('>');
    await wrapper.find('input[aria-label="阈值"]').setValue('150');
    await wrapper.find('input[aria-label="持续时长（秒）"]').setValue('60');
    await wrapper.find('input[aria-label="恢复带"]').setValue('10');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(alarmRules.create).toHaveBeenCalledWith({
      ruleName: '心率超阈告警',
      ruleType: 'THRESHOLD',
      deviceId: undefined,
      metricCode: 'MDC_ECG_HEART_RATE',
      compareOp: '>',
      thresholdValue: 150,
      durationSecs: 60,
      recoveryBand: 10,
      silenceWindowSecs: undefined,
      offlineSecs: undefined,
      alarmLevel: 'WARNING',
      escalateAfterSecs: undefined,
      enabled: true,
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 规则列表重载（list 第二次调用）
    expect(alarmRules.list).toHaveBeenCalledTimes(2);
  });

  it('模拟回放提交出网并在弹窗展示扫描行数与触发清单', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    vi.mocked(alarmRules.simulate).mockResolvedValue({
      scannedRows: '1200',
      triggers: [
        {
          deviceId: 'dev-101',
          metricCode: 'MDC_ECG_HEART_RATE',
          triggeredAt: '2026-09-25T08:12:00+08:00',
          triggerValue: '168',
          alarmLevel: 'CRITICAL',
        },
      ],
    });
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '模拟回放');
    await flushPromises();
    await wrapper.find('input[aria-label="回放起始"]').setValue('2026-09-25T00:00');
    await wrapper.find('input[aria-label="回放截止"]').setValue('2026-09-25T23:59');
    await clickButton(wrapper, '开始回放');
    await flushPromises();
    expect(alarmRules.simulate).toHaveBeenCalledWith('rule-2', {
      from: '2026-09-25T00:00',
      to: '2026-09-25T23:59',
    });
    // 回放结果弹窗展示扫描行数与命中触发清单
    const text = wrapper.text();
    expect(text).toContain('1200');
    expect(text).toContain('dev-101');
    expect(text).toContain('168');
  });

  it('活跃告警等级徽标渲染并确认出网（acknowledge 携告警号）', async () => {
    vi.mocked(alarms.list).mockResolvedValue({
      content: [
        alarmMock({ alarmNo: 'ALM-20260926-0001', status: 'ACTIVE', alarmLevel: 'CRITICAL' }),
        alarmMock({
          id: 'a-2',
          alarmNo: 'ALM-20260926-0002',
          status: 'ACKNOWLEDGED',
          alarmLevel: 'WARNING',
        }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    vi.mocked(alarms.acknowledge).mockResolvedValue(alarmMock({ status: 'ACKNOWLEDGED' }));
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    // 等级徽标状态类契约（机器判据）
    expect(wrapper.find('.fuy-alarm-tag--critical').exists()).toBe(true);
    expect(wrapper.find('.fuy-alarm-tag--warning').exists()).toBe(true);
    // 确认操作出网并刷新告警列表
    await clickRowButton(wrapper, 'ALM-20260926-0001', '确认');
    await flushPromises();
    expect(alarms.acknowledge).toHaveBeenCalledWith('ALM-20260926-0001');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(alarms.list).toHaveBeenCalledTimes(2);
  });

  it('关闭告警缺原因零出网拦截，携原因出网并刷新', async () => {
    vi.mocked(alarms.list).mockResolvedValue({
      content: [alarmMock({ alarmNo: 'ALM-20260926-0001', status: 'ACTIVE' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(alarms.close).mockResolvedValue(alarmMock({ status: 'CLOSED' }));
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, 'ALM-20260926-0001', '关闭');
    await flushPromises();
    // 空原因确认 → 零出网拦截
    await clickButton(wrapper, '确认关闭');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写关闭原因');
    expect(alarms.close).not.toHaveBeenCalled();
    // 补原因后出网
    await wrapper.find('textarea[aria-label="关闭原因"]').setValue('误报，设备已重新标定');
    await clickButton(wrapper, '确认关闭');
    await flushPromises();
    expect(alarms.close).toHaveBeenCalledWith('ALM-20260926-0001', {
      reason: '误报，设备已重新标定',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(alarms.list).toHaveBeenCalledTimes(2);
  });
});
