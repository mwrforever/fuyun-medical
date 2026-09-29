// 入区登记面单测（EX-47 拆分）：visit 号格式非法零出网、提交成功关窗复位表单并触发卡墙
// 重载。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { wardPatients } from '@/api/nursing';
import { useWardRegister } from './useWardRegister';

vi.mock('@/api/nursing', () => ({
  wardPatients: { register: vi.fn(), list: vi.fn(), detail: vi.fn(), remove: vi.fn() },
}));

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return { ...mod, ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } };
});

describe('useWardRegister', () => {
  const wardId = ref('W01');

  beforeEach(() => {
    vi.mocked(wardPatients.register).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('visit 号格式非法提示且零出网（I2026 非 14 位）', async () => {
    const state = useWardRegister({ wardId, reloadWard: vi.fn() });
    state.registerVisible.value = true;
    state.registerForm.value = {
      patientId: '1932000000000000002',
      visitId: 'I2026',
      patientName: '李四',
      bedNo: '05',
      nursingLevel: 'NORMAL',
      conditionTags: [],
    };
    await state.onRegister();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      'visit 号应以 I 开头共 14 位（I+日期+流水），请核对入区单',
    );
    expect(vi.mocked(wardPatients.register)).not.toHaveBeenCalled();
    expect(state.registerVisible.value).toBe(true);
  });

  it('提交成功关窗、复位表单并触发卡墙重载', async () => {
    const reloadWard = vi.fn().mockResolvedValue(undefined);
    const state = useWardRegister({ wardId, reloadWard });
    state.registerForm.value = {
      patientId: '1932000000000000002',
      visitId: 'I2026092300002',
      patientName: '李四',
      bedNo: '05',
      nursingLevel: 'NORMAL',
      conditionTags: ['NEW'],
    };
    await state.onRegister();
    expect(vi.mocked(wardPatients.register)).toHaveBeenCalledWith({
      visitId: 'I2026092300002',
      patientId: '1932000000000000002',
      wardId: 'W01',
      bedNo: '05',
      patientName: '李四',
      nursingLevel: 'NORMAL',
      conditionTags: 'NEW',
    });
    expect(reloadWard).toHaveBeenCalledTimes(1);
    expect(state.registerVisible.value).toBe(false);
    expect(state.registerForm.value.patientName).toBe('');
  });
});
