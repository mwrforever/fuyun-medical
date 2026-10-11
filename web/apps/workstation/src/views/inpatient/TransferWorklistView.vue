<script setup lang="ts">
// 转抄工作台页（/inpatient/transfer，M04 FU-M04-06 前端面 · 暖纸卷宗换脸重排 乙-4，
// 「换脸不换业务」：勾选状态机/批量核对载荷/出网顺序=列表序契约/IP-1016 前端不复制拦截
// 零变动）纵向序：门牌页首（衬线标题 + 状态位=病区 select + 「已选 N」墨底徽标 + 刷新，
// 批注行「病区 AUDITED 医嘱聚合 · 高危/输血类须双人核对 · 谁 · 时刻」）→ 两栏 lg 16/8（既有）：
// 左 16 栏=待转抄列表卡（卡头「全选本页」勾选+「已选 N · 共 M 条」计数——PM 重排裁决：统计与
// 勾选动作同位，不再驻留右栏卡头；高危行=左缘印泥朱 3px 规线，与列内「高危」旗行级双通道
// 同义；勾选行=金洗底 checkbox 行选中语义），右 8 栏=批量核对签署卡（「转抄签」文书脸
// .fuy-form + 提交钮墨实底全宽）→ 页脚批注（数据时点）。
// 双人核对的「高危/输血类缺第二核对人拒」业务规则单点归后端 IP-1016 把守（前端不复制拦截，
// 红*/占位文案仅承载视觉必填指示）；结构必填（勾选/转抄人）显式校验零出网。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹，
// 业务拒绝对象由 surfaceBizError 兜底展示 detail 原文——IP 域 4xx detail 已是中文业务口径）；
// 成功反馈=钤印 .fuy-stamp-toast（「已转抄 N 条」动词结果 ≤1200ms 收场，v-if 挂载制防
// stagger 误伤——入院登记台先例同构；失败永不盖印）。
// 病区清单换真数据源 listOrgs({type:'WARD'})（G4 假常量 WARD_OPTIONS 清零，本页零消费；
// 三态=在途骨架/失败恢复/空清单空态脸）。患者摘要按脱敏口径仅展示患者编号（后端 VO 契约
// 即不含姓名）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { ORDER_CLASS_OPTIONS, ORDER_TYPE_OPTIONS, transferWorklist } from '@/api/inpatient';
import type { TransferWorklistVO } from '@/api/inpatient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

// 元素权限判定入口（PR-4F F8）：提交批量核对码 v-perm 直挂提交钮
const auth = useAuthStore();

/* ==================== 病区清单（listOrgs WARD 档真数据面，G4） ==================== */
/** 病区下拉选项（listOrgs 真数据面；展示与出网值=orgCode） */
const wardOptions = ref<OrgVO[]>([]);
/** 当前病区（初值待清单装配裁决；切换即重载待转抄列表） */
const wardId = ref('');

/** 病区清单装载（三态经 useAsyncTask 承载：在途骨架 loading / 失败恢复 error / 空清单空态脸）；
 * 装载成功即裁决 wardId 初值——空/不在启用清单内一律回落首项（清单空则驻留空串，列表加载
 * 守卫拦截出网，防空病区码请求） */
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
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
 * @return true=清单就绪且存在可用病区（装配链据此决定是否续拉待转抄列表）；false=失败或空清单
 */
async function loadWardOptions(): Promise<boolean> {
  await wardLoad.run();
  return wardOptions.value.length > 0;
}

/* ==================== 待转抄列表 ==================== */
/** 勾选集合（批量提交 orderNos 顺序=列表序，保证出网稳定） */
const selectedNos = ref<Set<string>>(new Set());
/** 列表数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——只叙事实） */
const listLoadedAt = ref<string | null>(null);

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const loadedAtLabel = computed(() =>
  listLoadedAt.value === null ? '—' : formatTime(listLoadedAt.value),
);

/** 待转抄列表三段式（EX-49 范式，病区维度 AUDITED 医嘱聚合，开立时间倒序）：行集/总数/
 * 加载态经 usePagedList 收拢（固定首页 size 50 直出，分页语义不越页——「全选本页」只勾当页
 * 与此契约一致），病区经快照工厂发起时实时取值；成功后勾选集清空迁 onSuccess（失败驻留
 * 旧清单与旧勾选，行为与迁移前一致——失败弹错归响应拦截器） */
