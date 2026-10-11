<script setup lang="ts">
// 病区床位图页（/inpatient/beds，M04 FU-M04-02 前端面 · 暖纸卷宗重设计切片乙-2）：
// 门牌页首（病区 select + 五态图例计数行 + 刷新，批注行「床位状态机经后端事件投影 · 谁 · 时刻」）+
// 五态纸语法床位卡墙（G3 裁决：整卡实底白字→纸面卡 + 左缘 4px 状态规线 + 状态文字徽标双通道，
// 规线/徽标色走 --fuy-color-bed-* 语义 token 禁自创色值）+ 床位操作下拉（按状态给出合法动作：
// 预占/占床/释放/消毒完成/维修/恢复/转科转床）+ 转科转床弹窗（挂 .fuy-dialog；同病区轻量转床 /
// 跨病区四阶段编排，四阶段口径文案逐字冻结）+ 页脚批注（数据时点）。
// 换脸不换业务：全部写操作自带在途守卫（入口早退先于一切 await）+ 4xx 口径显式校验（禁裸 parse）；
// 失败与业务拒绝=message 批注条（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底展示
// detail 原文），成功反馈=钤印 .fuy-stamp-toast（动词结果 ≤1200ms 收场，失败永不盖印）。
// 病区清单换真数据源 listOrgs({type:'WARD'})（G4 假常量 WARD_OPTIONS 清零，本页零消费；三态=
// 在途骨架/失败恢复/空清单空态脸）。占用患者摘要仅展示就诊号与入科时点（姓名脱敏不出网，
// BedMapVO 契约即不含姓名）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { beds, BED_STATUS_LABELS, transfer } from '@/api/inpatient';
import type { BedMapVO } from '@/api/inpatient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

// 元素权限判定入口（PR-4F F8）：床位卡「转科转床」入口经 actionsOf 过滤（#15），
// 与弹窗确认按钮（v-perm）同码双保险
const auth = useAuthStore();

/** 门牌批注行操作员（会话身份 displayName，空值「—」占位零伪数据） */
const operatorName = computed(() => auth.user?.displayName ?? '—');
/** 门牌批注行时刻（页面装配时点，系统生成非伪数据） */
const headTime = formatTime(new Date().toISOString());

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，M02 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/** 五态展示序与图例顺序（BED_STATUS_LABELS 键序冻结） */
const BED_STATE_ORDER = ['FREE', 'RESERVED', 'OCCUPIED', 'DISINFECTING', 'MAINTENANCE'] as const;

/** 床位状态 → 合法动作清单（后端状态机迁移表的前端镜像；value=动作语义键） */
const BED_ACTIONS: Record<string, ReadonlyArray<{ value: string; label: string }>> = {
  FREE: [
    { value: 'reserve', label: '预占' },
    { value: 'assign', label: '占床' },
    { value: 'maintain', label: '转维修' },
  ],
  RESERVED: [{ value: 'release', label: '释放预占' }],
  OCCUPIED: [{ value: 'transferDialog', label: '转科转床' }],
  DISINFECTING: [{ value: 'disinfectDone', label: '消毒完成' }],
  MAINTENANCE: [{ value: 'maintainDone', label: '维修恢复' }],
};

/* ==================== 病区清单（listOrgs WARD 档真数据面，G4） ==================== */
/** 病区下拉选项（listOrgs 真数据面；展示与出网值=orgCode） */
const wardOptions = ref<OrgVO[]>([]);
/** 当前病区（初值待清单装配裁决；切换即重载床位图） */
const wardId = ref('');
/** 病区清单装载（三态经 useAsyncTask 承载：在途骨架 loading / 失败恢复 error / 空清单空态脸） */
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
  // wardId 初值裁决：空/不在启用清单内一律回落首项（清单空则驻留空串，床位面加载守卫拦截出网）
  if (!wardOptions.value.some((option) => option.orgCode === wardId.value)) {
    wardId.value = wardOptions.value[0]?.orgCode ?? '';
  }
});
const wardOptionsLoading = wardLoad.loading;
/** 病区清单失败态（null=无失败；视图据此给「说问题+给恢复」恢复行，不伪装空选项） */
const wardOptionsError = computed(() => wardLoad.error.value !== null);
/** 病区下拉禁用态：清单在途或空清单（出网值必须锚定真实病区编码） */
const wardDisabled = computed(() => wardOptionsLoading.value || wardOptions.value.length === 0);

