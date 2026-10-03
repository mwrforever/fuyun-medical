// 最新体征面单测（EX-47 拆分）：近 24h 末次值组装、未选患者清空、EX-45 竞态守卫——换患者后
// 旧患者慢回包丢弃不写入简报。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';
import { vitalSigns } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { useLatestVitals } from './useLatestVitals';

vi.mock('@/api/nursing', () => ({
  vitalSigns: {
    record: vi.fn(),
    list: vi.fn(),
    pendingReview: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
}));

/** 患者详情（patientId/visitId 可覆写） */
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

/** 装配被测面（detailMap 按 selectedVisitId 派生 detail，口径同卡墙底座） */
function setup(detailMap: Record<string, WardPatientDetailVO>) {
  const selectedVisitId = ref<string | null>('I20260923000000001');
  const selectedDetail = computed(() =>
    selectedVisitId.value === null ? undefined : detailMap[selectedVisitId.value],
  );
  return { ...useLatestVitals({ selectedDetail, selectedVisitId }), selectedVisitId };
}

describe('useLatestVitals', () => {
  beforeEach(() => {
    vi.mocked(vitalSigns.list).mockReset().mockResolvedValue([]);
  });

  it('近 24h 查询取末次值为简报（多行取最后一行）', async () => {
    vi.mocked(vitalSigns.list).mockResolvedValue([
      { id: '9001', temperature: 36.5 },
      { id: '9002', temperature: 38.9 },
    ] as never);
    const state = setup({ I20260923000000001: detailMock() });
    await state.loadLatestVitals();
    expect(state.latestVitals.value?.temperature).toBe(38.9);
    expect(state.latestVitalsLoading.value).toBe(false);
  });

  it('未选患者时清空简报且零出网', async () => {
    const state = setup({});
    state.selectedVisitId.value = null;
    await state.loadLatestVitals();
    expect(state.latestVitals.value).toBeNull();
    expect(vi.mocked(vitalSigns.list)).not.toHaveBeenCalled();
  });

  it('EX-45 竞态守卫：换患者后旧患者慢回包整包丢弃', async () => {
    let releaseOld: (rows: Array<{ id: string; temperature: number }>) => void = () => {};
    vi.mocked(vitalSigns.list).mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseOld = resolve;
        }),
    );
    const state = setup({ I20260923000000001: detailMock() });
    const pending = state.loadLatestVitals();
    // 旧患者请求在途时切换到新患者
    state.selectedVisitId.value = 'I20260923000000002';
    releaseOld([{ id: '9001', temperature: 38.9 }]);
    await pending;
    expect(state.latestVitals.value).toBeNull();
  });
});
