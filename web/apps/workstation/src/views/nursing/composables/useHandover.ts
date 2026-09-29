/**
 * 病区看板作业面⑧后半：交接班双签（WardBoardView 巨型脚本随迁，EX-47 拆分）。当日交接
 * 班材料加载/生成（SBAR 自动汇总，本班次已存在时后端幂等返回）与完成双签（中档确认带回显
 * 「交班 X → 接班 Y」；DRAFT 可点 / COMPLETED 置灰 §3.10）。行为与拆分前逐字一致。
 */
import { ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handovers } from '@/api/nursing';
import type { ShiftHandoverVO } from '@/api/nursing';
import { surfaceBizError, todayString } from '../wardBoardShared';

/** 交接班参数对象（病区/班次上下文与交班人锚点经底座/视图注入） */
export interface UseHandoverOptions {
  /** 当前病区代码（交接班归属病区），来自 useWardContext.wardId */
  wardId: Ref<string>;
  /** 当前班次（生成交接班的班次上下文），来自 useWardContext.shiftCode */
  shiftCode: Ref<string>;
  /** 当班护士名（交接班确认回显的交班人锚点），来自 auth store 派生 */
  operatorName: ComputedRef<string>;
}

/** 初始化交接班面（每组件实例独立状态，仅 setup 同步调用） */
export function useHandover(options: UseHandoverOptions) {
  const handover = ref<ShiftHandoverVO | null>(null);
  const handoverLoading = ref(false);
  const generating = ref(false);
  const completingHandover = ref(false);
  /** 接班护士工号（完成交接必填入参） */
  const incomingNurseId = ref('');

  async function loadHandoverOfDay(): Promise<void> {
    handoverLoading.value = true;
    try {
      const rows = await handovers.list({ wardId: options.wardId.value, date: todayString() });
      handover.value = rows.length > 0 ? (rows[rows.length - 1] ?? null) : null;
    } catch {
      // 失败弹错归响应拦截器
    } finally {
      handoverLoading.value = false;
    }
  }

  /** 生成交接班（SBAR 自动汇总；本班次已存在时后端幂等返回当日材料） */
  async function onGenerateHandover(): Promise<void> {
    if (generating.value) {
      return;
    }
    generating.value = true;
    try {
      handover.value = await handovers.generate({
        wardId: options.wardId.value,
        shiftCode: options.shiftCode.value,
      });
      void ElMessage.success('交接班材料已生成');
    } catch (error) {
      surfaceBizError(error);
    } finally {
      generating.value = false;
    }
  }

  /** 完成交接双签（中档确认带回显「交班 X → 接班 Y」；DRAFT 可点 / COMPLETED 置灰 §3.10） */
  async function onCompleteHandover(): Promise<void> {
    if (completingHandover.value || handover.value === null) {
      return;
    }
    if (incomingNurseId.value.trim() === '') {
      void ElMessage.warning('请填写接班护士工号');
      return;
    }
    try {
      await ElMessageBox.confirm(
        `交班 ${options.operatorName.value} → 接班 ${incomingNurseId.value.trim()}，确认完成交接？`,
        '完成交接确认',
        { confirmButtonText: '确认完成交接', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    completingHandover.value = true;
    try {
      handover.value = await handovers.complete(handover.value.handoverNo ?? '', {
        incomingNurseId: incomingNurseId.value.trim(),
      });
      void ElMessage.success('交接已完成');
    } catch (error) {
      surfaceBizError(error);
    } finally {
      completingHandover.value = false;
    }
  }

  return {
    handover,
    handoverLoading,
    generating,
    completingHandover,
    incomingNurseId,
    loadHandoverOfDay,
    onGenerateHandover,
    onCompleteHandover,
  };
}
