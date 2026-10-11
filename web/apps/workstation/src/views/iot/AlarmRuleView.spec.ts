// 告警规则页单测（/iot/alarm-rules，M14 FU-M14-08 告警引擎前端面 · 暖纸卷宗重排=丙-4 蓝图）：
// 【丙-4 spec 重写锚点】①两分区结构断言（.fuy-sign-divider 前=触发口径段：规则名称/类型/
//   设备 + 三口径分叉字段（既有 v-if 保留）；后=通知口径段：等级/升级时限/启用开关）；
// ②活跃告警计数胶囊（前端自 alarmRows 过滤 ACTIVE 派生零出网，确认闭环后随刷新派生更新）；
// ③EX-46/FE-A2-08 版本冲突确认弹窗文案逐字冻结断言（整串精确匹配）；
// ④三口径分叉必填校验文案保留（THRESHOLD 持续时长/恢复带 + OFFLINE 离线时长，零出网拦截）；
// ⑤钤印族（保存=已保存/确认=已确认/关闭=已关闭，v-if 挂载制+is-show 静态类）+ 等级徽标
//   CRITICAL 印泥朱实底断言（源码级：实底背景+反白字走语义 token 通道零新 hex）。
// 【既有业务断言全数保留】规则列表三类源徽标（fuy-rule-tag--{type} 契约类）+ 触发口径摘要
// 列、THRESHOLD 完整提交出网携阈值参数并刷新、模拟回放动态近一日默认窗出网并展示扫描行数
// 与触发清单、活跃告警等级徽标渲染与确认出网、关闭缺原因零出网拦截与携原因出网（后端
// casClose 实况允许 ACTIVE/ACKNOWLEDGED 关闭）、PR-4F v-perm 写面把守位、删除 ElMessageBox
// 中档确认（丙-4.4：删除=ElMessageBox 保留，成功反馈维持既有 message 通道）。
// 【换脸重写留痕】原 ElMessage.success 成功反馈断言（已创建/已更新/已确认/已关闭）随钤印族
// 换脸同批重写为 .fuy-stamp-toast 断言（G2 基线：成功=钤印动词结果，失败/校验仍走 message
// 批注条，钤印永不承失败）；原页头 h2+hint 断言随门牌页首收编删除（G1），hint 原文「活跃或
// 已确认均可关」语义落告警卡卡头副注不丢。门牌页首/卷宗卡/空态脸/.fuy-dialog 挂类为 G1/G2/G6
// 基线新增断言。api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定
// 实现细节。桩面：版本冲突/删除确认走 ElMessageBox.confirm 替身（默认确认放行，单用例按需
// mockRejectedValueOnce 覆写）。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
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

// 仅替身 ElMessage/ElMessageBox（校验与删除确认断言用；成功反馈归钤印），其余导出原样保留供组件解析
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

/** 告警行（活跃/已确认/已关闭三态可覆写） */
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

/** 定位规则表单卡内「签」分界并取分界前/后字段集文本（两分区结构断言的取材口） */
function splitRuleFormBySignDivider(wrapper: VueWrapper): { upper: string; lower: string } {
  const form = wrapper.find('.rule-form-card form');
  expect(form.exists()).toBe(true);
  const divider = form.find('.fuy-sign-divider');
  expect(divider.exists()).toBe(true);
  const children = Array.from(form.element.children);
  const dividerIndex = children.indexOf(divider.element);
  expect(dividerIndex).toBeGreaterThan(-1);
  return {
    upper: children
      .slice(0, dividerIndex)
      .map((el) => el.textContent ?? '')
      .join('\n'),
    lower: children
      .slice(dividerIndex + 1)
      .map((el) => el.textContent ?? '')
      .join('\n'),
  };
}