/**
 * 病区清单（重）装载出网（失败弹错归响应拦截器，恢复经视图「重新加载」重入）。
 *
 * @return true=清单就绪且存在可用病区（装配链据此决定是否续拉床位图）；false=失败或空清单
 */
async function loadWardOptions(): Promise<boolean> {
  await wardLoad.run();
  return wardOptions.value.length > 0;
}

/* ==================== 床位卡墙 ==================== */
const bedList = ref<BedMapVO[]>([]);
/** 在途守卫：同一时刻至多一个床位动作可发（防双击重复提交） */
const actingBedId = ref<string | null>(null);
/** 床位图数据时点（最近一次成功拉取，页脚批注消费；null=尚未成功拉取） */
const loadedAt = ref<string | null>(null);

/** 加载病区床位图（后端床号升序直出）：loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移，
 * 行为与迁移前一致——失败弹错归响应拦截器、驻留旧床位图）；病区上下文未就绪（清单在途/
 * 失败/空清单）时零出网，防空病区码请求 */
const { loading: mapLoading, run: loadMap } = useAsyncTask(async () => {
  if (wardId.value === '') {
    return;
  }
  bedList.value = await beds.map(wardId.value);
  // 数据时点回填（页脚批注「数据时点」消费；失败路径驻留旧时点）
  loadedAt.value = new Date().toISOString();
});

/** 装配链：病区清单就绪后再拉床位图（失败/空清单驻留恢复态/空态脸，零床位面出网） */
async function bootWard(): Promise<void> {
  if (await loadWardOptions()) {
    void loadMap();
  }
}

function onWardChange(): void {
  void loadMap();
}

/** 五态状态类（fuy-bed-card--{state} 契约类=左缘 4px 状态规线取色锚，G3 双通道之一） */
function stateClass(bed: BedMapVO): string {
  return `fuy-bed-card--${(bed.bedStatus ?? '').toLowerCase()}`;
}

/** 状态中文词表反查（卡内状态徽标与图例共用；空值「—」占位） */
function stateLabel(code: string | undefined): string {
  return BED_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 五态计数（图例行；与卡态同源=同一 bedList 派生，G5 分组折叠不影响计数） */
const stateCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {};
  for (const state of BED_STATE_ORDER) {
    counts[state] = bedList.value.filter((bed) => bed.bedStatus === state).length;
  }
  return counts;
});

/* ==================== 卡墙分组（G5 ⑨.4 长列表策略） ==================== */
/** 卡墙分组阈值：>120 卡必须分组/分页，禁一次全量平铺（暖纸渲染性能宪章 ⑨.4） */
const WALL_GROUP_THRESHOLD = 120;

/** 床号段分组形态（组头=「01-10 床」石规签行；空 label=无组头的单组平铺） */
interface WallBedGroup {
  /** 分组稳定键（段起始床号 / all / other） */
  key: string;
  /** 组头展示串（空串=无组头的单组平铺形态） */
  label: string;
  /** 组内床位（上游床号升序切片，不重排） */
  beds: BedMapVO[];
}

/** 床号数字前缀 → 十位段起始（非数字床号返回 null，归「其他床号」尾组） */
function bedSegmentStart(bedNo: string): number | null {
  const match = /^(\d+)/.exec(bedNo.trim());
  if (match === null) {
    return null;
  }
  const no = Number.parseInt(match[1] ?? '', 10);
  return no > 0 ? Math.floor((no - 1) / 10) * 10 + 1 : null;
}

/** 卡墙分组（参照 nursing/wardBoardShared.groupWallPatients 纯函数形态页内实现）：
 * ≤阈值单组平铺（组头不渲染）；超阈值按床号十位段分组，非数字床号归「其他床号」尾组；
 * 入参须为后端床号升序清单，组内顺序=入参序不重排。
 *
 * @param bedsInWard 当前病区床位清单（后端床号升序）
 * @return 分组结果；空清单返回空数组（调用方渲染空态脸）
 */
