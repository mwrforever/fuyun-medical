// 体征面单测（EX-47 拆分）：表单复位（BUG-15 口径随迁）、待复核确认成功该行移除（spec
// 冻结语义）。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { vitalSigns } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { useVitalSigns } from './useVitalSigns';

vi.mock('@/api/nursing', () => ({
  vitalSigns: {
    record: vi.fn(),
    list: vi.fn(),
    pendingReview: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
}));

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: '测试原因' }),
    },
  };
});

/** 装配被测面（患者上下文口径同卡墙底座派生） */
function setup(patient: WardPatientVO | undefined, detail: WardPatientDetailVO | undefined) {
  const wardId = ref('W01');
  const selectedPatient = computed(() => patient);
  const selectedDetail = computed(() => detail);
  return useVitalSigns({
    selectedPatient,
    selectedDetail,
    wardId,
    onRecorded: vi.fn().mockResolvedValue(undefined),
  });
}

describe('useVitalSigns', () => {
  beforeEach(() => {
    vi.mocked(vitalSigns.pendingReview).mockReset().mockResolvedValue([]);
    vi.mocked(vitalSigns.confirm).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('resetVitalForm 回初始值（换患者/提交成功共用口径）', () => {
    const state = setup(undefined, undefined);
    state.vitalForm.value.temperature = '36.5';
    state.vitalForm.value.pulse = '80';
    state.resetVitalForm();
    expect(state.vitalForm.value).toEqual({
      temperature: '',
      tempSite: 'AXILLARY',
      pulse: '',
      respiration: '',
      systolicBp: '',
      diastolicBp: '',
      spo2: '',
      weight: '',
      height: '',
      painScore: '',
    });
  });

  it('未选患者提交被前置拦截零出网', async () => {
    const state = setup(undefined, undefined);
    await state.onRecordVitals();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请先从床位卡墙选择患者');
    expect(vi.mocked(vitalSigns.record)).not.toHaveBeenCalled();
  });

  it('待复核确认成功后该行移除（spec 冻结语义）', async () => {
    const state = setup(undefined, undefined);
    state.pendingList.value = [
      { id: '9200', measuredAt: '2026-09-23T08:00:00', temperature: 37.8 },
      { id: '9201', measuredAt: '2026-09-23T09:00:00', temperature: 36.9 },
    ];
    await state.onConfirmVital({ id: '9200' });
    expect(vitalSigns.confirm).toHaveBeenCalledWith('9200');
    expect(state.pendingList.value.map((row) => row.id)).toEqual(['9201']);
    expect(state.confirmingId.value).toBeNull();
  });
});
