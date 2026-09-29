// 出入量面单测（EX-47 拆分）：换患者草稿复位（BUG-15 既有口径 ef2bc7d 随迁）、数量格式
// 非法零出网、记录成功表单回初始行。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { ioRecords } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { useIoRecords } from './useIoRecords';

vi.mock('@/api/nursing', () => ({
  ioRecords: { create: vi.fn(), list: vi.fn() },
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
  const selectedDetail = computed(() => detail);
  return useIoRecords({ selectedDetail });
}

describe('useIoRecords', () => {
  beforeEach(() => {
    vi.mocked(ioRecords.create).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('换患者草稿复位：出入量明细回初始行（ef2bc7d 口径随迁）', () => {
    const state = setup(detailMock());
    state.ioForm.value = { ioType: 'OUTPUT', itemCode: 'URINE', quantity: '300', unit: 'ml' };
    state.resetDraft();
    expect(state.ioForm.value).toEqual({
      ioType: 'INTAKE',
      itemCode: '',
      quantity: '',
      unit: 'ml',
    });
  });

  it('数量格式非法提示且零出网（两位小数拦截）', async () => {
    const state = setup(detailMock());
    state.ioForm.value = { ioType: 'INTAKE', itemCode: 'FOOD-MILK', quantity: '150.25', unit: 'ml' };
    await state.onCreateIoRecord();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('数量应为数值（最多一位小数）');
    expect(vi.mocked(ioRecords.create)).not.toHaveBeenCalled();
  });

  it('记录成功表单回初始行（quantity string 透传零运算）', async () => {
    const state = setup(detailMock());
    state.ioForm.value = { ioType: 'INTAKE', itemCode: 'FOOD-MILK', quantity: '150', unit: '' };
    await state.onCreateIoRecord();
    expect(ioRecords.create).toHaveBeenCalledWith({
      visitId: 'I20260923000000001',
      ioType: 'INTAKE',
      itemCode: 'FOOD-MILK',
      quantity: '150',
      unit: undefined,
      source: 'MANUAL',
    });
    expect(state.ioForm.value.itemCode).toBe('');
    expect(state.ioRecording.value).toBe(false);
  });
});
