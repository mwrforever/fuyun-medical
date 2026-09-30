// 责任护士分配面单测（EX-47 拆分）：按病区+班次加载、管床缺床位号零出网。api mock 承载。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { assignments } from '@/api/nursing';
import { useAssignments } from './useAssignments';

vi.mock('@/api/nursing', () => ({
  assignments: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
}));

vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return { ...mod, ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } };
});

describe('useAssignments', () => {
  const wardId = ref('W01');
  const shiftCode = ref('DAY');

  beforeEach(() => {
    vi.mocked(assignments.list).mockReset().mockResolvedValue([]);
    vi.mocked(assignments.create).mockReset().mockResolvedValue({});
    vi.mocked(ElMessage.warning).mockClear();
  });

  it('按当前病区+班次加载分配清单', async () => {
    vi.mocked(assignments.list).mockResolvedValue([
      { id: 1, nurseId: 'n1', assignmentType: 'BED', bedNo: '01' },
    ] as never);
    const state = useAssignments({ wardId, shiftCode });
    await state.loadAssignments();
    expect(assignments.list).toHaveBeenCalledWith('W01', 'DAY');
    expect(state.assignmentList.value).toHaveLength(1);
    expect(state.assignmentLoading.value).toBe(false);
  });

  it('管床分配缺床位号提示且零出网', async () => {
    const state = useAssignments({ wardId, shiftCode });
    state.assignForm.value = { nurseId: 'n1', assignmentType: 'BED', bedNo: '', patientId: '' };
    await state.onAssign();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('管床分配需填写床位号');
    expect(vi.mocked(assignments.create)).not.toHaveBeenCalled();
  });
});
