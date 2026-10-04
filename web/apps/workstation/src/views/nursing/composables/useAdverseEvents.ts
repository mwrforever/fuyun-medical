/**
 * 护理不良事件 composable（PR-3 Task 14，AdverseEventView 业务面）：分页列表（类别/状态
 * 筛选，usePagedList 三段式范式）+ 上报表单（类别/分级/等级/病区/时点/经过必填显式校验
 * 前置零出网，匿名开关透传——非惩罚通道口径）+ 处理/RCA 关闭/退回三操作（操作人留痕
 * 锚点=会话 userId 经 getOperatorId 注入，弹窗文案同步提示留痕人）。
 */
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adverseEvents } from '@/api/nursing';
import type { AdverseEventVO } from '@/api/nursing';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';

/** 上报表单模型（A.7-3 表单模型 ≠ 请求类型：可选字段以空串承载，转换边界在 onReport） */
export interface AdverseEventFormModel {
  category: string;
  severityClass: string;
  severityGrade: string;
  wardId: string;
  occurredAt: string;
  eventSummary: string;
  handlingNote: string;
  visitId: string;
  isAnonymous: boolean;
}

/** 上报表单初始态（病区缺省 W01 演示病区——冻结 REST 面无病区清单端点口径） */
const EMPTY_FORM: AdverseEventFormModel = {
  category: '',
  severityClass: '',
  severityGrade: '',
  wardId: 'W01',
  occurredAt: '',
  eventSummary: '',
  handlingNote: '',
  visitId: '',
  isAnonymous: false,
};

/** 不良事件参数对象（操作人取值器经视图注入，会话惰性取值） */
export interface UseAdverseEventsOptions {
  /** 操作人 userId 取值器（处理/关闭/退回留痕锚点）；来源：auth store 会话用户 */
  getOperatorId: () => string;
}

