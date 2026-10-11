// 不良事件 composable 单测（PR-3 Task 14 + 暖纸卷宗换脸切片 甲-3）：既有业务断言全量保留
// （分页筛选参数透传、上报必填校验零出网、上报成功关窗重拉、+08:00 钉面、patientId 死字段、
// 处理/退回操作人留痕与原因必填），新增锚点：今日概览统计带三字段读取（getAdverseEventStats
// 无参当日 + 三态）、待处理计数探针（REPORTED 过滤 size=1 取 total）、上报表单病区下拉
// listOrgs({type:'WARD'}) 三态接线（假常量 WARD_OPTIONS 清零，缺省病区空串）、钤印成功反馈族
// （已上报/已处理/已关闭/已退回——匿名口径不带操作人）。
// api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adverseEvents, getAdverseEventStats } from '@/api/nursing';
import type { AdverseEventStatsVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAdverseEvents } from './useAdverseEvents';

vi.mock('@/api/nursing', () => ({
  adverseEvents: {
    list: vi.fn(),
    report: vi.fn(),
    handle: vi.fn(),
    close: vi.fn(),
    return: vi.fn(),
  },
  getAdverseEventStats: vi.fn(),
  ADVERSE_CATEGORY_OPTIONS: [{ code: 'FALL', label: '跌倒坠床' }],
  SEVERITY_CLASS_OPTIONS: [{ code: 'II', label: 'II 级（重）' }],
  SEVERITY_GRADE_OPTIONS: [{ code: 'A', label: 'A' }],
}));

// 病区清单读端点替身（暖纸切片真数据面，G4 假常量清零）：默认回 V1123 种子镜像病区
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身弹层件（提示与确认断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: '处置记录' }),
    },
  };
});