describe('告警规则页', () => {
  /** 文件级 Pinia：门牌批注读 auth 会话 store + v-perm 指令元素权限判定，每用例新实例防串扰 */
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

  it('门牌页首与页级骨架锚点：根挂 fuy-page 零 stagger、flow 容器承接、衬线标题+英文小注+批注行+页脚批注+刷新联动两列表', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    // 契约 ⑦.4：页面根节点禁挂 .fuy-stagger——stagger 挂页内 flow 容器（product-flow 同构）
    const root = wrapper.find('.fuy-page.alarm-rule');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.fuy-stagger.alarm-flow').exists()).toBe(true);
    // 门牌三锚：衬线标题 + 英文小注（装饰层）+ 批注行（蓝图丙-4.2 批注文案逐字 + 谁·时刻）
    expect(wrapper.find('.fuy-page-title').text()).toBe('告警规则');
    expect(wrapper.find('.fuy-page-en').text()).toBe('ALARM RULES');
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('三类规则源 · 模拟回放验证后再上线 · 告警闭环：确认/关闭');
    expect(note).toContain('签认人 陆物联');
    // 页脚批注（G1 纵向序收尾）：数据时点 + 手动刷新取数语义
    expect(wrapper.find('.fuy-page-foot').text()).toContain('数据时点');
    expect(wrapper.find('.fuy-page-foot').text()).toContain('手动刷新取数');
    // 门牌刷新联动（蓝图丙-4.2 状态位刷新）：规则与告警两列表一并重取
    await clickButton(wrapper, '刷新');
    await flushPromises();
    expect(alarmRules.list).toHaveBeenCalledTimes(2);
    expect(alarms.list).toHaveBeenCalledTimes(2);
  });

  it('活跃告警计数胶囊（锚点②）：初载骨架态→内容态，自 alarmRows 过滤 ACTIVE 派生零出网', async () => {
    vi.mocked(alarms.list).mockResolvedValue({
      content: [
        alarmMock({ alarmNo: 'ALM-20260926-0001', status: 'ACTIVE' }),
        alarmMock({ id: 'a-2', alarmNo: 'ALM-20260926-0002', status: 'ACTIVE' }),
        alarmMock({ id: 'a-3', alarmNo: 'ALM-20260926-0003', status: 'ACKNOWLEDGED' }),
        alarmMock({ id: 'a-4', alarmNo: 'ALM-20260926-0004', status: 'CLOSED' }),
      ],
      page: '0',
      size: '20',
      total: '4',
    });
    const wrapper = mount(AlarmRuleView);
    // 挂载即骨架态（首拉未落地，禁伪 0 值）：静态金洗纸占位 + 零内容胶囊
    expect(wrapper.find('.fuy-page-status .alarm-count-sk').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').exists()).toBe(false);
    await flushPromises();
    // 内容态：印泥朱胶囊（danger 变体）+ ACTIVE 过滤派生计数（4 行中 2 活跃）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.classes()).toContain('fuy-status-pill--danger');
    expect(pill.text()).toBe('活跃告警 2');
    // 派生零出网：计数自已加载行集过滤，出网仍只 mount 时一次
    expect(alarms.list).toHaveBeenCalledTimes(1);
  });

  it('规则列表渲染三类源徽标与触发口径摘要列（fuy-rule-tag--{type} 契约类保留）', async () => {
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
    // 触发口径摘要列（蓝图丙-4.2：左栏列表卡保留摘要列）
    expect(text).toContain('MDC_ECG_HEART_RATE > 150，持续 60s');
    expect(text).toContain('离线 300s 触发');
    // 规则列表卡=卷宗卡（fuy-card）密排（G1 卡墙形态）
    expect(wrapper.find('.rule-list-card.fuy-card.fuy-dense').exists()).toBe(true);
  });

  it('两分区结构断言（锚点①）：sign-divider 前=触发口径段字段集，后=通知口径段字段集', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    const form = wrapper.find('.rule-form-card form');
    // 恰一道「签」分界（PM 重排裁决：触发口径/通知口径两分区，唯一文书分界）
    expect(form.findAll('.fuy-sign-divider')).toHaveLength(1);
    const { upper, lower } = splitRuleFormBySignDivider(wrapper);
    // 上段（触发口径）：名称/类型/设备 + THRESHOLD 三口径分叉字段（既有 v-if 保留）
    expect(upper).toContain('触发口径');
    expect(upper).toContain('规则名称');
    expect(upper).toContain('规则类型');
    expect(upper).toContain('设备 ID');
    expect(upper).toContain('指标编码');
    expect(upper).toContain('比较符');
    expect(upper).toContain('阈值');
    expect(upper).toContain('持续时长');
    expect(upper).toContain('恢复带');
    expect(upper).toContain('静默窗口');
    // 下段（通知口径）：等级/升级时限/启用开关/保存
    expect(lower).toContain('通知口径');
    expect(lower).toContain('告警等级');
    expect(lower).toContain('升级时限');
    expect(lower).toContain('启用');
    expect(lower).toContain('保存规则');
    // 分叉字段不越界：口径字段全部留在分界上段
    expect(lower).not.toContain('阈值');
    expect(lower).not.toContain('指标编码');
    expect(lower).not.toContain('比较符');
    expect(lower).not.toContain('持续时长');
  });

  it('新建 THRESHOLD 规则缺持续时长/恢复带零出网显式校验拦截（校验拦截零钤印——钤印只承成功）', async () => {
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
    // 业务拒绝通道零钤印（G2：钤印永不承失败/校验拦截）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
  });

  it('OFFLINE 规则缺离线时长零出网拦截（锚点④三口径文案保留）+ 离线时长字段居分界上段', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await wrapper.find('input[aria-label="规则名称"]').setValue('设备离线告警');
    await wrapper.find('select[aria-label="规则类型"]').setValue('OFFLINE');
    await flushPromises();
    // 切口径后分叉字段仍在「签」分界上段（两分区结构随口径切换稳定）
    const { upper } = splitRuleFormBySignDivider(wrapper);
    expect(upper).toContain('离线时长');
    const { lower } = splitRuleFormBySignDivider(wrapper);
    expect(lower).not.toContain('离线时长');
    // 离线时长留空提交 → 零出网拦截（OFFLINE 必填项显式校验）
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写离线时长（秒）');
    expect(alarmRules.create).not.toHaveBeenCalled();
  });

  it('新建 THRESHOLD 规则完整提交出网携阈值参数并刷新规则列表，成功反馈=钤印「已保存」', async () => {
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
    // 成功反馈=钤印「已保存」（蓝图丙-4.4 逐字；原 ElMessage.success('告警规则已创建')
    // 断言随钤印换脸同批重写留痕——v-if 挂载制 + is-show 静态类 + aria-live）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已保存');
    expect(stamp.attributes('aria-live')).toBe('polite');
    // 保存成功后表单清空（新建态复位）
    const nameInput = wrapper.find('input[aria-label="规则名称"]').element as HTMLInputElement;
    expect(nameInput.value).toBe('');
    // 规则列表重载（list 第二次调用）
    expect(alarmRules.list).toHaveBeenCalledTimes(2);
  });

  it('模拟回放默认动态近一日窗口提交出网并展示扫描行数与触发清单，弹窗挂 fuy-dialog 与 fuy-form', async () => {
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
    // 回放弹窗挂 .fuy-dialog（G2）+ 弹窗内字段容器挂 .fuy-form（表单脸）
    const dialog = wrapper.findAll('.fuy-dialog').find((d) => d.text().includes('模拟回放'));
    expect(dialog).toBeDefined();
    expect(dialog?.find('.fuy-form').exists()).toBe(true);
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

  it('模拟回放零命中：弹窗内空态脸（G6 暂无语法）+ 扫描行数照常回显', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue(threeTypeRules());
    vi.mocked(alarmRules.simulate).mockResolvedValue({ scannedRows: '0', triggers: [] });
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '模拟回放');
    await flushPromises();
    await clickButton(wrapper, '开始回放');
    await flushPromises();
    const dialog = wrapper.findAll('.fuy-dialog').find((d) => d.text().includes('模拟回放'));
    expect(dialog).toBeDefined();
    // 零命中走空态脸（默认纸箱插画在 .fuy-page 子树由全局段压制）：主句保原文案语义 + 给下一步
    const empty = dialog?.find('.fuy-empty');
    expect(empty?.exists()).toBe(true);
    expect(empty?.text()).toContain('回放窗口内未命中触发');
    expect(empty?.text()).toContain('重新');
  });

  it('活跃告警等级徽标渲染 + CRITICAL 印泥朱实底强状态（锚点⑤源码级：实底背景+反白字语义通道）', async () => {
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
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    // 等级徽标状态类契约（机器判据）
    expect(wrapper.find('.fuy-alarm-tag--critical').exists()).toBe(true);
    expect(wrapper.find('.fuy-alarm-tag--warning').exists()).toBe(true);
    // CRITICAL=印泥朱实底强状态（蓝图丙-4.2③）：源码级断言实底背景+反白字，
    // 取值全走语义 token 通道（--fuy-color-danger-text / 最亮纸白），零新 hex 字面量
    const source = readFileSync(join(import.meta.dirname, 'AlarmRuleView.vue'), 'utf-8');
    const criticalBlock = source.match(/\.fuy-alarm-tag--critical\s*\{[^}]*\}/);
    expect(criticalBlock).not.toBeNull();
    expect(criticalBlock?.[0]).toContain('background: var(--fuy-color-danger-text)');
    expect(criticalBlock?.[0]).toContain('color: var(--el-fill-color-blank)');
    // WARNING 维持描边文本形态（双红分工：仅 CRITICAL 升实底强状态）
    const warningBlock = source.match(/\.fuy-alarm-tag--warning\s*\{[^}]*\}/);
    expect(warningBlock?.[0]).toContain('border-color: var(--fuy-color-warning-text)');
    expect(warningBlock?.[0]).not.toContain('background');
  });

  it('确认告警出网携告警号 + 钤印「已确认」+ 计数胶囊随刷新派生更新', async () => {
    vi.mocked(alarms.list)
      .mockResolvedValueOnce({
        content: [
          alarmMock({ alarmNo: 'ALM-20260926-0001', status: 'ACTIVE', alarmLevel: 'CRITICAL' }),
          alarmMock({ id: 'a-2', alarmNo: 'ALM-20260926-0002', status: 'ACTIVE' }),
          alarmMock({ id: 'a-3', alarmNo: 'ALM-20260926-0003', status: 'ACKNOWLEDGED' }),
        ],
        page: '0',
        size: '20',
        total: '3',
      })
      // 确认后重拉：ALM-0001 转已确认 → 活跃计数派生降为 1
      .mockResolvedValueOnce({
        content: [
          alarmMock({ alarmNo: 'ALM-20260926-0001', status: 'ACKNOWLEDGED' }),
          alarmMock({ id: 'a-2', alarmNo: 'ALM-20260926-0002', status: 'ACTIVE' }),
          alarmMock({ id: 'a-3', alarmNo: 'ALM-20260926-0003', status: 'ACKNOWLEDGED' }),
        ],
        page: '0',
        size: '20',
        total: '3',
      });
    vi.mocked(alarms.acknowledge).mockResolvedValue(alarmMock({ status: 'ACKNOWLEDGED' }));
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').text()).toBe('活跃告警 2');
    // 确认操作出网并刷新告警列表
    await clickRowButton(wrapper, 'ALM-20260926-0001', '确认');
    await flushPromises();
    expect(alarms.acknowledge).toHaveBeenCalledWith('ALM-20260926-0001');
    // 成功反馈=钤印「已确认」（蓝图丙-4.4 逐字；原 ElMessage.success('告警已确认：…') 断言随
    // 钤印换脸同批重写留痕）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已确认');
    expect(alarms.list).toHaveBeenCalledTimes(2);
    // 计数胶囊随重拉派生更新（前端过滤派生而非快照）
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').text()).toBe('活跃告警 1');
  });

  it('关闭告警缺原因零出网拦截，携原因出网+钤印「已关闭」，关闭弹窗挂 fuy-dialog 与 fuy-form', async () => {
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
    // 关闭弹窗挂 .fuy-dialog（G2）+ 弹窗内表单容器挂 .fuy-form
    const dialog = wrapper.findAll('.fuy-dialog').find((d) => d.text().includes('告警关闭'));
    expect(dialog).toBeDefined();
    expect(dialog?.find('.fuy-form').exists()).toBe(true);
    // 空原因确认 → 零出网拦截（原因强制，业务拒绝零钤印）
    await clickButton(wrapper, '确认关闭');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写关闭原因');
    expect(alarms.close).not.toHaveBeenCalled();
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    // 补原因后出网
    await wrapper.find('textarea[aria-label="关闭原因"]').setValue('误报，设备已重新标定');
    await clickButton(wrapper, '确认关闭');
    await flushPromises();
    expect(alarms.close).toHaveBeenCalledWith('ALM-20260926-0001', {
      reason: '误报，设备已重新标定',
    });
    // 成功反馈=钤印「已关闭」（蓝图丙-4.4 逐字；原 ElMessage.success('告警已关闭：…') 断言随
    // 钤印换脸同批重写留痕）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已关闭');
    expect(alarms.list).toHaveBeenCalledTimes(2);
    // 弹窗收场由 closeVisible 置假承载；EP leave 过渡在 jsdom 永不终结（transitionend 不触发），
    // 断言过渡期 DOM 移除即绑定 EP 过渡实现细节——不再断言（业务收场以上述关窗+重拉为据）
  });

  it('编辑规则保存时远端已被他人修改：冲突弹窗文案逐字冻结 + 取消零出网（锚点③，EX-46/FE-A2-08）', async () => {
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
    // 冲突确认文案逐字冻结（蓝图丙-4.4：版本冲突确认弹窗文案逐字冻结——整串精确匹配，
    // 含规则名回显/远端时点/覆盖警示三段，防文案无感漂移）
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '规则「心率超阈告警」已被他人修改（远端更新于 2026-09-26T08:00:00+08:00），继续保存将覆盖他人变更',
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
    // 覆盖保存成功同为钤印「已保存」
    expect(wrapper.find('.fuy-stamp-toast.is-show').text()).toBe('已保存');
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

  it('规则删除维持 ElMessageBox 中档确认（丙-4.4：删除=ElMessageBox 保留）+ 出网刷新', async () => {
    vi.mocked(alarmRules.list).mockResolvedValue([ruleMock()]);
    // remove 真实契约为 Promise<void>（204 无返回体），桩面仅承载守卫后的 resolve 分支
    vi.mocked(alarmRules.remove).mockResolvedValue(undefined);
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '心率超阈告警', '删除');
    await flushPromises();
    // 中档确认：确认框带规则名回显与不可恢复警示（文案既有保留）
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('即将删除规则「心率超阈告警」'),
      '规则删除确认',
      expect.anything(),
    );
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('删除后不可恢复'),
      '规则删除确认',
      expect.anything(),
    );
    // 确认后出网删除并刷新列表（删除成功反馈维持既有 message 通道——钤印族不含「已删除」）
    expect(alarmRules.remove).toHaveBeenCalledWith('rule-2');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(alarmRules.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('初始空载：零钤印 DOM（v-if 挂载制反向断言）+ 双列表空态脸（G6 暂无语法）', async () => {
    const wrapper = mount(AlarmRuleView);
    await flushPromises();
    // 钤印挂载制：无成功动作时零钤印 DOM（防常驻空朱块泄漏）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    // 空态脸（默认纸箱插画在 .fuy-page 子树由全局段压制）：主句「暂无〈业务客体〉」+ 说明给下一步
    const empties = wrapper.findAll('.fuy-empty');
    expect(empties).toHaveLength(2);
    expect(empties[0].find('.fuy-empty-title').text()).toBe('暂无告警规则');
    expect(empties[0].text()).toContain('新建');
    expect(empties[1].find('.fuy-empty-title').text()).toBe('暂无告警');
    // 告警空载时活跃计数胶囊为诚实 0（已加载非伪数据）
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').text()).toBe('活跃告警 0');
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
