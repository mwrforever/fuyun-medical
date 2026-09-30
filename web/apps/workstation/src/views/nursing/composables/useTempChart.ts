/**
 * 病区看板作业面⑥：体温单渲染区（WardBoardView 巨型脚本随迁，EX-47 拆分）。月页翻页
 * （早于入院月禁翻 §5.8）+ 月页数据加载（chart 月页与 vital-signs 月窗并行）+ 渲染模型
 * （tempChart.ts 纯函数 computed 缓存）。承载 EX-45（FE-A1-04）竞态守卫：发起时锚定
 * 当前选中患者，回包时已切换患者则整包丢弃——旧患者慢回包不得覆盖新患者的月页。
 */
import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { chart, vitalSigns } from '@/api/nursing';
import type { TemperatureChartVO, VitalSignVO, WardPatientDetailVO } from '@/api/nursing';
import { buildTempChart } from '../tempChart';

/** 体温单参数对象（患者上下文经卡墙底座注入） */
export interface UseTempChartOptions {
  /** 选中患者详情（未选=undefined：清空月页），来自 useWardContext.selectedDetail */
  selectedDetail: ComputedRef<WardPatientDetailVO | undefined>;
  /** 选中患者 visitId（EX-45 竞态守卫锚点），来自 useWardContext.selectedVisitId */
  selectedVisitId: Ref<string | null>;
}

/** 初始化体温单面（每组件实例独立状态，仅 setup 同步调用） */
export function useTempChart(options: UseTempChartOptions) {
  /** 当前月页键（yyyy-MM） */
  const chartMonth = ref(
    `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}`,
  );
  const chartData = ref<TemperatureChartVO | null>(null);
  const chartLoading = ref(false);
  /** 月页体征值行（体温/脉搏数值经 vitalRef 关联补齐的取值来源） */
  const monthVitals = ref<VitalSignVO[]>([]);

  /** 月页起止 ISO（vital-signs 查询窗口） */
  function monthWindow(month: string): { from: string; to: string } {
    const [year, mon] = month.split('-').map((part) => Number(part));
    const from = new Date(year, mon - 1, 1);
    const to = new Date(year, mon, 1);
    return { from: from.toISOString(), to: to.toISOString() };
  }

  /** 月键加减一月 */
  function shiftMonth(month: string, delta: number): string {
    const [year, mon] = month.split('-').map((part) => Number(part));
    const date = new Date(year, mon - 1 + delta, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  }

  /** 早于入院月的上月按钮禁用（§5.8 越界禁用） */
  const prevMonthDisabled = computed(() => {
    const admittedAt = options.selectedDetail.value?.admittedAt;
    if (!admittedAt) {
      return false;
    }
    const admittedMonth = admittedAt.slice(0, 7);
    return chartMonth.value <= admittedMonth;
  });

  async function loadChart(): Promise<void> {
    const detail = options.selectedDetail.value;
    if (detail === undefined || !detail.visitId || !detail.patientId) {
      chartData.value = null;
      monthVitals.value = [];
      return;
    }
    chartLoading.value = true;
    try {
      // 发起时锚定当前选中患者：回包前再切换患者即形成在途竞态（EX-45/FE-A1-04）
      const visitAtRequest = options.selectedVisitId.value;
      const range = monthWindow(chartMonth.value);
      const [chartPage, vitals] = await Promise.all([
        chart.query(detail.visitId, chartMonth.value),
        vitalSigns.list({ patientId: String(detail.patientId), from: range.from, to: range.to }),
      ]);
      // 过期回包丢弃：旧患者慢回包晚到不得覆盖新患者的月页
      if (options.selectedVisitId.value !== visitAtRequest) {
        return;
      }
      chartData.value = chartPage;
      monthVitals.value = vitals;
    } catch {
      // 失败弹错归响应拦截器；驻留旧月页
    } finally {
      chartLoading.value = false;
    }
  }

  function onMonthChange(delta: number): void {
    if (delta < 0 && prevMonthDisabled.value) {
      return;
    }
    chartMonth.value = shiftMonth(chartMonth.value, delta);
    void loadChart();
  }

  /** 体温单渲染模型（纯函数 computed 缓存，§7 单月页节点预算内） */
  const chartModel = computed(() =>
    buildTempChart(chartData.value, monthVitals.value, chartMonth.value),
  );

  /** 日行值单元格取值（按天 × 行键） */
  function dailyCellValue(
    day: number,
    rowKey: string,
  ): { text: string; ruleClass?: string } | undefined {
    const cell = chartModel.value.dailyCells.find(
      (item) => item.day === day && item.rowKey === rowKey,
    );
    return cell === undefined ? undefined : { text: cell.valueText, ruleClass: cell.ruleClass };
  }

  return {
    chartMonth,
    chartData,
    chartLoading,
    monthVitals,
    prevMonthDisabled,
    loadChart,
    onMonthChange,
    chartModel,
    dailyCellValue,
  };
}
