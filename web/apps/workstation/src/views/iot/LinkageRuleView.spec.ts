// 联动规则页单测（/iot/linkage-rules，M16 联动规则 CRUD + 执行日志重试面前端面）：规则列表
// 加载与触发源/动作类型词表徽标（fuy-rule-tag--{source} 机器判据）、新建触发条件 JSON 非法
// 零出网、新建合法出网携词表值并刷新、编辑预填回显并出网 update、删除出网并刷新、执行日志
// 渲染结果三态徽标（fuy-linkage-tag--{result}）与 FAILED 行重试出网并刷新。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { linkageLogs, linkageRules } from '@/api/iot';
import type { LinkageLogVO, LinkageRuleVO } from '@/api/iot';
import LinkageRuleView from './LinkageRuleView.vue';

vi.mock('@/api/iot', () => ({
  TRIGGER_SOURCE_LABELS: {
    ALARM_TRIGGERED: '告警触发',
    TELEMETRY_ANOMALY: '遥测异常',
    DEVICE_STATUS: '设备状态',
  },
  ACTION_TYPE_LABELS: {
    NOTIFY: '站内通知',
    M01_NOTIFY: 'M01 通知',
    CALL_TRANSFER: '呼叫转接',
    NURSING_TASK: '护理任务',
    WARD_BROADCAST: '病区播报',
  },
  LINKAGE_RESULT_LABELS: { SUCCESS: '成功', FAILED: '失败', PENDING: '待执行' },
  linkageRules: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  linkageLogs: { page: vi.fn(), retry: vi.fn() },
}));

// ward 域替身（目标病区选项；同时切断 api/ward→http→auth→router 模块链，保持纯单元隔离）
vi.mock('@/api/ward', () => ({
  WARD_OPTIONS: [{ code: '1001', label: '1001 演示病区' }],
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

/** 规则行（可覆写词表值/启用态） */
function ruleMock(partial: Partial<LinkageRuleVO> = {}): LinkageRuleVO {
  return {
    id: '701',
    ruleName: '危急告警转呼叫',
    triggerSource: 'ALARM_TRIGGERED',
    triggerCondition: { level: 'CRITICAL' },
    actionType: 'CALL_TRANSFER',
    actionConfig: { priority: 'high' },
    targetWardId: '1001',
    enabled: true,
    createdAt: '2026-09-25T10:00:00+08:00',
    updatedAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 执行日志行（可覆写结果态） */
function logMock(partial: Partial<LinkageLogVO> = {}): LinkageLogVO {
  return {
    id: '601',
    linkageNo: 'LK20260926001',
    ruleId: '701',
    triggerSource: 'ALARM_TRIGGERED',
    triggerRef: 'AL20260926001',
    actionType: 'CALL_TRANSFER',
    actionResult: 'FAILED',
    retryCount: 1,
    errorMsg: '目标病区路由缺失',
    executedAt: '2026-09-26T10:00:00+08:00',
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 空日志分页出参 */
function emptyLogPage() {
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

describe('联动规则页', () => {
  beforeEach(() => {
    for (const fn of [
      linkageRules.list,
      linkageRules.create,
      linkageRules.update,
      linkageRules.remove,
      linkageLogs.page,
      linkageLogs.retry,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(linkageRules.list).mockResolvedValue([]);
    vi.mocked(linkageLogs.page).mockResolvedValue(emptyLogPage());
  });

  it('规则列表加载渲染触发源/动作类型词表徽标与启用态（机器判据）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([
      ruleMock({ id: '701', enabled: true }),
      ruleMock({
        id: '702',
        ruleName: '遥测异常护理任务',
        triggerSource: 'TELEMETRY_ANOMALY',
        actionType: 'NURSING_TASK',
        enabled: false,
      }),
    ]);
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('危急告警转呼叫');
    expect(text).toContain('告警触发');
    expect(text).toContain('呼叫转接');
    expect(text).toContain('遥测异常');
    expect(text).toContain('护理任务');
    // 触发源徽标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-rule-tag--alarm_triggered').exists()).toBe(true);
    expect(wrapper.find('.fuy-rule-tag--telemetry_anomaly').exists()).toBe(true);
  });

  it('规则新建触发条件 JSON 非法零出网显式校验拦截', async () => {
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('新规则');
    await wrapper.find('textarea[aria-label="触发条件 JSON"]').setValue('{level:');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '触发条件须为合法 JSON 对象，请修正后再试',
    );
    expect(linkageRules.create).not.toHaveBeenCalled();
  });

  it('规则新建合法出网携词表值与条件对象并刷新列表', async () => {
    vi.mocked(linkageRules.create).mockResolvedValue(ruleMock());
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('危急告警播报');
    await wrapper.find('select[aria-label="触发源"]').setValue('DEVICE_STATUS');
    await wrapper.find('textarea[aria-label="触发条件 JSON"]').setValue('{"status":"OFFLINE"}');
    await wrapper.find('select[aria-label="动作类型"]').setValue('WARD_BROADCAST');
    await wrapper.find('select[aria-label="目标病区"]').setValue('1001');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(linkageRules.create).toHaveBeenCalledWith({
      ruleName: '危急告警播报',
      triggerSource: 'DEVICE_STATUS',
      triggerCondition: { status: 'OFFLINE' },
      actionType: 'WARD_BROADCAST',
      actionConfig: undefined,
      targetWardId: '1001',
      enabled: true,
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
  });

  it('规则编辑预填回显原值并出网整单替换（update）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.update).mockResolvedValue(ruleMock());
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '危急告警转呼叫', '编辑');
    // 预填回显（编辑框承载原规则值）
    const nameInput = wrapper.find('input[aria-label="规则名称"]').element as HTMLInputElement;
    expect(nameInput.value).toBe('危急告警转呼叫');
    await wrapper.find('input[aria-label="规则名称"]').setValue('危急告警转护理任务');
    await wrapper.find('select[aria-label="动作类型"]').setValue('NURSING_TASK');
    await clickButton(wrapper, '保存规则');
    await flushPromises();
    expect(linkageRules.update).toHaveBeenCalledWith('701', {
      ruleName: '危急告警转护理任务',
      triggerSource: 'ALARM_TRIGGERED',
      triggerCondition: { level: 'CRITICAL' },
      actionType: 'NURSING_TASK',
      actionConfig: { priority: 'high' },
      targetWardId: '1001',
      enabled: true,
    });
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
  });

  it('规则删除出网并刷新列表', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
  });

  it('执行日志渲染结果三态徽标，FAILED 行重试出网携 linkageNo 并刷新日志', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [
        logMock({ actionResult: 'FAILED' }),
        logMock({ linkageNo: 'LK20260926002', actionResult: 'SUCCESS' }),
        logMock({ linkageNo: 'LK20260926003', actionResult: 'PENDING' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    vi.mocked(linkageLogs.retry).mockResolvedValue(logMock({ actionResult: 'SUCCESS' }));
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    // 结果三态徽标状态类契约（机器判据）
    expect(wrapper.find('.fuy-linkage-tag--failed').exists()).toBe(true);
    expect(wrapper.find('.fuy-linkage-tag--success').exists()).toBe(true);
    expect(wrapper.find('.fuy-linkage-tag--pending').exists()).toBe(true);
    await clickRowButton(wrapper, 'LK20260926001', '重试');
    await flushPromises();
    expect(linkageLogs.retry).toHaveBeenCalledWith('LK20260926001');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 日志重载（page 第二次调用）
    expect(linkageLogs.page).toHaveBeenCalledTimes(2);
  });
});
