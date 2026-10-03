// 特殊事件面单测（EX-47 拆分）：换患者草稿复位（BUG-15 同类修复：类型/备注不跨患者
// 滞留）、记录成功清备注并刷新体温单。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { chart } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { useSpecialEvents } from './useSpecialEvents';

vi.mock('@/api/nursing', () => ({
  chart: { query: vi.fn(), addSpecialEvent: vi.fn() },
}));

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return { ...mod, ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } };
});

/** 患者详情（visitId 可覆写） */
function detailMock(partial: Partial<WardPatientDetailVO> = {}): WardPatientDetailVO {
  return {
    wardId: 'W01',
    bedNo: '01',
    patientId: '1932000000000000001',
    visitId: 'I20260923000000001',
    patientName: '张三',
    allergyFlag: false,
    riskFlags: '',
    ...partial,
  };
}

/** 装配被测面（患者上下文口径同卡墙底座派生） */
function setup(detail: WardPatientDetailVO | undefined) {
  const selectedDetail = computed(() => detail);
  const onEventRecorded = vi.fn().mockResolvedValue(undefined);
  return { ...useSpecialEvents({ selectedDetail, onEventRecorded }), onEventRecorded };
}

describe('useSpecialEvents', () => {
  beforeEach(() => {
    vi.mocked(chart.addSpecialEvent).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('换患者草稿复位：类型回 ADMISSION、备注清空（BUG-15 同类）', () => {
    const state = setup(detailMock());
    state.specialEventType.value = 'SURGERY';
    state.specialEventRemark.value = '术前准备完毕';
    state.resetDraft();
    expect(state.specialEventType.value).toBe('ADMISSION');
    expect(state.specialEventRemark.value).toBe('');
  });

  it('记录成功清备注并触发体温单刷新', async () => {
    const state = setup(detailMock());
    state.specialEventType.value = 'SURGERY';
    state.specialEventRemark.value = '术前准备完毕';
    await state.onAddSpecialEvent();
    expect(chart.addSpecialEvent).toHaveBeenCalledWith('I20260923000000001', {
      eventType: 'SURGERY',
      remark: '术前准备完毕',
    });
    expect(state.onEventRecorded).toHaveBeenCalledTimes(1);
    expect(state.specialEventRemark.value).toBe('');
  });

  it('未选患者记录被前置拦截零出网', async () => {
    const state = setup(undefined);
    await state.onAddSpecialEvent();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请先从床位卡墙选择患者');
    expect(vi.mocked(chart.addSpecialEvent)).not.toHaveBeenCalled();
  });
});
