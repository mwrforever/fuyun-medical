// 不良事件 composable 单测（PR-3 Task 14）：分页列表筛选参数透传、上报必填校验零出网、
// 上报成功关窗重拉、处理/退回操作操作人留痕（getOperatorId 注入）与退回原因必填。
// api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adverseEvents } from '@/api/nursing';
import { useAdverseEvents } from './useAdverseEvents';

vi.mock('@/api/nursing', () => ({
  adverseEvents: {
    list: vi.fn(),
    report: vi.fn(),
    handle: vi.fn(),
    close: vi.fn(),
    return: vi.fn(),
  },
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  ADVERSE_CATEGORY_OPTIONS: [{ code: 'FALL', label: '跌倒坠床' }],
  SEVERITY_CLASS_OPTIONS: [{ code: 'II', label: 'II 级（重）' }],
  SEVERITY_GRADE_OPTIONS: [{ code: 'A', label: 'A' }],
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

describe('useAdverseEvents', () => {
  beforeEach(() => {
    vi.mocked(adverseEvents.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(adverseEvents.report).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.handle).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.close).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
    vi.mocked(adverseEvents.return).mockReset().mockResolvedValue({ eventNo: 'AE2026100100001' });
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
      patientId: '',
      isAnonymous: true,
    };
    await state.onReport();
    expect(adverseEvents.report).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(adverseEvents.report).mock.calls[0]?.[0];
    expect(payload?.isAnonymous).toBe(true);
    expect(payload?.eventSummary).toBe('病房走廊跌倒');
    expect(state.reportVisible.value).toBe(false);
    // 上报成功后回第一页重拉（本用例无初拉，恰一次）
    expect(adverseEvents.list).toHaveBeenCalledTimes(1);
  });

  it('处理操作：操作人留痕出网（handlerId=会话 userId）且成功后重拉', async () => {
    const state = useAdverseEvents({ getOperatorId: () => 'u1' });
    await state.onHandle({ eventNo: 'AE2026100100001', status: 'REPORTED' });
    expect(adverseEvents.handle).toHaveBeenCalledWith('AE2026100100001', {
      handlerId: 'u1',
      handlingNote: '处置记录',
    });
    expect(adverseEvents.list).toHaveBeenCalledTimes(1);
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
});
