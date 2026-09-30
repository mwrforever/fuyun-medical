/**
 * 病区看板作业面①②底座：病区/班次上下文 + 床位序患者卡墙 + 全页患者选中锚点 + 出区
 * （WardBoardView 巨型脚本随迁，EX-47 拆分）。选中患者（selectedVisitId）是③⑤⑥⑦区块的
 * 患者上下文锚点；换患者时的草稿复位与患者上下文重载经入参回调注入（各作业面 composable
 * 承载，时序与拆分前逐字一致：先换锚点 → 同步复位草稿 → 异步发起上下文加载）。
 * 病区主加载（一览 → 逐床详情富化 → 四面联动重载）同样经 getWardReloads 惰性求值注入，
 * 保持「单床失败不阻塞卡墙 + 四面并行重载」的原时序。
 */
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { wardPatients, SHIFT_OPTIONS, WARD_OPTIONS } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { splitTags } from '../wardBoardShared';

/** 病情标记 → 角标类/文案映射（§3.11：娩归 brand 系） */
const CONDITION_FLAG_META: Record<string, { cls: string; text: string }> = {
  CRITICAL: { cls: 'fuy-nursing-flag--danger', text: '危' },
  SEVERE: { cls: 'fuy-nursing-flag--warning', text: '重' },
  NEW: { cls: 'fuy-nursing-flag--brand', text: '新' },
  SURGERY: { cls: 'fuy-nursing-flag--info', text: '术' },
  DELIVERY: { cls: 'fuy-nursing-flag--brand', text: '娩' },
};

/** 床旁风险标识 → 空心角标文案（§3.11：跌倒/压疮红描边空心） */
const RISK_FLAG_LABELS: Record<string, string> = {
  FALL: '跌',
  PRESSURE: '压',
  TUBE: '管',
};

/** 上下文回调参数对象（跨作业面联动的惰性注入面） */
export interface UseWardContextOptions {
  /**
   * 病区主加载完成后的联动重载集合（待复核/分配/任务/交接班），惰性求值：
   * 返回数组时各 loader 已同步并发发起，与拆分前 Promise.all([...]) 时序一致。
   * 缺省时不联动（独立单测场景）。
   */
  getWardReloads?: () => Promise<unknown>[];
  /**
   * 换患者的草稿复位回调（同步）：各作业面表单草稿不得跨患者滞留，否则续提即归档到
   * 新患者名下（医疗差错级，BUG-15 口径，同 PdaView.onIdentify）。
   */
  onPatientSwitchReset?: () => void;
  /** 换患者后的患者上下文异步加载回调（体征简报/体温单/评估历史并发发起） */
  onPatientSwitchLoad?: () => void;
}

