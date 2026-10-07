// 告警规则页单测（/iot/alarm-rules，M14 FU-M14-08 告警引擎前端面）：规则列表加载与三类
// 源徽标渲染（fuy-rule-tag--{type} 机器判据）、THRESHOLD 规则缺持续时长/恢复带零出网
// 显式校验、THRESHOLD 完整提交出网携阈值参数并刷新、模拟回放动态近一日默认窗出网并在
// 弹窗展示扫描行数与触发清单、活跃告警等级徽标渲染与确认出网、关闭缺原因零出网拦截与
// 携原因出网（后端 casClose 实况允许 ACTIVE/ACKNOWLEDGED 关闭）、EX-46/FE-A2-08 编辑
// 保存版本比对（远端 updatedAt 偏离打开基线=他人已改 → 冲突提示：取消零出网/确认覆盖）。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
// 桩面：版本冲突确认走 ElMessageBox.confirm 替身（默认确认放行，用例按需 mockRejectedValueOnce 覆写）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { alarmRules, alarms } from '@/api/iot';
import type { AlarmRuleVO, AlarmVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
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

// 仅替身 ElMessage/ElMessageBox（提示与版本冲突确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
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
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #32）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u5',
          loginName: 'iotadmindemo',
          displayName: '陆物联',
          orgId: null,
          roles: ['iot_admin'],
          permissions: ['iot:alarm-rule:btn:manage'],
        },
      }),
    );
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
    // 确认框替身调用记录清零（实现默认确认放行，单用例按需 mockRejectedValueOnce 覆写）
    vi.mocked(ElMessageBox.confirm).mockClear();
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

  it('模拟回放默认动态近一日窗口提交出网并展示扫描行数与触发清单', async () => {
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
    // 默认窗已预填（动态近一日，不落固定日期字面量）——零改动直接开始回放
    const fromInput = wrapper.find('input[aria-label="回放起始"]').element as HTMLInputElement;
    expect(fromInput.value).not.toBe('');
    await clickButton(wrapper, '开始回放');
    await flushPromises();
    // 出网载荷为合法近一日窗（from<to、跨度恰 24h、截止≈当前时刻——按业务结果断言不绑定字面量）
    expect(alarmRules.simulate).toHaveBeenCalledTimes(1);
    const [ruleId, payload] = vi.mocked(alarmRules.simulate).mock.calls[0];
    expect(ruleId).toBe('rule-2');
    const fromMs = new Date(payload.from).getTime();
    const toMs = new Date(payload.to).getTime();
    expect(toMs - fromMs).toBe(24 * 3600 * 1000);
    expect(Date.now() - toMs).toBeLessThan(60_000);
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

  it('编辑规则保存时远端已被他人修改：冲突提示后取消零出网（EX-46/FE-A2-08）', async () => {
    // 初载（T1 基线）→ 保存时重拉（T2=他人已改）
    vi.mocked(alarmRules.list)
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-25T10:00:00+08:00' })])
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-26T08:00:00+08:00' })]);
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '编辑');
    // 表单已预填 THRESHOLD 四必填项，直接保存触发版本比对：远端偏离基线 → 冲突确认
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('已被他人修改'),
      '版本冲突提醒',
      expect.anything(),
    );
    // 取消：零出网，表单驻留可复核
    expect(alarmRules.update).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('编辑规则保存时远端已被他人修改：确认后覆盖出网（EX-46/FE-A2-08）', async () => {
    vi.mocked(alarmRules.list)
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-25T10:00:00+08:00' })])
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-26T08:00:00+08:00' })]);
    vi.mocked(alarmRules.update).mockResolvedValue(ruleMock());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '编辑');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    // 冲突确认（替身默认放行）恰一次后按操作者意图覆盖出网
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(alarmRules.update).toHaveBeenCalledTimes(1);
    expect(alarmRules.update).toHaveBeenCalledWith(
      'rule-2',
      expect.objectContaining({ ruleName: '心率超阈告警' }),
    );
    wrapper.unmount();
  });

  it('编辑规则保存时远端未变更：零冲突提示直接出网（EX-46/FE-A2-08）', async () => {
    // 初载与保存时重拉的 updatedAt 一致（T1=T1）→ 无冲突零打扰
    vi.mocked(alarmRules.list)
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-25T10:00:00+08:00' })])
      .mockResolvedValueOnce([ruleMock({ id: 'rule-2', updatedAt: '2026-09-25T10:00:00+08:00' })]);
    vi.mocked(alarmRules.update).mockResolvedValue(ruleMock());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '编辑');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(alarmRules.update).toHaveBeenCalledWith(
      'rule-2',
      expect.objectContaining({ ruleName: '心率超阈告警' }),
    );
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(AlarmRuleView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 iot:alarm-rule:btn:manage 时规则/告警写面与回放执行口全隐藏（D-34）', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(alarms.list).mockResolvedValue({
      content: [alarmMock({ status: 'ACTIVE' })],
      page: '0',
      size: '20',
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 规则行编辑/删除与告警行确认/关闭四个写口无码全隐藏（D-34 无码 DOM 移除）
    expect(buttonTexts).not.toContain('编辑');
    expect(buttonTexts).not.toContain('删除');
    expect(buttonTexts).not.toContain('确认');
    expect(buttonTexts).not.toContain('关闭');
    // 回放执行口（弹窗内出网按钮）无码隐藏：入口「模拟回放」可开窗但不可出网
    await clickRowButton(wrapper, '心率超阈告警', '模拟回放');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('开始回放');
    // 列表读面不受元素码影响（规则名/告警号仍渲染）
    expect(wrapper.text()).toContain('心率超阈告警');
    expect(wrapper.text()).toContain('ALM-20260926-0001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:alarm-rule:btn:manage 时规则/告警写面与回放执行口可见', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(alarms.list).mockResolvedValue({
      content: [alarmMock({ status: 'ACTIVE' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('编辑');
    expect(buttonTexts).toContain('删除');
    expect(buttonTexts).toContain('确认');
    expect(buttonTexts).toContain('关闭');
    await clickRowButton(wrapper, '心率超阈告警', '模拟回放');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('开始回放');
    wrapper.unmount();
  });
});
