/**
 * 病区看板作业面⑤：体征录入 + 待复核（WardBoardView 巨型脚本随迁，EX-47 拆分）。
 * 体征表单文本承载显式校验（整数/小数一律经正则+范围双验后数值化，禁裸 parse，文案冻结
 * 于设计文档 §3.7 表）+ 录入在途守卫 + 提交成功刷新体温单/简报（经回调注入）；待复核
 * 列表加载与确认/驳回（中档确认带回显，成功该行移除——spec 冻结语义）。
 * 行为与拆分前逐字一致。
 */
import { ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
import { vitalSigns } from '@/api/nursing';
import type { VitalSignVO, WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { formatTime, surfaceBizError } from '../wardBoardShared';

/** 体征录入/待复核参数对象（患者上下文经卡墙底座注入；提交成功刷新经体温单面注入） */
export interface UseVitalSignsOptions {
  /** 选中患者一览行（出网取 visitId），来自 useWardContext.selectedPatient */
  selectedPatient: ComputedRef<WardPatientVO | undefined>;
  /** 选中患者详情（面板展示与出网前置判定），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
  /** 当前病区代码（待复核清单范围），来自 useWardContext.wardId */
  wardId: Ref<string>;
  /** 录入成功后的联动刷新（体温单 + 最新体征简报），时序与拆分前一致串行执行 */
  onRecorded: () => Promise<void>;
}

/** 初始化体征面（每组件实例独立状态，仅 setup 同步调用） */
export function useVitalSigns(options: UseVitalSignsOptions) {
  /** 体征录入表单（文本承载：整数/小数一律经正则+范围双验后数值化，禁裸 parse） */
  const vitalForm = ref({
    temperature: '',
    tempSite: 'AXILLARY',
    pulse: '',
    respiration: '',
    systolicBp: '',
    diastolicBp: '',
    spo2: '',
    weight: '',
    height: '',
    painScore: '',
  });
  const recording = ref(false);

  /** 体征表单复位（换患者上下文切换与提交成功后共用——草稿不跨患者滞留） */
  function resetVitalForm(): void {
    vitalForm.value = {
      temperature: '',
      tempSite: 'AXILLARY',
      pulse: '',
      respiration: '',
      systolicBp: '',
      diastolicBp: '',
      spo2: '',
      weight: '',
      height: '',
      painScore: '',
    };
  }

  /** 整数字段显式校验（纯数字正则 + 范围判定） */
  function isValidInt(raw: string, min: number, max: number): boolean {
    if (!/^\d+$/.test(raw)) {
      return false;
    }
    const value = Number(raw);
    return Number.isInteger(value) && value >= min && value <= max;
  }

  /** 一位小数字段显式校验（一到三位整数 + 可选一位小数 + 范围判定） */
  function isValidDecimal(raw: string, min: number, max: number): boolean {
    if (!/^\d{1,3}(\.\d)?$/.test(raw)) {
      return false;
    }
    const value = Number(raw);
    return value >= min && value <= max;
  }

  /** 提示并返回 false（校验器短路出口） */
  function warn(message: string): boolean {
    void ElMessage.warning(message);
    return false;
  }

  /** 各字段校验器（文案冻结于设计文档 §3.7 表） */
  const VITAL_VALIDATORS: Record<string, () => boolean> = {
    temperature: () =>
      vitalForm.value.temperature === '' ||
      isValidDecimal(vitalForm.value.temperature, 35, 42) ||
      warn('体温应为 35.0–42.0 的数值（如 36.5），请重新输入'),
    pulse: () =>
      vitalForm.value.pulse === '' ||
      isValidInt(vitalForm.value.pulse, 20, 250) ||
      warn('脉搏应为 20–250 的整数'),
    respiration: () =>
      vitalForm.value.respiration === '' ||
      isValidInt(vitalForm.value.respiration, 5, 60) ||
      warn('呼吸应为 5–60 的整数'),
    systolicBp: () =>
      vitalForm.value.systolicBp === '' ||
      isValidInt(vitalForm.value.systolicBp, 60, 250) ||
      warn('收缩压应为 60–250 的整数'),
    diastolicBp: () =>
      vitalForm.value.diastolicBp === '' ||
      isValidInt(vitalForm.value.diastolicBp, 30, 180) ||
      warn('舒张压应为 30–180 的整数'),
    spo2: () =>
      vitalForm.value.spo2 === '' ||
      isValidInt(vitalForm.value.spo2, 50, 100) ||
      warn('血氧应为 50–100 的整数'),
    weight: () =>
      vitalForm.value.weight === '' ||
      isValidDecimal(vitalForm.value.weight, 20, 300) ||
      warn('体重应为 20–300 的数值（kg）'),
    height: () =>
      vitalForm.value.height === '' ||
      isValidInt(vitalForm.value.height, 30, 250) ||
      warn('身高应为 30–250 的整数（cm）'),
    painScore: () =>
      vitalForm.value.painScore === '' ||
      isValidInt(vitalForm.value.painScore, 0, 10) ||
      warn('疼痛评分应为 0–10 的整数'),
  };

  /** 字段 change 校验（@change 触发单字段；提交时全字段双触发兜底） */
  function onVitalFieldChange(field: string): void {
    VITAL_VALIDATORS[field]?.();
  }

  /** 数值化出参（校验通过后按类型安全转换；体温/体重保留一位小数语义由字符串直转承载） */
  function toNumberOrNull(raw: string): number | undefined {
    return raw === '' ? undefined : Number(raw);
  }

  /** 体征录入提交：全字段显式校验 → 至少一项 → 出网 → 成功清表单并刷新体温单。 */
  async function onRecordVitals(): Promise<void> {
    if (recording.value) {
      return;
    }
    const patient = options.selectedPatient.value;
    if (patient === null || patient === undefined) {
      void ElMessage.warning('请先从床位卡墙选择患者');
      return;
    }
    const form = vitalForm.value;
    const allValid = Object.values(VITAL_VALIDATORS).every((validate) => validate());
    if (!allValid) {
      return;
    }
    if (
      form.temperature === '' &&
      form.pulse === '' &&
      form.respiration === '' &&
      form.systolicBp === '' &&
      form.diastolicBp === '' &&
      form.spo2 === '' &&
      form.weight === '' &&
      form.height === '' &&
      form.painScore === ''
    ) {
      void ElMessage.warning('请至少录入一项体征数据');
      return;
    }
    recording.value = true;
    try {
      await vitalSigns.record({
        visitId: patient.visitId ?? '',
        source: 'MANUAL',
        temperature: toNumberOrNull(form.temperature),
        tempSite: form.temperature === '' ? undefined : form.tempSite,
        pulse: toNumberOrNull(form.pulse),
        respiration: toNumberOrNull(form.respiration),
        systolicBp: toNumberOrNull(form.systolicBp),
        diastolicBp: toNumberOrNull(form.diastolicBp),
        spo2: toNumberOrNull(form.spo2),
        weight: toNumberOrNull(form.weight),
        height: toNumberOrNull(form.height),
        painScore: toNumberOrNull(form.painScore),
      });
      void ElMessage.success('体征已录入');
      // 提交成功后复位表单（与换患者复位同口径）
      resetVitalForm();
      await options.onRecorded();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      recording.value = false;
    }
  }

  /* ---------- 待复核列表 ---------- */
  const pendingList = ref<VitalSignVO[]>([]);
  const pendingLoading = ref(false);
  const confirmingId = ref<string | null>(null);

  async function loadPendingReview(): Promise<void> {
    pendingLoading.value = true;
    try {
      pendingList.value = await vitalSigns.pendingReview(options.wardId.value);
    } catch {
      // 失败弹错归响应拦截器
    } finally {
      pendingLoading.value = false;
    }
  }

  /** 待复核确认（中档确认带回显「确认将 … 入体温单？」；成功该行移除，spec 冻结语义） */
  async function onConfirmVital(row: VitalSignVO): Promise<void> {
    if (confirmingId.value !== null) {
      return;
    }
    try {
      await ElMessageBox.confirm(
        `确认将 ${formatTime(row.measuredAt)} 体温 ${row.temperature ?? '—'} 入体温单？`,
        '体征复核确认',
        { confirmButtonText: '确认', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    confirmingId.value = String(row.id ?? '');
    try {
      await vitalSigns.confirm(String(row.id ?? ''));
      void ElMessage.success('已确认入体温单');
      pendingList.value = pendingList.value.filter((item) => String(item.id) !== String(row.id));
    } catch (error) {
      surfaceBizError(error);
    } finally {
      confirmingId.value = null;
    }
  }

  /** 待复核驳回（中档确认 + 必填原因） */
  async function onRejectVital(row: VitalSignVO): Promise<void> {
    if (confirmingId.value !== null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `驳回 ${formatTime(row.measuredAt)} 体温 ${row.temperature ?? '—'} 的体征数据`,
        '体征驳回确认',
        {
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消',
          inputPlaceholder: '驳回原因（必填）',
          inputValidator: (input: string) => (input.trim() === '' ? '驳回原因不能为空' : true),
        },
      );
      confirmingId.value = String(row.id ?? '');
      await vitalSigns.reject(String(row.id ?? ''), { reason: value.trim() });
      void ElMessage.success('已驳回该体征');
      pendingList.value = pendingList.value.filter((item) => String(item.id) !== String(row.id));
    } catch (error) {
      if (!axios.isAxiosError(error)) {
        // 用户取消弹窗：静默返回（ElMessageBox 取消抛非 Axios 的 reject('cancel')）
        return;
      }
      surfaceBizError(error);
    } finally {
      confirmingId.value = null;
    }
  }

  return {
    vitalForm,
    recording,
    resetVitalForm,
    onVitalFieldChange,
    onRecordVitals,
    pendingList,
    pendingLoading,
    confirmingId,
    loadPendingReview,
    onConfirmVital,
    onRejectVital,
  };
}
