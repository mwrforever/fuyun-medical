/**
 * 病区看板作业面④：责任护士分配（WardBoardView 巨型脚本随迁，EX-47 拆分）。当班分配
 * 清单加载 + 新增分配内联表单（BED 管床需床位 / PRIMARY 责任需患者 ID）+ 移除分配
 * （中档确认带回显）。行为与拆分前逐字一致（FU-M05-01 拖拽批量分配 P-later 登记）。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { assignments } from '@/api/nursing';
import type { NurseAssignmentVO } from '@/api/nursing';
import { surfaceBizError } from '../wardBoardShared';

/** 分配类型词表（PRIMARY 责任患者 / BED 管床；新增分配表单与展示共用） */
export const ASSIGNMENT_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'BED', label: '管床' },
  { code: 'PRIMARY', label: '责任患者' },
];

/** 责任护士分配参数对象（病区/班次上下文经卡墙底座注入） */
export interface UseAssignmentsOptions {
  /** 当前病区代码（分配归属病区），来自 useWardContext.wardId */
  wardId: Ref<string>;
  /** 当前班次（分配的班次上下文），来自 useWardContext.shiftCode */
  shiftCode: Ref<string>;
}

/** 初始化分配面（每组件实例独立状态，仅 setup 同步调用） */
export function useAssignments(options: UseAssignmentsOptions) {
  const assignmentList = ref<NurseAssignmentVO[]>([]);
  const assignmentLoading = ref(false);
  const assigning = ref(false);
  /** 新增分配内联表单（BED 管床需床位；PRIMARY 责任需患者 ID） */
  const assignFormVisible = ref(false);
  const assignForm = ref({ nurseId: '', assignmentType: 'BED', bedNo: '', patientId: '' });

  async function loadAssignments(): Promise<void> {
    assignmentLoading.value = true;
    try {
      assignmentList.value = await assignments.list(
        options.wardId.value,
        options.shiftCode.value,
      );
    } catch {
      // 失败弹错归响应拦截器
    } finally {
      assignmentLoading.value = false;
    }
  }

  /** 新增分配：必填面前置校验零出网 → 出网 → 重载（FU-M05-01 拖拽批量分配 P-later 登记）。 */
  async function onAssign(): Promise<void> {
    if (assigning.value) {
      return;
    }
    const form = assignForm.value;
    if (form.nurseId.trim() === '') {
      void ElMessage.warning('请填写护士工号');
      return;
    }
    if (form.assignmentType === 'BED' && form.bedNo.trim() === '') {
      void ElMessage.warning('管床分配需填写床位号');
      return;
    }
    assigning.value = true;
    try {
      await assignments.create({
        wardId: options.wardId.value,
        nurseId: form.nurseId.trim(),
        assignmentType: form.assignmentType,
        shiftCode: options.shiftCode.value,
        bedNo: form.assignmentType === 'BED' ? form.bedNo.trim() : undefined,
        patientId:
          form.assignmentType === 'PRIMARY' && form.patientId.trim() !== ''
            ? form.patientId.trim()
            : undefined,
      });
      void ElMessage.success('分配已保存');
      assignFormVisible.value = false;
      assignForm.value = { nurseId: '', assignmentType: 'BED', bedNo: '', patientId: '' };
      await loadAssignments();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      assigning.value = false;
    }
  }

  /** 移除分配（中档确认带回显） */
  async function onUnassign(row: NurseAssignmentVO): Promise<void> {
    try {
      await ElMessageBox.confirm(
        `即将移除护士 ${row.nurseId ?? ''} 的${row.assignmentType === 'BED' ? `管床（${row.bedNo ?? ''}）` : '责任患者分配'}，确认？`,
        '移除分配确认',
        { confirmButtonText: '确认移除', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    try {
      await assignments.remove(String(row.id ?? ''));
      void ElMessage.success('分配已移除');
      await loadAssignments();
    } catch (error) {
      surfaceBizError(error);
    }
  }

  return {
    assignmentList,
    assignmentLoading,
    assigning,
    assignFormVisible,
    assignForm,
    loadAssignments,
    onAssign,
    onUnassign,
  };
}