function groupWallBeds(bedsInWard: BedMapVO[]): WallBedGroup[] {
  if (bedsInWard.length <= WALL_GROUP_THRESHOLD) {
    return bedsInWard.length === 0 ? [] : [{ key: 'all', label: '', beds: bedsInWard }];
  }
  const buckets = new Map<string, WallBedGroup>();
  const others: BedMapVO[] = [];
  for (const bed of bedsInWard) {
    const start = bedSegmentStart(bed.bedNo ?? '');
    if (start === null) {
      others.push(bed);
      continue;
    }
    const key = `seg-${start}`;
    const bucket = buckets.get(key);
    if (bucket === undefined) {
      // 组头「01-10 床」形态：段界两位补零（三位床号段仍以两位展示段界）
      buckets.set(key, {
        key,
        label: `${String(start).padStart(2, '0')}-${String(start + 9).padStart(2, '0')} 床`,
        beds: [bed],
      });
    } else {
      bucket.beds.push(bed);
    }
  }
  const groups = [...buckets.values()];
  if (others.length > 0) {
    groups.push({ key: 'other', label: '其他床号', beds: others });
  }
  return groups;
}

/** 分组结果（≤120 单组无组头平铺；>120 十位段分组） */
const wallGroups = computed<WallBedGroup[]>(() => groupWallBeds(bedList.value));
/** 已折叠床号段键集（分组形态下的组头开关；平铺形态无组头不消费） */
const collapsedGroups = ref<readonly string[]>([]);
function isGroupCollapsed(key: string): boolean {
  return collapsedGroups.value.includes(key);
}
function toggleWallGroup(key: string): void {
  collapsedGroups.value = isGroupCollapsed(key)
    ? collapsedGroups.value.filter((collapsed) => collapsed !== key)
    : [...collapsedGroups.value, key];
}

/* ==================== 床位动作与钤印反馈 ==================== */
/** 钤印确认反馈（契约 ⑧.5）：动词结果文案 ≤1200ms 收场；失败/业务拒绝走 message 不入钤印。
 * seq 每盖一次递增，模板以 :key 重挂重放落章动画（1200ms 内连续动作第二印不吞章——
 * 乙-1 登记台先例同构）；v-if 挂载制防 .fuy-stagger > * 的 fuy-rise both 误伤常显 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, 1200);
}
onBeforeUnmount(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
});

/** 床位卡动作清单（按状态给出合法迁移）；转科转床入口受元素权限约束（PR-4F #15）：
 * 床位动作经 v-for 生成 option，v-perm 指令无法逐项直挂，以 hasPerm 过滤清单等效承载
 * （无码全隐藏 D-34）——与弹窗确认按钮 v-perm 同码双保险 */
function actionsOf(bed: BedMapVO): ReadonlyArray<{ value: string; label: string }> {
  const actions = BED_ACTIONS[bed.bedStatus ?? ''] ?? [];
  return auth.hasPerm('inpatient:bed:btn:change')
    ? actions
    : actions.filter((action) => action.value !== 'transferDialog');
}

/** 床位操作 select 复位（动作完成后回「操作…」占位） */
function resetActionSelect(): void {
  actingBedId.value = null;
}

/** 占床（直接分配快速通道）：prompt 收 visit 号并显式校验 14 位格式零出网。 */
async function onAssign(bed: BedMapVO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `为床位 ${bed.bedNo ?? ''} 办理占床，请扫描/输入住院就诊号`,
      '床位占床',
      {
        confirmButtonText: '确认占床',
        inputPlaceholder: 'visit 号（I 开头共 14 位）',
        inputValidator: (input: string) =>
          VISIT_NO_PATTERN.test(input.trim()) ? true : 'visit 号应以 I 开头共 14 位（I+日期+流水）',
      },
    );
    actingBedId.value = String(bed.bedId ?? '');
    await beds.assign(String(bed.bedId ?? ''), { visitId: value.trim() });
    showStamp('已占床');
    await loadMap();
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      return;
    }
    surfaceBizError(error);
  } finally {
    resetActionSelect();
  }
}

/** 确认型迁移动作的成功钤印文案（动词结果，与蓝图钤印族对齐；键集=确认型动作全集） */
const ACTION_STAMP_LABELS: Record<string, string> = {
  reserve: '已预占',
  release: '已释放',
  disinfectDone: '消毒完成',
  maintain: '已转维修',
  maintainDone: '维修恢复',
};

