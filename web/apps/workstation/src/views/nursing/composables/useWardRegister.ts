/**
 * 病区看板作业面①：入区登记弹窗（WardBoardView 巨型脚本随迁，EX-47 拆分）。表单态 +
 * 显式校验（缺项/visit 号格式非法零出网）+ 提交在途守卫；成功关窗、复位表单并触发病区
 * 主加载重跑（卡墙刷新）。行为与拆分前逐字一致。
 */
import { ref } from 'vue';
import type { Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { wardPatients } from '@/api/nursing';
import { surfaceBizError } from '../wardBoardShared';

/** 入区登记参数对象（病区上下文与主加载重跑经卡墙底座注入） */
export interface UseWardRegisterOptions {
  /** 当前病区代码（登记落区归属），来自 useWardContext.wardId */
  wardId: Ref<string>;
  /** 病区主加载重跑（成功关窗后刷新卡墙），来自 useWardContext.loadWard */
  reloadWard: () => Promise<void>;
}

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，§3.3 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/** 初始化入区登记面（每组件实例独立状态，仅 setup 同步调用） */
export function useWardRegister(options: UseWardRegisterOptions) {
  /** 入区登记弹窗态 */
  const registerVisible = ref(false);
  const registering = ref(false);
  const registerForm = ref({
    patientId: '',
    visitId: '',
    patientName: '',
    bedNo: '',
    nursingLevel: 'NORMAL',
    conditionTags: [] as string[],
  });

  /** 重置登记表单（弹窗打开/提交成功后） */
  function resetRegisterForm(): void {
    registerForm.value = {
      patientId: '',
      visitId: '',
      patientName: '',
      bedNo: '',
      nursingLevel: 'NORMAL',
      conditionTags: [],
    };
  }

  /** 入区登记提交：显式校验（缺项/格式非法零出网）→ 出网 → 成功关窗重载卡墙。 */
  async function onRegister(): Promise<void> {
    if (registering.value) {
      return;
    }
    const form = registerForm.value;
    if (!/^\d+$/.test(form.patientId.trim())) {
      void ElMessage.warning('患者 ID 应为数字编号，请核对住院登记');
      return;
    }
    if (!VISIT_NO_PATTERN.test(form.visitId.trim())) {
      void ElMessage.warning('visit 号应以 I 开头共 14 位（I+日期+流水），请核对入区单');
      return;
    }
    if (form.patientName.trim() === '') {
      void ElMessage.warning('请填写患者姓名');
      return;
    }
    if (form.bedNo.trim() === '') {
      void ElMessage.warning('请填写床位号');
      return;
    }
    if (form.nursingLevel === '') {
      void ElMessage.warning('请选择护理级别');
      return;
    }
    registering.value = true;
    try {
      await wardPatients.register({
        visitId: form.visitId.trim(),
        patientId: form.patientId.trim(),
        wardId: options.wardId.value,
        bedNo: form.bedNo.trim(),
        patientName: form.patientName.trim(),
        nursingLevel: form.nursingLevel,
        conditionTags: form.conditionTags.join(','),
      });
      void ElMessage.success('入区登记完成');
      registerVisible.value = false;
      resetRegisterForm();
      await options.reloadWard();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      registering.value = false;
    }
  }

  return { registerVisible, registering, registerForm, resetRegisterForm, onRegister };
}
