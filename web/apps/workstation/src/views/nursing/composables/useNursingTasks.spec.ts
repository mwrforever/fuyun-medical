// 护理任务面单测（EX-47 拆分）：当日清单按病区加载、逾期行类契约、患者回显名取详情
// 映射、待执行任务认领（assigneeId 留痕+成功重拉）、常规模板生成（确认后出网+条数回显
// +空认领人拦截）。api mock 承载，不打真实网络。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { tasks } from '@/api/nursing';
import type { NursingTaskVO, WardPatientDetailVO } from '@/api/nursing';
import { useNursingTasks } from './useNursingTasks';

vi.mock('@/api/nursing', () => ({
  tasks: {
    list: vi.fn(),
    complete: vi.fn(),
    cancel: vi.fn(),
    claim: vi.fn(),
    generateRoutine: vi.fn(),
  },
}));

// 仅替身弹层件（确认/提示断言用），其余导出原样保留
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

describe('useNursingTasks', () => {
  const wardId = ref('W01');
  const detailMap = ref<Record<string, WardPatientDetailVO>>({});

  beforeEach(() => {
    vi.mocked(tasks.list).mockReset().mockResolvedValue([]);
    vi.mocked(tasks.claim).mockReset();
    vi.mocked(tasks.generateRoutine).mockReset();
    detailMap.value = {};
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
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

  it('待执行任务认领：assigneeId 留痕出网且成功后重拉清单', async () => {
    vi.mocked(tasks.claim).mockResolvedValue({ taskNo: 'T1', status: 'IN_PROGRESS' });
    const state = useNursingTasks({ wardId, detailMap, getAssigneeId: () => 'u1' });
    await state.onClaimTask({ taskNo: 'T1', status: 'PENDING' });
    expect(tasks.claim).toHaveBeenCalledTimes(1);
    expect(tasks.claim).toHaveBeenCalledWith('T1', { assigneeId: 'u1' });
    // 成功后清单重拉（认领态即时回显）
    expect(tasks.list).toHaveBeenCalledTimes(1);
    expect(state.actingTaskNo.value).toBeNull();
  });

  it('认领在途守卫：actingTaskNo 非空时二次认领零出网（双击防重）', async () => {
    let release: (vo: NursingTaskVO) => void = () => {};
    vi.mocked(tasks.claim).mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const state = useNursingTasks({ wardId, detailMap, getAssigneeId: () => 'u1' });
    const first = state.onClaimTask({ taskNo: 'T1', status: 'PENDING' });
    await state.onClaimTask({ taskNo: 'T2', status: 'PENDING' });
    expect(tasks.claim).toHaveBeenCalledTimes(1);
    release({ taskNo: 'T1' });
    await first;
  });

  it('认领人缺失（会话无 userId）显式拦截零出网', async () => {
    const state = useNursingTasks({ wardId, detailMap, getAssigneeId: () => '' });
    await state.onClaimTask({ taskNo: 'T1', status: 'PENDING' });
    expect(tasks.claim).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith(
      '会话缺少操作人身份，无法认领（请重新登录后再试）',
    );
  });

  it('生成常规任务：确认后按病区+当日出网并回显生成条数', async () => {
    vi.mocked(tasks.generateRoutine).mockResolvedValue({ createdTasks: 6 });
    const state = useNursingTasks({ wardId, detailMap, getAssigneeId: () => 'u1' });
    await state.onGenerateRoutine();
    expect(tasks.generateRoutine).toHaveBeenCalledTimes(1);
    expect(vi.mocked(tasks.generateRoutine).mock.calls[0]?.[0]?.wardId).toBe('W01');
    expect(ElMessage.success).toHaveBeenCalledWith('已生成 6 条常规任务');
    expect(tasks.list).toHaveBeenCalledTimes(1);
  });
});
