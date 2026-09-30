// 护理任务面单测（EX-47 拆分）：当日清单按病区加载、逾期行类契约、患者回显名取详情
// 映射。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { tasks } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { useNursingTasks } from './useNursingTasks';

vi.mock('@/api/nursing', () => ({
  tasks: { list: vi.fn(), complete: vi.fn(), cancel: vi.fn() },
}));

describe('useNursingTasks', () => {
  const wardId = ref('W01');
  const detailMap = ref<Record<string, WardPatientDetailVO>>({});

  beforeEach(() => {
    vi.mocked(tasks.list).mockReset().mockResolvedValue([]);
    detailMap.value = {};
  });

  it('当日清单按当前病区加载（date=当日 yyyy-MM-dd）', async () => {
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    vi.mocked(tasks.list).mockResolvedValue([{ taskNo: 'T1' }]);
    const state = useNursingTasks({ wardId, detailMap });
    await state.loadTasks();
    expect(tasks.list).toHaveBeenCalledWith({ wardId: 'W01', status: undefined, date: today });
    expect(state.taskList.value).toHaveLength(1);
    expect(state.taskLoading.value).toBe(false);
  });

  it('逾期行类契约：overdueFlag=true 挂 fuy-task-overdue（spec 机器判据）', () => {
    const state = useNursingTasks({ wardId, detailMap });
    expect(state.taskRowClass({ row: { overdueFlag: true } })).toBe('fuy-task-overdue');
    expect(state.taskRowClass({ row: { overdueFlag: false } })).toBe('');
  });

  it('患者回显名取详情映射姓名，缺详情回退床位号', () => {
    detailMap.value = { V1: { visitId: 'V1', patientName: '张三' } };
    const state = useNursingTasks({ wardId, detailMap });
    expect(state.taskPatientLabel({ visitId: 'V1' })).toBe('张三');
    expect(state.taskPatientLabel({ visitId: 'V2', bedNo: '02' })).toBe('02');
    expect(state.taskPatientLabel({})).toBe('—');
  });
});