/** 床位卡动作分发：转科转床开弹窗；占床走 prompt；其余简单迁移走确认。 */
async function onBedAction(bed: BedMapVO, action: string): Promise<void> {
  if (action === '' || actingBedId.value !== null) {
    return;
  }
  if (action === 'transferDialog') {
    void openTransferDialog(bed);
    resetActionSelect();
    return;
  }
  if (action === 'assign') {
    await onAssign(bed);
    return;
  }
  const actionLabels: Record<string, string> = {
    reserve: '预占',
    release: '释放预占',
    disinfectDone: '消毒完成',
    maintain: '转维修',
    maintainDone: '维修恢复',
  };
  try {
    await ElMessageBox.confirm(
      `确认对床位 ${bed.bedNo ?? ''} 执行「${actionLabels[action] ?? action}」？`,
      '床位操作确认',
      { confirmButtonText: '确认', cancelButtonText: '取消' },
    );
  } catch {
    resetActionSelect();
    return;
  }
  actingBedId.value = String(bed.bedId ?? '');
  try {
    if (action === 'reserve') {
      // 预占不绑定就诊主体，契约形状固化空对象（BedReserveRequest）
      await beds.reserve(String(bed.bedId ?? ''), {});
    } else if (action === 'release') {
      await beds.release(String(bed.bedId ?? ''));
    } else if (action === 'disinfectDone') {
      await beds.disinfectDone(String(bed.bedId ?? ''));
    } else if (action === 'maintain') {
      await beds.maintain(String(bed.bedId ?? ''));
    } else if (action === 'maintainDone') {
      await beds.maintainDone(String(bed.bedId ?? ''));
    }
    showStamp(ACTION_STAMP_LABELS[action] ?? '已完成');
    await loadMap();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    resetActionSelect();
  }
}

/* ==================== 转科转床弹窗 ==================== */
const transferVisible = ref(false);
const transferring = ref(false);
/** 转科编排源床位（OCCUPIED，携占用就诊摘要） */
const transferBed = ref<BedMapVO | null>(null);
/** 编排模式：change=同病区转床轻量路径；transfer=跨病区四阶段编排 */
const transferMode = ref<'change' | 'transfer'>('change');
/** 目标病区初值在开窗时锚定源床位病区（清单真数据面；初始空串待开窗裁决） */
const transferForm = ref({ toWardId: '', toBedId: '' });
/** 目标床位候选（转床=本病区空床；转科=目标病区空床+预占床——转科预占为计划目标） */
const targetBeds = ref<BedMapVO[]>([]);

/** 源占用就诊摘要（脱敏口径：仅就诊号+入科时点） */
const transferVisitId = computed(() => transferBed.value?.occupiedVisit?.visitId ?? '');

/** 打开转科转床弹窗：默认同病区转床，预载本病区可转入床位（FREE/RESERVED）。 */
async function openTransferDialog(bed: BedMapVO): Promise<void> {
  transferBed.value = bed;
  transferMode.value = 'change';
  transferForm.value = { toWardId: bed.wardId ?? wardId.value, toBedId: '' };
  transferVisible.value = true;
  await loadTargetBeds(transferForm.value.toWardId);
}

/** 切换编排模式：转科模式展示目标病区选择（默认当前病区，跨病区由用户改选）。 */
function onTransferModeChange(): void {
  transferForm.value.toBedId = '';
}

/** 目标病区变更后重载可转入床位。 */
async function onTransferWardChange(): Promise<void> {
  transferForm.value.toBedId = '';
  await loadTargetBeds(transferForm.value.toWardId);
}

/** 装载目标床位候选：空床+预占床（转科预占语义，占床/消毒/维修不可选）。 */
async function loadTargetBeds(ward: string): Promise<void> {
  try {
    const all = await beds.map(ward);
    targetBeds.value = all.filter(
      (bed) => bed.bedStatus === 'FREE' || bed.bedStatus === 'RESERVED',
    );
  } catch {
    // 失败弹错归响应拦截器；候选空表态
    targetBeds.value = [];
  }
}

/** 转科转床提交：未选源床/目标床显式校验零出网 → 出网 → 成功关窗盖钤印并刷新床位图。
 * 四阶段编排（停嘱截断/在途三分/床位流转/事件链）与同病区判别全由后端承载。 */