/** 病区种子（V1123__seed_sys_org 镜像：WARD 演示病区 ACTIVE） */
const WARD_SEED: OrgVO[] = [
  { id: '1123000000000000001', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
];

/** 统计种子（后端 AdverseEventStatsVO 生成物形态：计数 string 承载） */
function statsMock(partial: Partial<AdverseEventStatsVO> = {}): AdverseEventStatsVO {
  return {
    date: '2026-10-11',
    total: '12',
    deadlineTotal: '5',
    deadlineMetCount: '4',
    ...partial,
  };
}

describe('useAdverseEvents', () => {
  beforeEach(() => {
    vi.mocked(adverseEvents.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(adverseEvents.report).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.handle).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.close).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.return).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(getAdverseEventStats).mockReset().mockResolvedValue(statsMock());
    vi.mocked(listOrgs).mockReset().mockResolvedValue(WARD_SEED);
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.prompt).mockClear();
  });

  it('分页列表筛选参数透传（category/status 空串归一 undefined、page 0 基）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.categoryFilter.value = 'FALL';
    await state.search();
    expect(adverseEvents.list).toHaveBeenCalledWith({
      category: 'FALL',
      status: undefined,
      page: 0,
      size: 20,
    });
    state.categoryFilter.value = '';
    state.statusFilter.value = 'REPORTED';
    await state.goToPage(2);
    expect(adverseEvents.list).toHaveBeenLastCalledWith({
      category: undefined,
      status: 'REPORTED',
      page: 1,
      size: 20,
    });
  });

  it('上报必填校验：类别缺失提示且零出网（其余字段逐项拦截）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.openReport();
    state.reportForm.value.eventSummary = '病房走廊跌倒';
    await state.onReport();
    expect(adverseEvents.report).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith('请选择事件类别（必填）');
    state.reportForm.value.category = 'FALL';
    await state.onReport();
    expect(ElMessage.warning).toHaveBeenCalledWith('请选择严重度分级（必填）');
    expect(adverseEvents.report).not.toHaveBeenCalled();
  });

  it('上报成功：匿名开关透传出网、关窗并回第一页重拉', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.openReport();
    state.reportForm.value = {
      category: 'FALL',
      severityClass: 'II',
      severityGrade: 'B',
      wardId: 'W01',
      occurredAt: '2026-10-01T08:00',
      eventSummary: '病房走廊跌倒',
      handlingNote: '',
      visitId: '',
      isAnonymous: true,
    };
    await state.onReport();
    expect(adverseEvents.report).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(adverseEvents.report).mock.calls[0]?.[0];
    expect(payload?.isAnonymous).toBe(true);
    expect(payload?.eventSummary).toBe('病房走廊跌倒');
    expect(state.reportVisible.value).toBe(false);
    // 上报成功后回第一页重拉（本用例无初拉，页面清单 size=20 恰一次；同 mock 的待处理
    // 探针调用 size=1 不计入——甲-3 新增探针与页面清单共用 adverseEvents.list 出入口）
    const pageListCalls = vi
      .mocked(adverseEvents.list)
      .mock.calls.filter((call) => call[0]?.size !== 1);
    expect(pageListCalls).toHaveLength(1);
  });

  it('上报出网时点钉面：occurredAt 按 +08:00 北京钟面解析转精确 ISO 绝对时刻（datetime-local 本地串直拼后端 OffsetDateTime 必 400）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.openReport();
    state.reportForm.value = {
      category: 'FALL',
      severityClass: 'II',
      severityGrade: 'B',
      wardId: 'W01',
      occurredAt: '2026-10-01T10:00',
      eventSummary: '病房走廊跌倒',
      handlingNote: '',
      visitId: '',
      isAnonymous: false,
    };
    await state.onReport();
    expect(adverseEvents.report).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(adverseEvents.report).mock.calls[0]?.[0];
    // W-70① 出网钉面后解析不依赖运行环境本地时区，绝对时刻唯一（北京钟面 10:00 = UTC
    // 02:00）——原「双跑必翻车」前提消失，D-21 同步收紧：形态正则升级为精确 ISO 值断言
    expect(payload?.occurredAt).toBe('2026-10-01T02:00:00.000Z');
  });

  it('D-11 patientId 死字段清理：表单模型与上报载荷均不含 patientId（无录入面字段不透传）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.openReport();
    // 表单初始态不得携带 patientId 键（无录入面的字段不得进入表单模型）
    expect('patientId' in state.reportForm.value).toBe(false);
    // 逐项属性赋值填必填面（该形态在删字段前后均类型合法），上报后断言出网载荷不含 patientId 键
    state.reportForm.value.category = 'FALL';
    state.reportForm.value.severityClass = 'II';
    state.reportForm.value.severityGrade = 'B';
    state.reportForm.value.wardId = 'W01';
    state.reportForm.value.occurredAt = '2026-10-01T08:00';
    state.reportForm.value.eventSummary = '病房走廊跌倒';
    await state.onReport();
    const payload = vi.mocked(adverseEvents.report).mock.calls[0]?.[0];
    expect(payload !== undefined && 'patientId' in payload).toBe(false);
  });

  it('处理操作：操作人留痕出网（handlerId=会话 userId）且成功后重拉', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await state.onHandle({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(adverseEvents.handle).toHaveBeenCalledWith('AE2026100100001', {
      handlerId: 'u1',
      handlingNote: '处置记录',
    });
    // 成功后重拉页面清单（size=20 恰一次；同 mock 的待处理探针 size=1 调用不计入）
    const pageListCalls = vi
      .mocked(adverseEvents.list)
      .mock.calls.filter((call) => call[0]?.size !== 1);
    expect(pageListCalls).toHaveLength(1);
  });

  it('退回操作：原因必填留痕出网；会话无操作人身份显式拦截零出网', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    // 弹层替身工厂默认回 { value: '处置记录' }——退回原因断言以该默认值证明透传链路
    await state.onReturn({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(adverseEvents.return).toHaveBeenCalledWith('AE2026100100001', {
      reason: '处置记录',
      returnerId: 'u1',
    });
    const noOperator = useAdverseEvents({ getOperatorId: () => '' });
    await noOperator.onReturn({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(ElMessage.warning).toHaveBeenCalledWith(
      '会话缺少操作人身份，无法执行该操作（请重新登录后再试）',
    );
    expect(adverseEvents.return).toHaveBeenCalledTimes(1);
  });

  it('今日概览统计带：getAdverseEventStats 无参出网（后端缺省=当日），三字段原样落态', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    expect(state.stats.value).toBe(null);
    await state.refreshStats();
    expect(getAdverseEventStats).toHaveBeenCalledTimes(1);
    expect(getAdverseEventStats).toHaveBeenCalledWith();
    // 三字段映射（甲-3 锚点①）：total=总报数 / deadlineMetCount=按时上报数 / deadlineTotal=时限分母
    expect(state.stats.value?.total).toBe('12');
    expect(state.stats.value?.deadlineMetCount).toBe('4');
    expect(state.stats.value?.deadlineTotal).toBe('5');
    expect(state.statsError.value).toBe(null);
  });

  it('今日概览统计带错误态：失败落 statsError 且 stats 保持 null，重试成功恢复三字段', async () => {
    vi.mocked(getAdverseEventStats).mockRejectedValueOnce(new Error('network down'));
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await state.refreshStats();
    expect(state.statsError.value).not.toBe(null);
    expect(state.stats.value).toBe(null);
    // 重试（手动刷新取数口径）：成功后错误清零、三字段恢复
    await state.refreshStats();
    expect(state.statsError.value).toBe(null);
    expect(state.stats.value?.total).toBe('12');
    expect(getAdverseEventStats).toHaveBeenCalledTimes(2);
  });

  it('待处理计数探针：REPORTED 过滤 size=1 出网取 total（门牌胶囊与统计带第三格同源）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({ content: [], total: '3' });
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    expect(state.pendingTotal.value).toBe(null);
    await state.refreshPending();
    expect(adverseEvents.list).toHaveBeenCalledWith({ status: 'REPORTED', page: 0, size: 1 });
    expect(state.pendingTotal.value).toBe('3');
  });

  it('待处理计数探针失败：pendingTotal 保持 null 不上抛（胶囊以 — 占位，弹错归拦截器）', async () => {
    vi.mocked(adverseEvents.list).mockRejectedValueOnce(new Error('network down'));
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await expect(state.refreshPending()).resolves.toBeUndefined();
    expect(state.pendingTotal.value).toBe(null);
  });

  it('处理成功后待处理探针联动重取（REPORTED→HANDLING 待处理数变化）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await state.onHandle({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(adverseEvents.list).toHaveBeenCalledWith({ status: 'REPORTED', page: 0, size: 1 });
  });

  it("上报表单病区下拉：listOrgs({type:'WARD'}) 真数据接线，选项落 wardOptions", async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    expect(state.wardOptions.value).toEqual([]);
    await state.loadWards();
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    expect(state.wardOptions.value).toEqual(WARD_SEED);
    expect(state.wardError.value).toBe(null);
  });

  it('上报表单病区下拉失败态：wardError 落位、选项清空，重试成功恢复（说问题给恢复）', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('network down'));
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await state.loadWards();
    expect(state.wardError.value).not.toBe(null);
    expect(state.wardOptions.value).toEqual([]);
    await state.loadWards();
    expect(state.wardError.value).toBe(null);
    expect(state.wardOptions.value).toEqual(WARD_SEED);
  });

  it('假常量清零配套：上报表单初始病区为空串（用户显式选择，缺省不再锚定 W01 演示病区）', () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    state.openReport();
    expect(state.reportForm.value.wardId).toBe('');
  });

  it('钤印成功反馈族：上报/处理/关闭/退回各盖对应钤印，文案=动词结果且不带操作人（匿名口径）', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    // 上报：完整必填面 → 钤印「已上报」（不带单号/操作人，事件号回显随列表重拉承载）
    state.openReport();
    state.reportForm.value = {
      category: 'FALL',
      severityClass: 'II',
      severityGrade: 'B',
      wardId: 'W01',
      occurredAt: '2026-10-01T08:00',
      eventSummary: '病房走廊跌倒',
      handlingNote: '',
      visitId: '',
      isAnonymous: true,
    };
    await state.onReport();
    expect(state.stampText.value).toBe('已上报');
    expect(state.stampVisible.value).toBe(true);
    expect(state.stampText.value).not.toContain('u1');
    // 处理 → 「已处理」
    await state.onHandle({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(state.stampText.value).toBe('已处理');
    // 关闭 → 「已关闭」
    await state.onClose({ eventNo: 'AE2026100100001', status: 'HANDLING' });
    expect(state.stampText.value).toBe('已关闭');
    // 退回 → 「已退回」
    await state.onReturn({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(state.stampText.value).toBe('已退回');
    // 序号递增：同文案连续盖章仍重放落章动画（view 层 :key 重挂锚）
    expect(state.stampSeq.value).toBeGreaterThanOrEqual(4);
  });
});
