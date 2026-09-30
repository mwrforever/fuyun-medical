// 联动规则页单测（/iot/linkage-rules，M16 联动规则 CRUD + 执行日志重试面前端面）：规则列表
// 加载与触发源/动作类型词表徽标（fuy-rule-tag--{source} 机器判据）、新建触发条件 JSON 非法
// 零出网、新建合法出网携词表值并刷新、编辑预填回显并出网 update、删除出网并刷新（含删除在途
// 守卫双击仅一次出网）、执行日志渲染结果三态徽标（fuy-linkage-tag--{result}）与 FAILED 行
// 重试出网并刷新（含重试在途守卫双击仅一次出网——防重复投递联动动作）、EX-46/FE-A2-05
// 删除前确认框（取消零出网/确认出网）、EX-46/FE-A2-06 弹窗未保存草稿守卫（脏表单关闭需
// 确认丢弃，无改动零确认）。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
// 桩面：删除/守卫确认走 ElMessageBox.confirm 替身（默认确认放行，既有删除链业务断言零改动）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
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

// 仅替身 ElMessage/ElMessageBox（提示与删除/未保存守卫确认断言用），其余导出原样保留
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
    // 确认框替身调用记录清零（实现默认确认放行，单用例按需 mockRejectedValueOnce 覆写）
    vi.mocked(ElMessageBox.confirm).mockClear();
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

  it('删除在途守卫：双击窗口内重复点击仅一次出网（防重复提交删除）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.remove).mockResolvedValue(undefined);
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    const deleteButton = findRow(wrapper, '危急告警转呼叫')
      ?.findAll('button')
      .find((b) => b.text() === '删除');
    expect(deleteButton).toBeDefined();
    // 双击（第二次点击落在首次出网在途窗口内，未 flush）
    await deleteButton?.trigger('click');
    await deleteButton?.trigger('click');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledTimes(1);
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
  });

  it('重试在途守卫：双击窗口内重复点击仅一次出网（防重复投递联动动作与 retryCount 双计）', async () => {
    vi.mocked(linkageLogs.page).mockResolvedValue({
      content: [logMock({ actionResult: 'FAILED' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(linkageLogs.retry).mockResolvedValue(logMock({ actionResult: 'SUCCESS' }));
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    const retryButton = findRow(wrapper, 'LK20260926001')
      ?.findAll('button')
      .find((b) => b.text() === '重试');
    expect(retryButton).toBeDefined();
    // 双击（第二次点击落在首次重投在途窗口内，未 flush）
    await retryButton?.trigger('click');
    await retryButton?.trigger('click');
    await flushPromises();
    expect(linkageLogs.retry).toHaveBeenCalledTimes(1);
    expect(linkageLogs.retry).toHaveBeenCalledWith('LK20260926001');
  });

  it('规则删除前确认：取消零出网，确认后出网并刷新（EX-46/FE-A2-05）', async () => {
    vi.mocked(linkageRules.list).mockResolvedValue([ruleMock()]);
    vi.mocked(linkageRules.remove).mockResolvedValue(undefined);
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    // 取消：确认框带规则名回显摘要，零出网
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('危急告警转呼叫'),
      '规则删除确认',
      expect.anything(),
    );
    expect(linkageRules.remove).not.toHaveBeenCalled();
    // 确认：出网删除并刷新列表（取消后守卫复位可再次发起）
    await clickRowButton(wrapper, '危急告警转呼叫', '删除');
    await flushPromises();
    expect(linkageRules.remove).toHaveBeenCalledWith('701');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(linkageRules.list).toHaveBeenCalledTimes(2);
  });

  it('规则弹窗未保存草稿守卫：脏表单取消/X 关闭需确认丢弃，拒绝则弹窗驻留（EX-46/FE-A2-06）', async () => {
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    await wrapper.find('input[aria-label="规则名称"]').setValue('新规则');
    // 取消按钮关闭：脏表单先确认丢弃；拒绝 → 弹窗驻留草稿
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('未保存'),
      '未保存提醒',
      expect.anything(),
    );
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(true);
    // 右上角 X 关闭走同款守卫：拒绝丢弃 → 弹窗驻留
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await wrapper.find('.el-dialog__headerbtn').trigger('click');
    await flushPromises();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(true);
    // 再次取消并确认丢弃 → 弹窗关闭
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(false);
  });

  it('规则弹窗无改动时取消关闭零确认（EX-46/FE-A2-06）', async () => {
    const wrapper = mount(LinkageRuleView);
    await flushPromises();
    await clickButton(wrapper, '新建规则');
    // 未做任何录入：直接关闭零打扰（不弹未保存确认）
    await clickButton(wrapper, '取消');
    await flushPromises();
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(wrapper.find('input[aria-label="规则名称"]').isVisible()).toBe(false);
  });
});
