/**
 * 病区看板作业面⑧前半：护理任务（WardBoardView 巨型脚本随迁，EX-47 拆分）。当日任务
 * 清单加载（状态过滤）+ 完成/取消（中档确认带回显；逾期任务仍可完成——M05 Spec §5
 * 状态机口径）+ 逾期行类契约 + 待执行认领与常规模板生成（PR-3 Task 9 面：assigneeId
 * 会话留痕、批量生成确认后出网）。行为与拆分前逐字一致（认领/生成为 PR-3 新增面）。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
import { tasks } from '@/api/nursing';
import type { NursingTaskVO, WardPatientDetailVO } from '@/api/nursing';
import { surfaceBizError } from '@/utils/bizError';
import { todayString } from '../wardBoardShared';

/** 任务类型中文词表（NursingTaskVO.taskType 十值枚举展示映射） */
export const TASK_TYPE_LABELS: Record<string, string> = {
  MEDICATION: '给药',
  INFUSION_CARE: '输液',
  TURN: '翻身',
  PATROL: '巡视',
  SPECIMEN: '标本',
  IO_MONITOR: '出入量',
  IOT_LINKAGE: 'IoT 联动',
  ASSESS_REMIND: '评估提醒',
  MANUAL: '手工',
  PREVENTION: '防范',
};

/** 任务状态 tag 映射（§3.11：PENDING/IN_PROGRESS/COMPLETED+aa/CANCELLED+strike） */
export const TASK_STATUS_META: Record<
  string,
  { type: 'primary' | 'warning' | 'success' | 'info'; text: string; strike?: boolean }
> = {
  PENDING: { type: 'info', text: '待执行' },
  IN_PROGRESS: { type: 'primary', text: '执行中' },
  COMPLETED: { type: 'success', text: '已完成' },
  CANCELLED: { type: 'info', text: '已取消', strike: true },
};

/** 生成常规任务的在途哨兵锚点（批量动作无 taskNo，复用 actingTaskNo 单互斥通道） */
const ROUTINE_ACTION_KEY = '__routine__';

/** 护理任务参数对象（病区上下文与卡墙详情索引经底座注入） */
export interface UseNursingTasksOptions {
  /** 当前病区代码（任务清单范围），来自 useWardContext.wardId */
  wardId: Ref<string>;
  /** 详情按 visitId 索引（任务患者回显名取值源），来自 useWardContext.detailMap */
  detailMap: Ref<Record<string, WardPatientDetailVO>>;
  /**
   * 认领/生成常规任务的操作人 userId 取值器（会话惰性取值；缺省空串——认领前显式
   * 判空拦截零出网）。来源：auth store 会话用户。
   */
  getAssigneeId?: () => string;
}