async function onTransferConfirm(): Promise<void> {
  if (transferring.value) {
    return;
  }
  const bed = transferBed.value;
  if (bed === null || transferVisitId.value === '') {
    void ElMessage.warning('请从占床床位发起转科转床');
    return;
  }
  if (transferForm.value.toBedId === '') {
    void ElMessage.warning('请选择目标床位');
    return;
  }
  transferring.value = true;
  try {
    if (transferMode.value === 'change') {
      await transfer.changeBed(transferVisitId.value, { toBedId: transferForm.value.toBedId });
      showStamp('已转床');
    } else {
      await transfer.execute(transferVisitId.value, {
        toWardId: transferForm.value.toWardId,
        toBedId: transferForm.value.toBedId,
      });
      showStamp('已转科完成');
    }
    transferVisible.value = false;
    await loadMap();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    transferring.value = false;
  }
}

onMounted(() => {
  void bootWard();
});
</script>

<template>
  <section class="fuy-page bed-map-view">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块）：
         本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距（页首 0 / 卡墙 1 /
         页脚 2 三段级联序）由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 -->
    <div class="fuy-stagger bed-map-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（病区真数据下拉 +
           五态图例计数行 + 刷新）+「床位状态机经后端事件投影 · 谁 · 时刻」批注行 + 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">病区床位图</h1>
          <span class="fuy-page-en" aria-hidden="true">BED BOARD</span>
        </div>
        <div class="fuy-page-status bed-map-head-controls">
          <select
            v-model="wardId"
            class="bed-map-ward"
            aria-label="病区选择"
            :disabled="wardDisabled"
            @change="onWardChange"
          >
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
              {{ org.orgName }}（{{ org.orgCode }}）
            </option>
          </select>
          <!-- 五态图例计数行（双通道既有：五色点+文字+计数；计数与卡态同源派生） -->
          <div class="bed-map-legend" aria-label="床位状态图例">
            <span
              v-for="state in BED_STATE_ORDER"
              :key="state"
              class="bed-map-legend-item"
              :class="`bed-map-legend--${state.toLowerCase()}`"
            >
              <i class="bed-map-legend-dot" aria-hidden="true" />{{ stateLabel(state) }}
              <span class="fuy-num">{{ stateCounts[state] }}</span>
            </span>
          </div>
          <el-button class="bed-map-refresh" :loading="mapLoading" @click="loadMap">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          床位状态机经后端事件投影 · 操作员 {{ operatorName }} · <time>{{ headTime }}</time>
        </p>
      </header>

      <!-- ② 五态纸语法床位卡墙（G3 裁决：纸面卡 + 左缘 4px 状态规线 + 状态文字徽标双通道；
           >120 卡按床号十位段分组签行折叠，G5 ⑨.4） -->
      <section class="fuy-card bed-map-wall-card" :style="{ '--fuy-stagger-index': 1 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">床位卡墙</h2>
          <span class="bed-map-wall-meta">
            床位 <span class="fuy-num">{{ bedList.length }}</span> · {{ wardId || '—' }}
          </span>
        </header>
        <div class="fuy-card-body">
          <!-- 病区清单三态（listOrgs 真数据面）：在途骨架 / 失败恢复 / 空清单空态脸 -->
          <div v-if="wardOptionsLoading" class="bed-wall-sk" aria-hidden="true">
            <span v-for="i in 8" :key="i" class="fuy-sk" />
          </div>
          <div v-else-if="wardOptionsError" class="fuy-empty bed-map-boot-error" role="alert">
            <p class="fuy-empty-title">病区清单加载失败</p>
            <p class="fuy-empty-hint">无法获取病区清单，请检查网络后点击重新加载恢复。</p>
            <el-button type="primary" size="small" @click="bootWard">重新加载</el-button>
          </div>
          <div v-else-if="wardOptions.length === 0" class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">暂无可用病区</p>
            <p class="fuy-empty-hint">组织机构未配置启用病区，请联系管理员维护后重试。</p>
          </div>
          <template v-else>
            <!-- 床位图首拉骨架（卡墙 120px CLS 锁档）；已有数据重刷仅刷新钮转圈不闪骨架 -->
            <div v-if="mapLoading && bedList.length === 0" class="bed-wall-sk" aria-hidden="true">
              <span v-for="i in 8" :key="i" class="fuy-sk" />
            </div>
            <template v-else>
              <div v-for="group in wallGroups" :key="group.key" class="bed-wall-group">
                <!-- 床号段组头（石规签行折叠开关；平铺形态无组头不渲染） -->
                <button
                  v-if="group.label !== ''"
                  type="button"
                  class="bed-wall-group-head"
                  :aria-expanded="!isGroupCollapsed(group.key)"
                  @click="toggleWallGroup(group.key)"
                >
                  <span aria-hidden="true">{{ isGroupCollapsed(group.key) ? '▸' : '▾' }}</span
                  >{{ group.label }}
                </button>
                <div v-if="!isGroupCollapsed(group.key)" class="bed-map-wall">
                  <article
                    v-for="bed in group.beds"
                    :key="bed.bedId"
                    class="bed-card"
                    :class="stateClass(bed)"
                  >
                    <div class="bed-card-head">
                      <span class="fuy-num bed-card-no">{{ bed.bedNo }}</span>
                      <!-- 状态文字徽标（双通道之文字通道：规线类×徽标文案逐一成对） -->
                      <span class="bed-card-state">{{ stateLabel(bed.bedStatus) }}</span>
                    </div>
                    <div class="bed-card-meta">
                      <span>{{
                        bed.allowGender === 'MALE'
                          ? '限男'
                          : bed.allowGender === 'FEMALE'
                            ? '限女'
                            : '不限性别'
                      }}</span>
                      <span v-if="bed.bedAttr && bed.bedAttr !== 'NORMAL'" class="fuy-num">{{
                        bed.bedAttr
                      }}</span>
                    </div>
                    <!-- 占用患者摘要（脱敏口径：就诊号+入科时点，姓名不出网） -->
                    <div
                      v-if="bed.bedStatus === 'OCCUPIED' && bed.occupiedVisit"
                      class="bed-card-occupied"
                    >
                      <span class="fuy-num">在院 {{ bed.occupiedVisit.visitId }}</span>
                      <span class="bed-card-occupied-time"
                        >入科 {{ formatTime(bed.occupiedVisit.admittedAt) }}</span
                      >
                    </div>
                    <select
                      v-if="actionsOf(bed).length > 0"
                      class="bed-card-action"
                      :aria-label="`床位 ${bed.bedNo ?? ''} 操作`"
                      :disabled="actingBedId !== null"
                      @change="onBedAction(bed, ($event.target as HTMLSelectElement).value)"
                    >
                      <option value="">操作…</option>
                      <option
                        v-for="action in actionsOf(bed)"
                        :key="action.value"
                        :value="action.value"
                      >
                        {{ action.label }}
                      </option>
                    </select>
                  </article>
                </div>
              </div>
              <!-- 床位空态脸（G6「暂无」语法给下一步；默认插画 el-empty 随收编清零） -->
              <div v-if="bedList.length === 0" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无床位</p>
                <p class="fuy-empty-hint">当前病区暂无床位记录，可切换病区或点击刷新。</p>
              </div>
            </template>
          </template>
        </div>
      </section>

      <!-- ③ 转科转床弹窗（同病区轻量路径 / 跨病区四阶段编排；挂 .fuy-dialog 衬线标题脸） -->
      <el-dialog v-model="transferVisible" title="转科转床" width="440px" class="fuy-dialog">
        <p class="bed-transfer-patient fuy-num">
          在院 {{ transferVisitId }}
          <span v-if="transferBed?.bedNo" class="bed-transfer-from"
            >现床位 {{ transferBed?.bedNo }}</span
          >
        </p>
        <p class="bed-transfer-patient-hint">患者信息按脱敏口径展示，仅显示就诊号</p>
        <div class="bed-transfer-mode">
          <label class="bed-transfer-mode-item">
            <input
              v-model="transferMode"
              type="radio"
              value="change"
              @change="onTransferModeChange"
            />
            同病区转床（轻量，无停嘱）
          </label>
          <label class="bed-transfer-mode-item">
            <input
              v-model="transferMode"
              type="radio"
              value="transfer"
              @change="onTransferModeChange"
            />
            跨病区转科（四阶段编排）
          </label>
        </div>
        <template v-if="transferMode === 'transfer'">
          <label class="bed-transfer-label">目标病区</label>
          <select
            v-model="transferForm.toWardId"
            class="bed-transfer-input"
            aria-label="目标病区"
            :disabled="wardDisabled"
            @change="onTransferWardChange"
          >
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
              {{ org.orgName }}（{{ org.orgCode }}）
            </option>
          </select>
        </template>
        <label class="bed-transfer-label">目标床位（空床/预占床）</label>
        <select v-model="transferForm.toBedId" class="bed-transfer-input" aria-label="目标床位">
          <option value="">请选择目标床位</option>
          <option v-for="bed in targetBeds" :key="bed.bedId" :value="bed.bedId ?? ''">
            {{ bed.bedNo }}（{{ stateLabel(bed.bedStatus) }}）
          </option>
        </select>
        <!-- 四阶段提示文案（转科编排骨训口径逐字冻结；同病区转床仅床位流转） -->
        <p class="bed-transfer-stages">
          转科按四阶段编排执行：① 医嘱停嘱截断 → ② 在途执行三分 → ③ 床位流转 → ④
          事件通知；同病区转床仅执行床位流转（无停嘱）。
        </p>
        <template #footer>
          <el-button size="small" @click="transferVisible = false">取消</el-button>
          <!-- 转床确认（PR-4F #15）：与床位卡入口同码 v-perm 直挂（双保险）——与 transferring
               在途数据态 :disabled 正交叠加 -->
          <el-button
            v-perm="'inpatient:bed:btn:change'"
            type="primary"
            size="small"
            :loading="transferring"
            :disabled="transferring"
            @click="onTransferConfirm"
            >{{ transferMode === 'transfer' ? '确认转科' : '确认转床' }}</el-button
          >
        </template>
      </el-dialog>

      <!-- ④ 页脚批注（数据时点；禁放操作钮，契约 ⑧.8） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        床位状态机经后端事件投影 · 数据时点 {{ formatTime(loadedAt ?? undefined) }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：动词结果 ≤1200ms 收场，aria-live 即时播报；seq 递增重挂
           重放落章动画，连续动作第二印不吞章） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </section>
</template>

<style scoped>
/* 床位图页（暖纸乙-2）：门牌页首/卷宗卡/页脚/空态脸归全局族（element-plus.css）；
   床位卡=G3 纸语法（纸面卡 + 左缘 4px 状态规线 + 状态文字徽标），五态色值全部经
   --fuy-color-bed-* 语义 token（禁自创色值），版面内零投影零新 hex */

/* 页内级联容器（契约 ⑦.4）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，
   纵排与区块间距由本层同值承接（.fuy-page 同构 flex+gap） */
.bed-map-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：病区 select + 五态图例计数行 + 刷新（wrap 兜窄屏，baseline 随页首族） */
.bed-map-head-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-3);
}

