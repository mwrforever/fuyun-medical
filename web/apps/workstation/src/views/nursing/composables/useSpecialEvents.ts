/**
 * 病区看板作业面⑥后半：特殊事件录入（WardBoardView 巨型脚本随迁，EX-47 拆分）。事件
 * 类型 + 备注草稿与提交（时点由后端服务器时间承载 GC25），成功清备注并刷新体温单。
 * 承载 BUG-15 同类修复：换患者时草稿复位（specialEventType/specialEventRemark 此前
 * 不随 selectPatient 复位，残留草稿续提即把前患者的备注事件记到新患者名下——医疗差错级）。
 */
import { ref } from 'vue';
import type { ComputedRef } from 'vue';
import { ElMessage } from 'element-plus';
import { chart } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { surfaceBizError } from '@/utils/bizError';

/** 特殊事件参数对象（患者上下文经卡墙底座注入；记录成功刷新经体温单面注入） */
export interface UseSpecialEventsOptions {
  /** 选中患者详情（未选=undefined：提交前置拦截），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
  /** 记录成功后的体温单刷新（新事件竖线入图），来自 useTempChart.loadChart */
  onEventRecorded: () => Promise<void>;
}

/** 初始化特殊事件面（每组件实例独立状态，仅 setup 同步调用） */
export function useSpecialEvents(options: UseSpecialEventsOptions) {
  const specialEventType = ref('ADMISSION');
  const specialEventRemark = ref('');
  const specialEventRecording = ref(false);

  /** 换患者草稿复位（BUG-15 同类：事件类型/备注不跨患者滞留——前患者备注记到新患者
   * 名下属医疗差错，口径同体征/量表/出入量三处既有复位） */
  function resetDraft(): void {
    specialEventType.value = 'ADMISSION';
    specialEventRemark.value = '';
  }

  /** 特殊事件记录（§3.8：事件类型 + 备注；时点由后端服务器时间承载 GC25） */
  async function onAddSpecialEvent(): Promise<void> {
    if (specialEventRecording.value) {
      return;
    }
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.visitId) {
      void ElMessage.warning('请先从床位卡墙选择患者');
      return;
    }
    specialEventRecording.value = true;
    try {
      await chart.addSpecialEvent(detail.visitId, {
        eventType: specialEventType.value,
        remark: specialEventRemark.value.trim() === '' ? undefined : specialEventRemark.value.trim(),
      });
      void ElMessage.success('特殊事件已记录');
      specialEventRemark.value = '';
      await options.onEventRecorded();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      specialEventRecording.value = false;
    }
  }

  return { specialEventType, specialEventRemark, specialEventRecording, resetDraft, onAddSpecialEvent };
}