/** 初始化任务面（每组件实例独立状态，仅 setup 同步调用） */
export function useNursingTasks(options: UseNursingTasksOptions) {
  /** 任务动作在途标志（完成/取消互斥，同任务同时至多一个可发） */
  const actingTaskNo = ref<string | null>(null);
  const taskList = ref<NursingTaskVO[]>([]);
  const taskLoading = ref(false);
  const taskStatusFilter = ref('');

  async function loadTasks(): Promise<void> {
    taskLoading.value = true;
    try {
      taskList.value = await tasks.list({
        wardId: options.wardId.value,
        status: taskStatusFilter.value === '' ? undefined : taskStatusFilter.value,
        date: todayString(),
      });
    } catch {
      // 失败弹错归响应拦截器
    } finally {
      taskLoading.value = false;
    }
  }

  /** 任务行类（§3.10 逾期契约：overdueFlag=true 挂 .fuy-task-overdue，spec 机器判据） */
  function taskRowClass({ row }: { row: NursingTaskVO }): string {
    return row.overdueFlag === true ? 'fuy-task-overdue' : '';
  }

  function taskStatusMeta(status: string | undefined): {
    type: 'primary' | 'warning' | 'success' | 'info';
    text: string;
    strike?: boolean;
  } {
    return TASK_STATUS_META[status ?? ''] ?? { type: 'info', text: status ?? '—' };
  }

  /** 任务患者回显名（visitId → 详情映射姓名；缺详情回退床位号） */
  function taskPatientLabel(row: NursingTaskVO): string {
    const detail = options.detailMap.value[row.visitId ?? ''];
    return detail?.patientName ?? row.bedNo ?? '—';
  }

  /** 完成任务（中档确认带回显；逾期任务仍可完成——M05 Spec §5 状态机口径） */
  async function onCompleteTask(row: NursingTaskVO): Promise<void> {
    if (actingTaskNo.value !== null) {
      return;
    }
    const typeLabel = TASK_TYPE_LABELS[row.taskType ?? ''] ?? row.taskType ?? '';
    try {
      await ElMessageBox.confirm(
        `完成任务 ${row.taskNo ?? ''}（${row.bedNo ?? ''} ${taskPatientLabel(row)} ${typeLabel}）？`,
        '任务完成确认',
        { confirmButtonText: '确认完成', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    actingTaskNo.value = row.taskNo ?? '';
    try {
      await tasks.complete(row.taskNo ?? '');
      void ElMessage.success(`任务已完成：${row.taskNo ?? ''}`);
      await loadTasks();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      actingTaskNo.value = null;
    }
  }

  /** 取消任务（中档确认 + 必填原因） */
  async function onCancelTask(row: NursingTaskVO): Promise<void> {
    if (actingTaskNo.value !== null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `取消任务 ${row.taskNo ?? ''}（${row.bedNo ?? ''} ${taskPatientLabel(row)}），取消原因必填`,
        '任务取消确认',
        {
          confirmButtonText: '确认取消任务',
          cancelButtonText: '返回',
          inputPlaceholder: '取消原因（必填）',
          inputValidator: (input: string) => (input.trim() === '' ? '取消原因不能为空' : true),
        },
      );
      actingTaskNo.value = row.taskNo ?? '';
      await tasks.cancel(row.taskNo ?? '', { reason: value.trim() });
      void ElMessage.success(`任务已取消：${row.taskNo ?? ''}`);
      await loadTasks();
    } catch (error) {
      if (!axios.isAxiosError(error)) {
        return;
      }
      surfaceBizError(error);
    } finally {
      actingTaskNo.value = null;
    }
  }

  /** 认领任务（PENDING → 执行中；assigneeId=当班护士留痕，缺会话身份零出网显式拦截） */
  async function onClaimTask(row: NursingTaskVO): Promise<void> {
    if (actingTaskNo.value !== null) {
      return;
    }
    const assigneeId = options.getAssigneeId?.() ?? '';
    if (assigneeId === '') {
      void ElMessage.warning('会话缺少操作人身份，无法认领（请重新登录后再试）');
      return;
    }
    actingTaskNo.value = row.taskNo ?? '';
    try {
      await tasks.claim(row.taskNo ?? '', { assigneeId });
      void ElMessage.success(`已认领任务：${row.taskNo ?? ''}`);
      await loadTasks();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      actingTaskNo.value = null;
    }
  }

  /** 生成常规任务（常规模板按病区+当日批量生成；确认后出网，回显生成条数并重拉清单） */
  async function onGenerateRoutine(): Promise<void> {
    if (actingTaskNo.value !== null) {
      return;
    }
    try {
      await ElMessageBox.confirm(
        `按 ${options.wardId.value} 病区常规模板生成当日任务？重复生成由后端模板日去重承载`,
        '生成常规任务',
        { confirmButtonText: '确认生成', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    actingTaskNo.value = ROUTINE_ACTION_KEY;
    try {
      const result = await tasks.generateRoutine({
        wardId: options.wardId.value,
        date: todayString(),
      });
      void ElMessage.success(`已生成 ${result.createdTasks ?? 0} 条常规任务`);
      await loadTasks();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      actingTaskNo.value = null;
    }
  }

  return {
    actingTaskNo,
    taskList,
    taskLoading,
    taskStatusFilter,
    loadTasks,
    taskRowClass,
    taskStatusMeta,
    taskPatientLabel,
    onCompleteTask,
    onCancelTask,
    onClaimTask,
    onGenerateRoutine,
  };
}
