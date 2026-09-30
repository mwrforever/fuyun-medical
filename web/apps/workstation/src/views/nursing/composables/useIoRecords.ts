/**
 * 病区看板作业面⑤后半：出入量明细快录（WardBoardView 巨型脚本随迁，EX-47 拆分）。
 * ⑥ 日行值的生产入口（quantity string 透传零运算）；换患者草稿复位（BUG-15 既有修复
 * ef2bc7d 随迁保持：未提交明细不得跨患者滞留）。
 */
import { ref } from 'vue';
import type { ComputedRef } from 'vue';
import { ElMessage } from 'element-plus';
import { ioRecords } from '@/api/nursing';
import type { WardPatientDetailVO } from '@/api/nursing';
import { surfaceBizError } from '@/utils/bizError';

/** 出入量类型词表（IoType 两值：入量/出量；快录表单下拉与展示共用） */
export const IO_TYPE_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'INTAKE', label: '入量' },
  { code: 'OUTPUT', label: '出量' },
];

/** 出入量快录参数对象（患者上下文经卡墙底座注入） */
export interface UseIoRecordsOptions {
  /** 选中患者详情（未选=undefined：提交前置拦截），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
}

/** 初始化出入量面（每组件实例独立状态，仅 setup 同步调用） */
export function useIoRecords(options: UseIoRecordsOptions) {
  const ioVisible = ref(false);
  const ioForm = ref({ ioType: 'INTAKE', itemCode: '', quantity: '', unit: 'ml' });
  const ioRecording = ref(false);

  /** 换患者草稿复位（BUG-15 既有口径 ef2bc7d 随迁：出入量明细不跨患者滞留） */
  function resetDraft(): void {
    ioForm.value = { ioType: 'INTAKE', itemCode: '', quantity: '', unit: 'ml' };
  }

  async function onCreateIoRecord(): Promise<void> {
    if (ioRecording.value) {
      return;
    }
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.visitId) {
      void ElMessage.warning('请先从床位卡墙选择患者');
      return;
    }
    if (ioForm.value.itemCode.trim() === '') {
      void ElMessage.warning('请填写项目编码');
      return;
    }
    if (!/^\d+(\.\d)?$/.test(ioForm.value.quantity.trim())) {
      void ElMessage.warning('数量应为数值（最多一位小数）');
      return;
    }
    ioRecording.value = true;
    try {
      await ioRecords.create({
        visitId: detail.visitId,
        ioType: ioForm.value.ioType,
        itemCode: ioForm.value.itemCode.trim(),
        quantity: ioForm.value.quantity.trim(),
        unit: ioForm.value.unit.trim() === '' ? undefined : ioForm.value.unit.trim(),
        source: 'MANUAL',
      });
      void ElMessage.success('出入量明细已记录');
      ioForm.value = { ioType: 'INTAKE', itemCode: '', quantity: '', unit: 'ml' };
    } catch (error) {
      surfaceBizError(error);
    } finally {
      ioRecording.value = false;
    }
  }

  return { ioVisible, ioForm, ioRecording, resetDraft, onCreateIoRecord };
}
