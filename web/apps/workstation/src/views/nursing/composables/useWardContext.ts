/**
 * 病区看板作业面①②底座：病区/班次上下文 + 床位序患者卡墙 + 全页患者选中锚点
 * （WardBoardView 巨型脚本随迁，EX-47 拆分）。选中患者（selectedVisitId）是③⑤⑥⑦区块的
 * 患者上下文锚点；换患者时的草稿复位与患者上下文重载经入参回调注入（各作业面 composable
 * 承载，时序与拆分前逐字一致：先换锚点 → 同步复位草稿 → 异步发起上下文加载）。
 * 病区主加载（一览 → 逐床详情富化 → 四面联动重载）同样经 getWardReloads 惰性求值注入，
 * 保持「单床失败不阻塞卡墙 + 四面并行重载」的原时序。
 * 暖纸卷宗切片（逐页蓝图 甲-1）：病区清单换真数据源 listOrgs({type:'WARD'})——WARD_OPTIONS
 * 假常量消费清零（册级总则 G4）；清单三态（在途/成功/失败）由 wardOptionsLoading/
 * wardOptionsError 承载，失败恢复经视图「重新加载」重入 loadWardOptions；病区未就绪
 * （wardId 为空）时主加载零出网，防空病区码请求。会话记忆键 nursing.wardId 沿用。
 * W-34 换源（Task 7 审查 C2/C3 口径）：conditionTags 已随过渡通道退役——病情角标环与
 * 危/重计数派生面同步退役（过敏/风险角标保留）；出区移除动作归 inpatient 出院/转科事件
 * 投影，前端不再直调移除端点（原 onRemovePatient/removing 随端点退役删除）。
 */
import { computed, ref } from 'vue';
import { SHIFT_OPTIONS, wardPatients } from '@/api/nursing';
import type { WardPatientDetailVO, WardPatientVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { splitTags } from '../wardBoardShared';

/** 床旁风险标识 → 空心角标文案（§3.11：跌倒/压疮红描边空心） */
const RISK_FLAG_LABELS: Record<string, string> = {
  FALL: '跌',
  PRESSURE: '压',
  TUBE: '管',
};

/** 病区下拉选项（listOrgs WARD 档真数据面：code=orgCode，label=「名称（编码）」双标识防同名） */
export interface WardOptionItem {
  /** 病区编码（=OrgVO.orgCode，出网与病区面拉取的锚点值） */
  code: string;
  /** 展示串（「名称（编码）」形态，与分诊台诊区下拉同构） */
  label: string;
}

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
  /** 当前病区（会话内记忆：切换/刷新不回默认病区；初值待病区清单装配裁决） */
  const wardId = ref(sessionStorage.getItem('nursing.wardId') ?? '');
  /** 当前班次（分配与交接班的班次上下文；默认取词表首项，与病区初值同款口径） */
  const shiftCode = ref(SHIFT_OPTIONS[0].code);

  /** 病区下拉选项（listOrgs WARD 档真数据面） */
  const wardOptions = ref<WardOptionItem[]>([]);
  /** 病区清单在途态（视图据此给骨架与选择器禁用） */
  const wardOptionsLoading = ref(false);
  /** 病区清单失败态（视图据此给「说问题+给恢复」恢复态） */
  const wardOptionsError = ref(false);
  /** 病区面数据时点（最近一次主加载完成时点，页脚批注消费；null=尚未成功拉取） */
  const loadedAt = ref<string | null>(null);

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

  /** 病区概览计数（在区总数，头部计数行；危/重计数已随 conditionTags 退役——W-34 换源） */
  const wardCounts = computed(() => ({ total: patientList.value.length }));

  /** 卡墙角标行（§3.4：显示优先级 过敏>风险>其余，上限 4 个 + 溢出 +N；病情角标环已随
   * conditionTags 退役，仅存过敏与风险两类） */
  function bedFlags(detail: WardPatientDetailVO | undefined): {
    shown: Array<{ cls: string; text: string }>;
    overflow: number;
  } {
    if (detail === undefined) {
      return { shown: [], overflow: 0 };
    }
    const ordered: Array<{ cls: string; text: string }> = [];
    if (detail.allergyFlag === true) {
      ordered.push({ cls: 'fuy-nursing-flag--danger', text: '敏' });
    }
    for (const risk of splitTags(detail.riskFlags)) {
      const label = RISK_FLAG_LABELS[risk];
      if (label !== undefined) {
        ordered.push({ cls: 'fuy-nursing-flag--outline', text: label });
      }
    }
    return {
      shown: ordered.slice(0, 4),
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

  /**
   * 病区清单装配（listOrgs WARD 档真数据面，暖纸切片 G4）：拉取启用病区并裁决 wardId
   * 初值——会话记忆病区仍在清单内则沿用（切换/刷新不回默认病区），否则落首项。
   *
   * @return true=清单就绪且存在可用病区（调用方可继续拉病区面）；
   *         false=失败（wardOptionsError 置位，弹错归拦截器）或空清单（驻留空上下文）
   */
  async function loadWardOptions(): Promise<boolean> {
    wardOptionsLoading.value = true;
    wardOptionsError.value = false;
    try {
      const orgs: OrgVO[] = await listOrgs({ type: 'WARD' });
      wardOptions.value = orgs.map((org) => ({
        code: org.orgCode ?? '',
        label: `${org.orgName ?? '—'}（${org.orgCode ?? '—'}）`,
      }));
      // 会话记忆裁决：当前 wardId 不在启用清单内（含空初值/下线病区）一律回落首项；
      // 清单为空则驻留空串（主加载守卫拦截出网），装配本身不写会话记忆（仅切换时写）
      if (!wardOptions.value.some((option) => option.code === wardId.value)) {
        wardId.value = wardOptions.value[0]?.code ?? '';
      }
      return wardOptions.value.length > 0;
    } catch {
      // 失败弹错归响应拦截器；恢复态由视图给「重新加载」重入
      wardOptionsError.value = true;
      return false;
    } finally {
      wardOptionsLoading.value = false;
    }
  }

  /** 病区主加载：一览 → 逐床详情富化（Promise.allSettled 容错，单床失败不阻塞卡墙）→
   * 待复核/分配/任务/交接班并行重载。病区未就绪（清单在途/失败/空清单）时零出网。 */
  async function loadWard(): Promise<void> {
    if (wardId.value === '') {
      // 病区上下文未就绪：不出网，驻留骨架或恢复态（防空病区码请求）
      return;
    }
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
      // 数据时点回填（页脚批注「数据时点」消费；失败路径驻留旧时点）
      loadedAt.value = new Date().toISOString();
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

  return {
    wardId,
    shiftCode,
    wardOptions,
    wardOptionsLoading,
    wardOptionsError,
    loadedAt,
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
    loadWardOptions,
    loadWard,
    onWardChange,
    selectPatient,
    RISK_FLAG_LABELS,
  };
}