const {
  rows,
  total,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({ wardId: wardId.value }),
  fetcher: ({ wardId: ward, page, size }) => transferWorklist.list({ wardId: ward, page, size }),
  pageSize: 50,
  onSuccess: () => {
    selectedNos.value = new Set();
    listLoadedAt.value = new Date().toISOString();
  },
});

/** 列表刷新出口（病区上下文未就绪——清单在途/失败/空清单——时零出网，防空病区码请求） */
function refreshList(): void {
  if (wardId.value === '') {
    return;
  }
  void loadList();
}

/** 门牌病区切换：重载待转抄列表（守卫同上） */
function onWardChange(): void {
  refreshList();
}

/** 病区清单失败恢复重入：重载清单，就绪后续拉列表（说问题+给恢复通道） */
async function onWardRetry(): Promise<void> {
  if (await loadWardOptions()) {
    refreshList();
  }
}

/** 装配链：病区清单就绪后再拉待转抄列表（失败/空清单驻留恢复态/空态脸，零列表出网） */
async function bootWard(): Promise<void> {
  if (await loadWardOptions()) {
    void loadList();
  }
}

/** 「全选本页」选中态：当页行全在勾选集（空列表恒 false） */
const isAllPageSelected = computed(
  () =>
    rows.value.length > 0 && rows.value.every((row) => selectedNos.value.has(row.orderNo ?? '')),
);

/** 全选本页切换：只勾当页（分页语义不越页——勾选集恒⊆当页清单，清单重载即清空，
 * 故取消全选=整集清空）；勾选是纯本地动作零出网 */
function toggleSelectAll(): void {
  if (isAllPageSelected.value) {
    selectedNos.value = new Set();
  } else {
    selectedNos.value = new Set(rows.value.map((row) => row.orderNo ?? ''));
  }
}

/** 行勾选切换（native checkbox，jsdom 可测） */
function toggleSelect(row: TransferWorklistVO): void {
  const orderNo = row.orderNo ?? '';
  const next = new Set(selectedNos.value);
  if (next.has(orderNo)) {
    next.delete(orderNo);
  } else {
    next.add(orderNo);
  }
  selectedNos.value = next;
}

/** 行勾选态（checkbox 回显） */
function isChecked(row: TransferWorklistVO): boolean {
  return selectedNos.value.has(row.orderNo ?? '');
}

/** 行级契约类：高危行=左缘印泥朱 3px 规线（行级扫读双通道，与列内「高危」旗同义）；
 * 勾选行=金洗底 checkbox 行选中语义（取色全走 --fuy-* 语义通道） */
function rowClass({ row }: { row: TransferWorklistVO }): string {
  return [
    row.highRisk === true ? 'transfer-highrisk-row' : '',
    isChecked(row) ? 'transfer-row-selected' : '',
  ]
    .filter(Boolean)
    .join(' ');
}