/** 初始化不良事件面（每组件实例独立状态，仅 setup 同步调用） */
export function useAdverseEvents(options: UseAdverseEventsOptions) {
  /* ==================== 分页列表（筛选 + usePagedList 三段式） ==================== */
  const categoryFilter = ref('');
  const statusFilter = ref('');

  const {
    rows,
    total,
    loading: listLoading,
    currentPage,
    pageSize,
    fetch,
    search,
    goToPage,
  } = usePagedList({
    params: () => ({
      category: categoryFilter.value === '' ? undefined : categoryFilter.value,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
    }),
    fetcher: (query) => adverseEvents.list(query),
    pageSize: 20,
  });

  /* ==================== 上报表单（必填显式校验前置零出网） ==================== */
  const reportVisible = ref(false);
  const reporting = ref(false);
  const reportForm = ref<AdverseEventFormModel>({ ...EMPTY_FORM });

  /** 打开上报弹窗（草稿复位——未提交数据不得滞留到下次上报） */
  function openReport(): void {
    reportForm.value = { ...EMPTY_FORM };
    reportVisible.value = true;
  }

  /**
   * 提交上报：必填面逐项显式校验（零出网，服务端同语义兜底）→ 出网（occurredAt 北京
   * 钟面钉面转 ISO 带时区偏移）→ 关窗回第一页重拉。入口在途早退守卫防双击重复上报。
   */
  async function onReport(): Promise<void> {
    if (reporting.value) {
      return;
    }
    const form = reportForm.value;
    if (form.category === '') {
      void ElMessage.warning('请选择事件类别（必填）');
      return;
    }
    if (form.severityClass === '') {
      void ElMessage.warning('请选择严重度分级（必填）');
      return;
    }
    if (form.severityGrade === '') {
      void ElMessage.warning('请选择严重度等级（必填）');
      return;
    }
    if (form.wardId === '') {
      void ElMessage.warning('请选择发生病区（必填）');
      return;
    }
    if (form.occurredAt === '') {
      void ElMessage.warning('请填写发生时点（必填）');
      return;
    }
    if (form.eventSummary.trim() === '') {
      void ElMessage.warning('请填写事件经过（必填）');
      return;
    }
    reporting.value = true;
    try {
      const created = await adverseEvents.report({
        category: form.category,
        severityClass: form.severityClass,
        severityGrade: form.severityGrade,
        wardId: form.wardId,
        // datetime-local 本地串无时区偏移，后端 OffsetDateTime 解析必 400——W-70① 裁定该值
        // 为用户录入的北京钟面墙钟（HIS 用户面语义，不良事件 I/II 级 24h 时限计算基准），
        // 显式拼 +08:00 钉面解析后转 UTC ISO 出网（先例口径=DischargeManageView onCreate
        // 同款 +08:00 钉面——非北京客户端发生时点不随本地时区漂移）
        occurredAt: new Date(`${form.occurredAt}+08:00`).toISOString(),
        eventSummary: form.eventSummary.trim(),
        handlingNote: form.handlingNote.trim() === '' ? undefined : form.handlingNote.trim(),
        visitId: form.visitId.trim() === '' ? undefined : form.visitId.trim(),
        isAnonymous: form.isAnonymous,
      });
      void ElMessage.success(`已上报：${created.eventNo ?? ''}（非惩罚通道，感谢主动报告）`);
      reportVisible.value = false;
      await search();
    } catch (error) {
      surfaceBizError(error);
    } finally {
      reporting.value = false;
    }
  }

  /* ==================== 处理 / RCA 关闭 / 退回（操作人留痕） ==================== */
  /** 操作人判空（留痕必填；缺会话身份零出网显式拦截） */
  function requireOperatorId(): string | null {
    const operatorId = options.getOperatorId();
    if (operatorId === '') {
      void ElMessage.warning('会话缺少操作人身份，无法执行该操作（请重新登录后再试）');
      return null;
    }
    return operatorId;
  }

  /** 操作人留痕提示段（弹窗文案内明示以谁身份留痕——角色留痕提示） */
  function operatorHint(): string {
    return `本操作将以 ${options.getOperatorId() || '（未知操作人）'} 身份留痕`;
  }

  /** 处理（REPORTED→HANDLING；处置记录选填，操作人留痕） */
  async function onHandle(row: AdverseEventVO): Promise<void> {
    const operatorId = requireOperatorId();
    if (operatorId === null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `处理不良事件 ${row.eventNo ?? ''}？${operatorHint()}`,
        '事件处理',
        {
          confirmButtonText: '确认处理',
          cancelButtonText: '返回',
          inputType: 'textarea',
          inputPlaceholder: '处置记录（选填）',
        },
      );
      await adverseEvents.handle(row.eventNo ?? '', {
        handlerId: operatorId,
        handlingNote: value.trim() === '' ? undefined : value.trim(),
      });
      void ElMessage.success(`已处理：${row.eventNo ?? ''}`);
      await fetch();
    } catch (error) {
      surfaceBizError(error);
    }
  }

  /** RCA 关闭（HANDLING→CLOSED 终态；RCA 与纠正措施选填留痕） */
  async function onClose(row: AdverseEventVO): Promise<void> {
    const operatorId = requireOperatorId();
    if (operatorId === null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `关闭不良事件 ${row.eventNo ?? ''}（终态，关闭后不可再处理）？${operatorHint()}`,
        'RCA 关闭确认',
        {
          confirmButtonText: '确认关闭',
          cancelButtonText: '返回',
          inputType: 'textarea',
          inputPlaceholder: 'RCA 分析与纠正措施（选填，建议填写）',
        },
      );
      await adverseEvents.close(row.eventNo ?? '', {
        closedBy: operatorId,
        rcaNote: value.trim() === '' ? undefined : value.trim(),
      });
      void ElMessage.success(`已关闭：${row.eventNo ?? ''}`);
      await fetch();
    } catch (error) {
      surfaceBizError(error);
    }
  }

  /** 退回（上报信息不全退回补报；原因必填 + 退回人留痕） */
  async function onReturn(row: AdverseEventVO): Promise<void> {
    const operatorId = requireOperatorId();
    if (operatorId === null) {
      return;
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `退回不良事件 ${row.eventNo ?? ''} 补报？${operatorHint()}`,
        '事件退回确认',
        {
          confirmButtonText: '确认退回',
          cancelButtonText: '返回',
          inputPlaceholder: '退回原因（必填）',
          inputValidator: (input: string) => (input.trim() === '' ? '退回原因不能为空' : true),
        },
      );
      await adverseEvents.return(row.eventNo ?? '', {
        reason: value.trim(),
        returnerId: operatorId,
      });
      void ElMessage.success(`已退回：${row.eventNo ?? ''}`);
      await fetch();
    } catch (error) {
      surfaceBizError(error);
    }
  }

  return {
    // 分页列表
    rows,
    total,
    listLoading,
    currentPage,
    pageSize,
    categoryFilter,
    statusFilter,
    fetch,
    search,
    goToPage,
    // 上报表单
    reportVisible,
    reporting,
    reportForm,
    openReport,
    onReport,
    // 三操作
    onHandle,
    onClose,
    onReturn,
  };
}