/** 初始化卡墙底座（每组件实例独立状态，仅 setup 同步调用） */
export function useWardContext(options: UseWardContextOptions = {}) {
  /** 当前病区（会话内记忆：切换/刷新不回默认病区） */
  const wardId = ref(sessionStorage.getItem('nursing.wardId') ?? WARD_OPTIONS[0].code);
  /** 当前班次（分配与交接班的班次上下文；默认取词表首项，与病区初值同款口径） */
  const shiftCode = ref(SHIFT_OPTIONS[0].code);

  const patientList = ref<WardPatientVO[]>([]);
  const wardLoading = ref(false);
  /** 详情按 visitId 索引（卡墙姓名/角标富化 + ③ 详情面板数据源） */
  const detailMap = ref<Record<string, WardPatientDetailVO>>({});
  /** 选中患者 visitId（全页患者上下文锚点；null=未选） */
  const selectedVisitId = ref<string | null>(null);

  /** 床位序患者清单（床位号字符串升序=临床序，spec 冻结语序断言） */
  const sortedPatients = computed(() =>
    [...patientList.value].sort((a, b) => (a.bedNo ?? '').localeCompare(b.bedNo ?? '')),
  );

  /** 病区概览计数（在区/危/重，头部病情计数行） */
  const wardCounts = computed(() => {
    let critical = 0;
    let severe = 0;
    for (const patient of patientList.value) {
      const tags = splitTags(detailMap.value[patient.visitId ?? '']?.conditionTags);
      if (tags.includes('CRITICAL')) {
        critical += 1;
      }
      if (tags.includes('SEVERE')) {
        severe += 1;
      }
    }
    return { total: patientList.value.length, critical, severe };
  });

  /** 卡墙角标行（§3.4：显示优先级 过敏>危>重>风险>其余，上限 4 个 + 溢出 +N） */
  function bedFlags(detail: WardPatientDetailVO | undefined): {
    shown: Array<{ cls: string; text: string }>;
    overflow: number;
  } {
    if (detail === undefined) {
      return { shown: [], overflow: 0 };
    }
    const ordered: Array<{ cls: string; text: string; rank: number }> = [];
    if (detail.allergyFlag === true) {
      ordered.push({ cls: 'fuy-nursing-flag--danger', text: '敏', rank: 0 });
    }
    for (const tag of splitTags(detail.conditionTags)) {
      const meta = CONDITION_FLAG_META[tag];
      if (meta !== undefined) {
        ordered.push({
          cls: meta.cls,
          text: meta.text,
          rank: tag === 'CRITICAL' ? 1 : tag === 'SEVERE' ? 2 : 4,
        });
      }
    }
    for (const risk of splitTags(detail.riskFlags)) {
      const label = RISK_FLAG_LABELS[risk];
      if (label !== undefined) {
        ordered.push({ cls: 'fuy-nursing-flag--outline', text: label, rank: 3 });
      }
    }
    ordered.sort((a, b) => a.rank - b.rank);
    return {
      shown: ordered.slice(0, 4).map(({ cls, text }) => ({ cls, text })),
      overflow: Math.max(0, ordered.length - 4),
    };
  }

  /** 卡墙在途任务数（无详情时缺省 0） */
  function inFlightCount(detail: WardPatientDetailVO | undefined): number {
    return detail?.inFlightTasks?.length ?? 0;
  }

  /** 卡墙责任护士（当班分配首条 nurseId 直显；M01 姓名随 P2 组织机构对齐） */
  function dutyNurseId(detail: WardPatientDetailVO | undefined): string {
    return detail?.assignments?.[0]?.nurseId ?? '—';
  }

  /** 选中患者详情（visitId → detailMap 命中；未选/未命中=undefined） */
  const selectedDetail = computed<WardPatientDetailVO | undefined>(() =>
    selectedVisitId.value === null ? undefined : detailMap.value[selectedVisitId.value],
  );
  /** 选中患者一览行（visitId → patientList 命中；体征提交出网取 visitId 用） */
  const selectedPatient = computed<WardPatientVO | undefined>(() =>
    patientList.value.find((patient) => patient.visitId === selectedVisitId.value),
  );

  /** 病区主加载：一览 → 逐床详情富化（Promise.allSettled 容错，单床失败不阻塞卡墙）→
   * 待复核/分配/任务/交接班并行重载。 */
  async function loadWard(): Promise<void> {
    wardLoading.value = true;
    try {
      patientList.value = await wardPatients.list(wardId.value);
      const results = await Promise.allSettled(
        patientList.value.map((patient) => wardPatients.detail(patient.visitId ?? '')),
      );
      const map: Record<string, WardPatientDetailVO> = {};
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          map[patientList.value[index]?.visitId ?? ''] = result.value;
        }
      });
      detailMap.value = map;
      await Promise.all(options.getWardReloads?.() ?? []);
    } catch {
      // 失败弹错归响应拦截器；驻留旧清单
    } finally {
      wardLoading.value = false;
    }
  }

  /** 病区切换：会话记忆 + 清选中上下文（旧患者面板/草稿随之失效）+ 主加载重跑 */
  function onWardChange(): void {
    sessionStorage.setItem('nursing.wardId', wardId.value);
    selectedVisitId.value = null;
    void loadWard();
  }

  /** 卡墙选中：驱动 ③⑤⑥⑦ 患者上下文；换患者即复位各作业面草稿——未提交数据不得跨患者
   * 滞留，否则续提即归档到新患者名下（医疗差错级，口径同 PdaView.onIdentify） */
  function selectPatient(patient: WardPatientVO): void {
    selectedVisitId.value = patient.visitId ?? null;
    options.onPatientSwitchReset?.();
    options.onPatientSwitchLoad?.();
  }

  /** 出区在途标志（防双击重复出区） */
  const removing = ref(false);

  /** 出区（高风险档 §6.2：danger 确认 + 必填原因 + 回显摘要） */
  async function onRemovePatient(detail: WardPatientDetailVO): Promise<void> {
    if (removing.value) {
      return;
    }
    removing.value = true;
    try {
      try {
        const { value } = await ElMessageBox.prompt(
          `即将为 ${detail.bedNo ?? ''} ${detail.patientName ?? ''} 办理出区，出区后不可恢复`,
          '出区确认',
          {
            type: 'warning',
            confirmButtonText: '确认出区',
            confirmButtonClass: 'el-button--danger',
            inputPlaceholder: '出区原因（必填）',
            inputValidator: (input: string) => (input.trim() === '' ? '出区原因不能为空' : true),
          },
        );
        if (value.trim() === '') {
          return;
        }
        await wardPatients.remove(detail.visitId ?? '', { reason: value.trim() });
        void ElMessage.success(`已出区：${detail.bedNo ?? ''} ${detail.patientName ?? ''}`);
        if (selectedVisitId.value === detail.visitId) {
          selectedVisitId.value = null;
        }
        await loadWard();
      } catch {
        // 用户取消或出区失败：取消静默，失败弹错归拦截器
      }
    } finally {
      removing.value = false;
    }
  }

  return {
    wardId,
    shiftCode,
    patientList,
    wardLoading,
    detailMap,
    selectedVisitId,
    sortedPatients,
    wardCounts,
    bedFlags,
    inFlightCount,
    dutyNurseId,
    selectedDetail,
    selectedPatient,
    loadWard,
    onWardChange,
    selectPatient,
    removing,
    onRemovePatient,
    RISK_FLAG_LABELS,
  };
}
