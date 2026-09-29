/**
 * 病区看板作业面③：患者详情面板最新体征行（WardBoardView 巨型脚本随迁，EX-47 拆分）。
 * 前端另调 GET /vital-signs 近 24h 组装末次值（简报冻结口径）；未选患者/无 patientId 时
 * 清空展示。承载 EX-45（FE-A1-04）竞态守卫：发起时锚定当前选中患者，回包时已切换患者
 * 则整包丢弃——旧患者慢回包不得覆盖新患者的体征简报。
 */
import { ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { vitalSigns } from '@/api/nursing';
import type { VitalSignVO, WardPatientDetailVO } from '@/api/nursing';

/** 最新体征参数对象（患者上下文经卡墙底座注入） */
export interface UseLatestVitalsOptions {
  /** 选中患者详情（未选=undefined），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
  /** 选中患者 visitId（EX-45 竞态守卫锚点），来自 useWardContext.selectedVisitId */
  selectedVisitId: Ref<string | null>;
}

/** 初始化最新体征面（每组件实例独立状态，仅 setup 同步调用） */
export function useLatestVitals(options: UseLatestVitalsOptions) {
  /** 最新体征行（前端另调 GET /vital-signs 近 24h 组装，简报冻结口径） */
  const latestVitals = ref<VitalSignVO | null>(null);
  const latestVitalsLoading = ref(false);

  async function loadLatestVitals(): Promise<void> {
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.patientId) {
      latestVitals.value = null;
      return;
    }
    latestVitalsLoading.value = true;
    try {
      // 发起时锚定当前选中患者：回包前再切换患者即形成在途竞态（EX-45/FE-A1-04）
      const visitAtRequest = options.selectedVisitId.value;
      const from = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
      const to = new Date().toISOString();
      const rows = await vitalSigns.list({ patientId: String(detail.patientId), from, to });
      // 过期回包丢弃：旧患者慢回包晚到不得覆盖新患者的体征简报
      if (options.selectedVisitId.value !== visitAtRequest) {
        return;
      }
      latestVitals.value = rows.length > 0 ? (rows[rows.length - 1] ?? null) : null;
    } catch {
      // 失败弹错归响应拦截器；驻留旧体征
    } finally {
      latestVitalsLoading.value = false;
    }
  }

  return { latestVitals, latestVitalsLoading, loadLatestVitals };
}

/** 体温部位单字符号（详情面板最新体征行 ×/●/〇 直显） */
export function tempSiteMark(site: string | undefined): string {
  if (site === 'AXILLARY') {
    return '×';
  }
  if (site === 'ORAL') {
    return '●';
  }
  if (site === 'RECTAL') {
    return '〇';
  }
  return '';
}