.bed-map-ward {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 五态图例（圆点取对应状态语义 token，计数 tabular-nums） */
.bed-map-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-3);
}

.bed-map-legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-map-legend-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--fuy-radius-full); /* 图例五色点=正圆色点（radius-sm 2px 为圆角方块错档） */
}

.bed-map-legend--free .bed-map-legend-dot {
  background: var(--fuy-color-bed-free);
}

.bed-map-legend--reserved .bed-map-legend-dot {
  background: var(--fuy-color-bed-reserved);
}

.bed-map-legend--occupied .bed-map-legend-dot {
  background: var(--fuy-color-bed-occupied);
}

.bed-map-legend--disinfecting .bed-map-legend-dot {
  background: var(--fuy-color-bed-disinfecting);
}

.bed-map-legend--maintenance .bed-map-legend-dot {
  background: var(--fuy-color-bed-maintenance);
}

/* 卡墙卡头计数行（床位数+当前病区，空值 — 占位） */
.bed-map-wall-meta {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 卡墙：临床一屏可见行数优先的紧凑网格（min-height 120px 锁 CLS） */
.bed-map-wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--fuy-space-3);
  align-content: start;
  min-height: 120px;
}

/* 床位纸面卡（G3 裁决：整卡实底白字→纸面卡=卡面亮纸+发丝描边+radius-card，与丁-1 床卡同形态；禁投影） */
.bed-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  min-height: 120px;
  padding: var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-card);
  background: var(--fuy-surface-card);
  color: var(--fuy-color-text-emphasis);
}