/** 医嘱类型中文词表反查（列表类型列） */
function orderTypeLabel(code: string | undefined): string {
  return ORDER_TYPE_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/** 医嘱分类中文词表反查（列表分类列） */
function orderClassLabel(code: string | undefined): string {
  return ORDER_CLASS_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/* ==================== 批量核对（右栏签署卡） ==================== */
const submitting = ref(false);
const checkForm = ref({ transferNurseId: '', secondCheckerId: '', conclusion: 'PASSED' });

/** 勾选中含高危行（第二核对人红*指示源） */
const hasHighRisk = computed(() =>
  rows.value.some((row) => row.highRisk === true && selectedNos.value.has(row.orderNo ?? '')),
);

/* ---------- 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ---------- */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=入院登记台先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认；失败与业务拒绝归 message 批注条/拦截器，钤印永不承失败） */
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

/**
 * 提交批量核对：结构必填显式校验（勾选/转抄人，零出网）→ 出网（业务规则归后端把守：
 * 高危缺第二核对人 IP-1016，detail 原文透出）→ 成功盖钤印「已转抄 N 条」并刷新列表。
 * 入口在途早退守卫防双击重复提交；orderNos 出网顺序=列表序（列表序契约）。
 */
async function onSubmitCheck(): Promise<void> {
  if (submitting.value) {
    return;
  }
  if (selectedNos.value.size === 0) {
    void ElMessage.warning('请先勾选待转抄医嘱');
    return;
  }
  if (checkForm.value.transferNurseId.trim() === '') {
    void ElMessage.warning('请填写转抄人工号');
    return;
  }
  submitting.value = true;
  try {
    // 出网顺序=列表序契约：rows.map 保序过滤勾选集（「全选本页」与逐行勾选同走此出口）
    const orderNos = rows.value
      .map((row) => row.orderNo ?? '')
      .filter((no) => selectedNos.value.has(no));
    await transferWorklist.check({
      orderNos,
      transferNurseId: checkForm.value.transferNurseId.trim(),
      conclusion: checkForm.value.conclusion === 'REJECTED' ? 'REJECTED' : 'PASSED',
      secondCheckerId:
        checkForm.value.secondCheckerId.trim() === ''
          ? undefined
          : checkForm.value.secondCheckerId.trim(),
    });
    showStamp(`已转抄 ${orderNos.length} 条`);
    checkForm.value.secondCheckerId = '';
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    submitting.value = false;
  }
}

/* ---------- 门牌批注行：转抄人（会话身份） · 时刻（本地时钟零出网） ---------- */
/** 转抄人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

onMounted(() => {
  void bootWard();
});
</script>

<template>
  <div class="fuy-page transfer-worklist">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——入院
         登记台 admission-flow 同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，
         纵排与区块间距（门牌 0 / 两栏 1 / 页脚 2 三段级联序）由本层同值承接（.fuy-page
         同构 flex+gap），保证视觉零漂移 -->
    <div class="fuy-stagger transfer-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（病区真数据下拉 +
           「已选 N」墨底徽标 + 刷新）+ 批注行（原 hint 文案逐字保留，「谁·何时」齐备）+
           2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">转抄工作台</h1>
          <span class="fuy-page-en" aria-hidden="true">TRANSCRIPTION</span>
        </div>
        <div class="fuy-page-status transfer-head-controls">
          <select
            v-model="wardId"
            class="transfer-ward-select"
            aria-label="病区选择"
            :disabled="wardDisabled"
            @change="onWardChange"
          >
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
              {{ org.orgName }}（{{ org.orgCode }}）
            </option>
          </select>
          <!-- 已选 N 墨底徽标（勾选统计上行至门牌状态位，蓝图乙-4.2①；数字 tabular） -->
          <span class="transfer-selected-badge" aria-label="已选数量"
            >已选 <span class="fuy-num">{{ selectedNos.size }}</span> 条</span
          >
          <el-button :loading="listLoading" @click="refreshList">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          病区 AUDITED 医嘱聚合 · 高危/输血类须双人核对 · 转抄人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 两栏（el-row gutter 16，lg 16/8 既有断点） -->
      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：待转抄列表卡（卷宗卡 + .fuy-dense 密排——挂卡容器构成后代关系） -->
        <el-col :md="24" :lg="16">
          <section class="fuy-card fuy-dense transfer-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">待转抄列表</h2>
              <!-- 全选本页与计数同位（PM 重排裁决：统计与勾选动作同处列表卡头，不再驻留
                   右栏卡头远离勾选动作发生地）；只勾当页（分页语义不越页） -->
              <div class="transfer-select-all-group">
                <label class="transfer-select-all">
                  <input
                    type="checkbox"
                    :checked="isAllPageSelected"
                    :disabled="rows.length === 0"
                    aria-label="全选本页"
                    @change="toggleSelectAll"
                  />
                  全选本页
                </label>
                <span class="transfer-list-count"
                  >已选 <span class="fuy-num">{{ selectedNos.size }}</span> · 共
                  <span class="fuy-num">{{ total }}</span> 条</span
                >
              </div>
            </header>
            <div class="fuy-card-body transfer-list-body">
              <!-- 病区清单失败=说问题给恢复（G4/G6：不伪装空选项），重新加载同路恢复列表 -->
              <div v-if="wardOptionsError" class="transfer-ward-error" role="alert">
                <span>病区清单加载失败，暂无法加载待转抄列表</span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :loading="wardOptionsLoading"
                  @click="onWardRetry"
                  >重新加载</el-button
                >
              </div>
              <!-- 病区清单在途：纸感骨架占位（.fuy-sk 静态块零 shimmer，min-height 锁 CLS） -->
              <div v-else-if="wardOptionsLoading" class="transfer-ward-skeleton" aria-hidden="true">
                <span class="fuy-sk fuy-sk-table-row"></span>
                <span class="fuy-sk fuy-sk-table-row"></span>
                <span class="fuy-sk fuy-sk-table-row"></span>
                <span class="fuy-sk fuy-sk-table-row"></span>
              </div>
              <!-- 空清单=诚实空态脸（G6：暂无〈业务客体〉+ 下一步，非「暂无数据」裸词） -->
              <div v-else-if="wardOptions.length === 0" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无启用病区</p>
                <p class="fuy-empty-hint">未获取到启用病区清单，请联系管理员配置后重试。</p>
              </div>
              <div v-else v-loading="listLoading" class="transfer-list-table-wrap">
                <el-table
                  v-if="rows.length > 0"
                  :data="rows"
                  class="fuy-dense"
                  :row-class-name="rowClass"
                >
                  <el-table-column label="勾选" width="48">
                    <template #default="{ row }">
                      <input
                        type="checkbox"
                        :checked="isChecked(row)"
                        :aria-label="`勾选 ${row.orderNo}`"
                        @change="toggleSelect(row)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column label="医嘱号" min-width="130">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.orderNo }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="就诊号" min-width="120">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.visitId }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="患者编号（脱敏）" min-width="140">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.patientId }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="类型" width="70">
                    <template #default="{ row }">
                      {{ orderTypeLabel(row.orderType) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="分类" width="60">
                    <template #default="{ row }">
                      {{ orderClassLabel(row.orderClass) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="频次" width="60">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.freqCode ?? '—' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="开立" width="100">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ formatTime(row.orderedAt) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="高危" width="80">
                    <template #default="{ row }">
                      <!-- 高危药红色标识（双人核对强制面指示；机器判据类；行级另有左缘
                           印泥朱 3px 规线 transfer-highrisk-row——行级双通道同义） -->
                      <span v-if="row.highRisk === true" class="transfer-highrisk-flag">高危</span>
                      <span v-else>—</span>
                    </template>
                  </el-table-column>
                </el-table>
                <!-- 诚实空态脸（契约 ⑥/⑧：暂无〈业务客体〉+ 给下一步，el-empty 纸箱插画零出现） -->
                <div v-else class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无待转抄医嘱</p>
                  <p class="fuy-empty-hint">当前病区暂无待转抄记录，可切换病区或点击刷新。</p>
                </div>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：批量核对签署卡（「转抄签」文书脸：.fuy-form 挂脸 + 提交钮墨实底全宽） -->
        <el-col :md="24" :lg="8">
          <section class="fuy-card transfer-sign-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">批量核对</h2>
              <span class="transfer-sign-note">高危/输血类须第二核对人</span>
            </header>
            <div class="fuy-card-body">
              <el-form class="fuy-form" label-position="top" size="small">
                <label class="transfer-field-label" for="transfer-nurse">转抄人工号</label>
                <input
                  id="transfer-nurse"
                  v-model="checkForm.transferNurseId"
                  class="transfer-input"
                  placeholder="转抄护士工号"
                  aria-label="转抄人工号"
                />
                <label class="transfer-field-label" for="transfer-second"
                  >第二核对人
                  <!-- 高危红*：双人核对强制面视觉指示（规则后端 IP-1016 把守，显隐与 hasHighRisk 联动） -->
                  <span v-if="hasHighRisk" class="transfer-second-required" aria-label="高危必填"
                    >*</span
                  ></label
                >
                <input
                  id="transfer-second"
                  v-model="checkForm.secondCheckerId"
                  class="transfer-input"
                  :placeholder="hasHighRisk ? '高危医嘱必填第二核对人' : '第二核对人（高危必填）'"
                  aria-label="第二核对人"
                />
                <label class="transfer-field-label">核对结论</label>
                <!-- 结论 radio 选中=墨（契约 ⑤#13 映射自动承继，零额外规则） -->
                <div class="transfer-radio-row">
                  <label class="transfer-radio-item">
                    <input v-model="checkForm.conclusion" type="radio" value="PASSED" />
                    核对通过
                  </label>
                  <label class="transfer-radio-item">
                    <input v-model="checkForm.conclusion" type="radio" value="REJECTED" />
                    核对不通过
                  </label>
                </div>
                <!-- 提交批量核对（PR-4F #18）：医生核对码 v-perm 直挂——与 submitting 在途数据态
                     :disabled 正交叠加（权限决定在不在 DOM，在途决定可不可点） -->
                <el-button
                  v-perm="'inpatient:transfer:btn:check'"
                  type="primary"
                  class="transfer-submit"
                  :loading="submitting"
                  :disabled="submitting"
                  @click="onSubmitCheck"
                  >提交批量核对</el-button
                >
              </el-form>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 页脚批注（契约 ⑧.8）：数据时点=最近一次成功加载时点 + 手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ loadedAtLabel }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认「已转抄 N 条」；v-if 挂载制=入院登记台先例
           同构——防 stagger 误伤常驻泄漏，seq 递增重挂重放落章动画，≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块）：本层成为
   .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距（门牌 0 / 两栏 1 / 页脚 2 三段
   级联序）由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.transfer-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：病区下拉/已选徽标/刷新钮右挂同行（.fuy-page-status 全局段只承载
   margin-left:auto，补 flex 同行） */
.transfer-head-controls {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 病区切换（native select 与 EP 密度口径对齐）：围合取值直取 --fuy-* 语义通道（契约 ⑤#2
   输入围合=最亮纸白底+石规描边，五页表单基元同形；石规与 EP border 映射同源） */
.transfer-ward-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 已选 N 墨底徽标（门牌状态位）：墨实底纸字=统计上行语义，色值全走语义 token */
.transfer-selected-badge {
  padding: 1px 10px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-text-emphasis);
  color: var(--fuy-paper);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  white-space: nowrap;
}

/* 列表卡头右侧组：全选本页勾选（动作）+ 已选/共条计数（统计）——同位承载（PM 重排裁决） */
.transfer-select-all-group {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
}

.transfer-select-all {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  cursor: pointer;
}

.transfer-list-count {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 列表卡体 CLS 锁（蓝图乙-4.6：骨架 min-height 240px）——加载/空态切换零塌陷 */
.transfer-list-body {
  box-sizing: border-box;
  min-height: 240px;
}

/* 病区清单在途骨架：纸感静态块纵排（.fuy-sk 全局族，零 shimmer 常驻动画） */
.transfer-ward-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
}

/* 高危行左缘印泥朱 3px 规线（行级扫读双通道，与列内「高危」旗同义）：inset 盒影承载
   左缘规线，取色走 --fuy-color-danger-text 状态朱语义通道（零新 hex）。规线落首列 td 层
   （inset 盒影绘制于该 td 自身背景之上）——EP 行悬停底（fill-lighter 半透明洗）与勾选
   金洗底（brand-100 实色）均画在 td 背景层，规线若画在 tr 层会被 td 背景盖住（悬停洗淡、
   勾选完全遮没），td 层承载则悬停/选中态规线恒在上层可见 */
.transfer-list-card :deep(tr.transfer-highrisk-row td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--fuy-color-danger-text);
}

/* 勾选行金洗底（checkbox 行选中语义）：brand-100 金洗纸（选中态同源，与卡片选中态一致） */
.transfer-list-card :deep(tr.transfer-row-selected) td {
  background: var(--fuy-palette-brand-100);
}

/* 病区清单失败恢复行（说问题给恢复：状态朱文字+重新加载动作，G6） */
.transfer-ward-error {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 高危红色标识（红字描边弱底，双人核对强制面指示） */
.transfer-highrisk-flag {
  padding: 0 var(--fuy-space-1);
  border: 1px solid var(--fuy-color-danger-text);
  border-radius: var(--fuy-radius-sm);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 签署卡卡头副注（12px 灰墨，卡头右侧随 .fuy-card-head 空间分布） */
.transfer-sign-note {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 签署表单基元（token 取色；native input 与 EP 密度口径对齐，jsdom 可测） */
.transfer-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.transfer-input {
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

/* 高危红*（视觉必填指示，显隐与 hasHighRisk 联动） */
.transfer-second-required {
  color: var(--fuy-color-danger-text);
  font-weight: 700;
}

.transfer-radio-row {
  display: flex;
  gap: var(--fuy-space-4);
  margin-bottom: var(--fuy-space-3);
}

.transfer-radio-item {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 提交钮墨实底全宽（「转抄签」文书脸收笔；type=primary 墨实底经映射自动承继） */
.transfer-submit {
  width: 100%;
}
</style>
