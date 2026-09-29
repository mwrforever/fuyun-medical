// 护理评估面单测（EX-47 拆分）：换患者草稿复位扩展结果条清理（前患者判级不滞留）、
// 换量表清作答与结果、未答条目零出网。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { assessments } from '@/api/nursing';
import type { NursingAssessmentVO, WardPatientDetailVO } from '@/api/nursing';
import { useNursingAssessment } from './useNursingAssessment';

vi.mock('@/api/nursing', () => ({
  assessments: { scales: vi.fn(), create: vi.fn(), list: vi.fn() },
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
    conditionTags: '',
    allergyFlag: false,
    riskFlags: '',
    ...partial,
  };
}

/** 装配被测面（患者上下文口径同卡墙底座派生） */
function setup(detail: WardPatientDetailVO | undefined) {
  const selectedVisitId = ref<string | null>('I20260923000000001');
  const selectedDetail = computed(() => detail);
  return { ...useNursingAssessment({ selectedDetail, selectedVisitId }), selectedVisitId };
}

describe('useNursingAssessment', () => {
  beforeEach(() => {
    vi.mocked(assessments.scales).mockReset().mockResolvedValue([]);
    vi.mocked(assessments.create).mockReset().mockResolvedValue({});
    vi.mocked(assessments.list).mockReset().mockResolvedValue([]);
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('换患者草稿复位：作答清空 + 结果条残留清理（前患者判级不滞留）', () => {
    const state = setup(detailMock());
    state.scaleAnswers.value = { PERCEPTION: 3 };
    state.assessResult.value = { id: '9300', totalScore: 50, riskLevel: 'HIGH' };
    state.resetDraft();
    expect(state.scaleAnswers.value).toEqual({});
    expect(state.assessResult.value).toBeNull();
  });

  it('换量表清作答与结果（onScaleChange 既有口径）', () => {
    const state = setup(detailMock());
    state.scaleAnswers.value = { PERCEPTION: 3 };
    state.assessResult.value = { id: '9300' };
    state.onScaleChange();
    expect(state.scaleAnswers.value).toEqual({});
    expect(state.assessResult.value).toBeNull();
  });

  it('存在未作答条目提交被前置拦截零出网', async () => {
    vi.mocked(assessments.scales).mockResolvedValue([
      {
        scaleType: 'BRADEN',
        itemCodes: ['PERCEPTION', 'MOISTURE'],
        itemLabels: ['知觉感受', '潮湿程度'],
        choices: { PERCEPTION: [1, 2, 3, 4], MOISTURE: [1, 2, 3, 4] },
        totalRule: 'SUM',
      },
    ]);
    const state = setup(detailMock());
    await state.loadScales();
    state.scaleAnswers.value = { PERCEPTION: 3 };
    await state.onAssess();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '存在未作答条目，请完成全部条目后提交',
    );
    expect(vi.mocked(assessments.create)).not.toHaveBeenCalled();
  });

  it('EX-45 竞态守卫：换患者后旧患者历史回包丢弃', async () => {
    let releaseOld: (rows: NursingAssessmentVO[]) => void = () => {};
    vi.mocked(assessments.list).mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseOld = resolve;
        }),
    );
    const state = setup(detailMock());
    state.assessHistory.value = [];
    const pending = state.loadAssessmentHistory();
    // 旧患者历史请求在途时切换到新患者
    state.selectedVisitId.value = 'I20260923000000002';
    releaseOld([{ id: '9300' }]);
    await pending;
    expect(state.assessHistory.value).toEqual([]);
  });
});
