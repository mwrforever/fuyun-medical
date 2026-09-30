/**
 * 病区看板作业面⑦：护理评估（WardBoardView 巨型脚本随迁，EX-47 拆分）。量表定义加载与
 * 切换（换量表清作答）、打分提交（未选患者/未选量表/存在未答条目前置拦截零出网）、结果条
 * 与最近历史。承载两处修复：①换患者草稿复位扩展到结果条（assessResult 此前不随
 * selectPatient 复位，前患者「高风险」判级滞留新患者名下=误导判级，BUG-15 同类口径）；
 * ②EX-45（FE-A1-04）竞态守卫——历史加载回包比对当前选中患者，过期回包丢弃。
 */
import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { assessments } from '@/api/nursing';
import type { NursingAssessmentVO, ScaleDefinitionVO, WardPatientDetailVO } from '@/api/nursing';
import { surfaceBizError } from '@/utils/bizError';

/** 量表中文词表（scaleType 五值展示映射） */
export const SCALE_TYPE_LABELS: Record<string, string> = {
  BRADEN: 'Braden 压疮',
  MORSE: 'Morse 跌倒',
  NRS: 'NRS 疼痛',
  BARTHEL: 'Barthel 自理',
  MEWS: 'MEWS 早期预警',
};

/** 评估风险判级映射（§3.11：HIGH/MEDIUM/LOW 三档 tag 文案） */
export const RISK_LEVEL_META: Record<string, { type: 'danger' | 'warning' | 'success'; text: string }> = {
  HIGH: { type: 'danger', text: '高风险' },
  MEDIUM: { type: 'warning', text: '中风险' },
  LOW: { type: 'success', text: '低风险' },
};

/** 护理评估参数对象（患者上下文经卡墙底座注入） */
export interface UseNursingAssessmentOptions {
  /** 选中患者详情（未选=undefined：提交拦截/历史清空），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
  /** 选中患者 visitId（EX-45 竞态守卫锚点），来自 useWardContext.selectedVisitId */
  selectedVisitId: Ref<string | null>;
}

/** 初始化评估面（每组件实例独立状态，仅 setup 同步调用） */
export function useNursingAssessment(options: UseNursingAssessmentOptions) {
  const scaleList = ref<ScaleDefinitionVO[]>([]);
  const scaleType = ref('');
  /** 当前量表条目定义（条目码/名称/选项分值对齐展开） */
  const currentScale = computed<ScaleDefinitionVO | undefined>(() =>
    scaleList.value.find((scale) => scale.scaleType === scaleType.value),
  );
  /** 打分表答案（条目码 → 分值） */
  const scaleAnswers = ref<Record<string, number>>({});
  const assessing = ref(false);
  /** 最近一次提交结果（结果条展示：总分 + 判级 + 时点） */
  const assessResult = ref<NursingAssessmentVO | null>(null);
  const assessHistory = ref<NursingAssessmentVO[]>([]);

  async function loadScales(): Promise<void> {
    try {
      scaleList.value = await assessments.scales();
      if (scaleList.value.length > 0 && scaleType.value === '') {
        scaleType.value = scaleList.value[0]?.scaleType ?? '';
      }
    } catch {
      // 失败弹错归响应拦截器；评估区块空量表态
    }
  }

  function onScaleChange(): void {
    scaleAnswers.value = {};
    assessResult.value = null;
  }

  /** 换患者草稿复位（量表作答清空 + 结果条残留清理：前患者判级不得滞留新患者名下） */
  function resetDraft(): void {
    scaleAnswers.value = {};
    assessResult.value = null;
  }

  /** 评估提交：未选患者/未选量表/存在未答条目前置拦截零出网 → 出网 → 结果条 + 历史刷新。 */
  async function onAssess(): Promise<void> {
    if (assessing.value) {
      return;
    }
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.visitId) {
      void ElMessage.warning('请先从床位卡墙选择患者');
      return;
    }
    const scale = currentScale.value;
    if (scale === undefined) {
      void ElMessage.warning('请选择评估量表');
      return;
    }
    const unanswered = (scale.itemCodes ?? []).filter(
      (code) => scaleAnswers.value[code] === undefined,
    );
    if (unanswered.length > 0) {
      void ElMessage.warning('存在未作答条目，请完成全部条目后提交');
      return;
    }
    assessing.value = true;
    try {
      assessResult.value = await assessments.create({
        visitId: detail.visitId,
        scaleType: scale.scaleType ?? '',
        answers: { ...scaleAnswers.value },
        assessedAt: new Date().toISOString(),
      });
      void ElMessage.success('评估已提交');
      await loadAssessmentHistory();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      assessing.value = false;
    }
  }

  async function loadAssessmentHistory(): Promise<void> {
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.visitId) {
      assessHistory.value = [];
      return;
    }
    try {
      // 发起时锚定当前选中患者：回包前再切换患者即形成在途竞态（EX-45/FE-A1-04）
      const visitAtRequest = options.selectedVisitId.value;
      const rows = await assessments.list({ visitId: detail.visitId });
      // 过期回包丢弃：旧患者慢回包晚到不得覆盖新患者的评估历史
      if (options.selectedVisitId.value !== visitAtRequest) {
        return;
      }
      assessHistory.value = rows;
    } catch {
      // 失败弹错归响应拦截器
    }
  }

  return {
    scaleList,
    scaleType,
    currentScale,
    scaleAnswers,
    assessing,
    assessResult,
    assessHistory,
    loadScales,
    onScaleChange,
    resetDraft,
    onAssess,
    loadAssessmentHistory,
  };
}
