// 交接班面单测（EX-47 拆分）：当日材料取末条、DRAFT 生成幂等回填、未填接班工号零出网。
// api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handovers } from '@/api/nursing';
import { useHandover } from './useHandover';

vi.mock('@/api/nursing', () => ({
  handovers: { generate: vi.fn(), complete: vi.fn(), list: vi.fn() },
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

describe('useHandover', () => {
  const wardId = ref('W01');
  const shiftCode = ref('DAY');
  const operatorName = computed(() => '李护士');

  beforeEach(() => {
    vi.mocked(handovers.list).mockReset().mockResolvedValue([]);
    vi.mocked(handovers.generate).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('当日材料多行取末条（同日重生成幂等回显口径）', async () => {
    vi.mocked(handovers.list).mockResolvedValue([{ handoverNo: 'HD1' }, { handoverNo: 'HD2' }]);
    const state = useHandover({ wardId, shiftCode, operatorName });
    await state.loadHandoverOfDay();
    expect(state.handover.value?.handoverNo).toBe('HD2');
    expect(state.handoverLoading.value).toBe(false);
  });

  it('生成按病区+班次出网并回填当日材料', async () => {
    vi.mocked(handovers.generate).mockResolvedValue({ handoverNo: 'HD3' });
    const state = useHandover({ wardId, shiftCode, operatorName });
    await state.onGenerateHandover();
    expect(handovers.generate).toHaveBeenCalledWith({ wardId: 'W01', shiftCode: 'DAY' });
    expect(state.handover.value?.handoverNo).toBe('HD3');
    expect(state.generating.value).toBe(false);
  });

  it('未填接班工号时完成交接被前置拦截零出网', async () => {
    const state = useHandover({ wardId, shiftCode, operatorName });
    state.handover.value = { handoverNo: 'HD3', status: 'DRAFT' };
    await state.onCompleteHandover();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写接班护士工号');
    expect(vi.mocked(ElMessageBox.confirm)).not.toHaveBeenCalled();
  });
});