/* 左缘 4px 状态规线（双通道之色通道；随卡圆角；五态取色经 --fuy-color-bed-* 语义 token） */
.bed-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  border-radius: var(--fuy-radius-card) 0 0 var(--fuy-radius-card);
  background: var(--fuy-color-bed-disinfecting); /* 无态兜底=中性灰墨 */
}

/* hover=规线加重一档（静态加重，禁投影禁动画——蓝图 ④ 口径） */
.bed-card:hover::before {
  width: 6px;
}

.bed-card.fuy-bed-card--free::before {
  background: var(--fuy-color-bed-free);
}

.bed-card.fuy-bed-card--reserved::before {
  background: var(--fuy-color-bed-reserved);
}

.bed-card.fuy-bed-card--occupied::before {
  background: var(--fuy-color-bed-occupied);
}

.bed-card.fuy-bed-card--disinfecting::before {
  background: var(--fuy-color-bed-disinfecting);
}

.bed-card.fuy-bed-card--maintenance::before {
  background: var(--fuy-color-bed-maintenance);
}

.bed-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
}

.bed-card-no {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  line-height: 1.2;
}

/* 状态文字徽标（双通道之文字通道）：同色文字 + 同色 10% 洗底 + 同色描边（色经 currentColor
   派生零新字面量；洗底/描边为装饰增强，老引擎退化仍承文字通道） */
