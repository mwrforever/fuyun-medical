// 体温单面单测（EX-47 拆分）：未选患者清空月页、EX-45 竞态守卫（换患者后旧患者慢回包
// 丢弃）、早于入院月禁翻。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';
import { chart, vitalSigns } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { useTempChart } from './useTempChart';

vi.mock('@/api/nursing', () => ({
  chart: { query: vi.fn(), addSpecialEvent: vi.fn() },
  vitalSigns: {
    record: vi.fn(),
    list: vi.fn(),
    pendingReview: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
}));

/** 患者详情（admittedAt 可覆写：入院月禁翻断言用） */
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
  const selectedVisitId = ref<string | null>('I20260923000000001');
  const selectedDetail = computed(() => detail);
  return { ...useTempChart({ selectedDetail, selectedVisitId }), selectedVisitId };
}

describe('useTempChart', () => {
  beforeEach(() => {
    vi.mocked(chart.query).mockReset().mockResolvedValue({
      visitId: 'I20260923000000001',
      chartMonth: '2026-09',
      vitals: [],
      specialEvents: [],
      dailyValues: [],
    });
    vi.mocked(vitalSigns.list).mockReset().mockResolvedValue([]);
  });

  it('未选患者清空月页且零出网', async () => {
    const state = setup(undefined);
    state.chartData.value = { visitId: 'X' };
    await state.loadChart();
    expect(state.chartData.value).toBeNull();
    expect(state.monthVitals.value).toEqual([]);
    expect(vi.mocked(chart.query)).not.toHaveBeenCalled();
  });

  it('EX-45 竞态守卫：换患者后旧患者慢回包整包丢弃', async () => {
    let releaseOld: () => void = () => {};
    vi.mocked(vitalSigns.list).mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseOld = () => resolve([]);
        }),
    );
    const state = setup(detailMock());
    const pending = state.loadChart();
    // 旧患者月页请求在途时切换到新患者
    state.selectedVisitId.value = 'I20260923000000002';
    releaseOld();
    await pending;
    expect(state.chartData.value).toBeNull();
    expect(state.monthVitals.value).toEqual([]);
    expect(state.chartLoading.value).toBe(false);
  });

  it('早于入院月的上月按钮禁用（§5.8 越界禁用）', () => {
    const now = new Date();
    const currentMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    const state = setup(detailMock({ admittedAt: `${currentMonth}-15T08:00:00` }));
    expect(state.prevMonthDisabled.value).toBe(true);
  });
});