.bed-card-state {
  padding: 0 var(--fuy-space-2);
  border: 1px solid currentColor;
  border-radius: var(--fuy-radius-full);
  background: color-mix(in srgb, currentColor 10%, transparent);
  border-color: color-mix(in srgb, currentColor 30%, transparent);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  line-height: 20px;
  white-space: nowrap;
}

.bed-card.fuy-bed-card--free .bed-card-state {
  color: var(--fuy-color-bed-free);
}

.bed-card.fuy-bed-card--reserved .bed-card-state {
  color: var(--fuy-color-bed-reserved);
}

.bed-card.fuy-bed-card--occupied .bed-card-state {
  color: var(--fuy-color-bed-occupied);
}

.bed-card.fuy-bed-card--disinfecting .bed-card-state {
  color: var(--fuy-color-bed-disinfecting);
}

.bed-card.fuy-bed-card--maintenance .bed-card-state {
  color: var(--fuy-color-bed-maintenance);
}

.bed-card-meta {
  display: flex;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 占用摘要：就诊号承浓墨、入科时点承灰墨（脱敏口径不变） */
.bed-card-occupied {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fuy-font-size-xs);
}

.bed-card-occupied-time {
  color: var(--fuy-color-text-secondary);
}

/* 床位动作 select（native 保留=jsdom 可测口径）：最亮纸白底+石规描边，禁用态弱化 */
.bed-card-action {
  margin-top: auto;
  padding: 3px var(--fuy-space-1);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

.bed-card-action:disabled {
  color: var(--fuy-color-text-secondary);
}

/* 卡墙首拉骨架（.fuy-sk 金洗纸块，卡墙 120px CLS 锁档） */
.bed-wall-sk {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--fuy-space-3);
  align-content: start;
  min-height: 120px;
}

.bed-wall-sk .fuy-sk {
  height: 120px;
}

/* 床号段分组组头（石规签行：上缘石规 + 灰墨小字，>120 卡折叠开关） */
.bed-wall-group-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  width: 100%;
  margin: var(--fuy-space-2) 0;
  padding: var(--fuy-space-1) 0;
  border: 0;
  border-top: 1px solid var(--fuy-color-rule-stone);
  background: transparent;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
  letter-spacing: 0.08em;
  text-align: left;
  cursor: pointer;
}

.bed-transfer-patient {
  margin: 0 0 var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-brand-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.bed-transfer-from {
  margin-left: var(--fuy-space-2);
}

.bed-transfer-patient-hint {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-transfer-mode {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  margin-bottom: var(--fuy-space-3);
}

.bed-transfer-mode-item {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.bed-transfer-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-transfer-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

.bed-transfer-stages {
  margin: 0;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-left: 3px solid var(--fuy-color-brand);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-xs);
  line-height: 1.6;
  color: var(--fuy-color-text-secondary);
}
</style>
