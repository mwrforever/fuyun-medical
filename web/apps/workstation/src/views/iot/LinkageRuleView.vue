<script setup lang="ts">
// 联动规则页（/iot/linkage-rules，M16 联动治理面前端面 · 暖纸卷宗换脸重排 丙-6，换脸不换
// 业务）：门牌页首（衬线标题 + FAILED 计数印泥朱胶囊——前端自执行日志行集过滤派生零出网 +
// 批注行「触发源 × 动作类型治理 · 命中执行归联动引擎 · 失败可重试 · 谁·时刻」）+ 左 14 栏
// 规则列表卷宗卡（触发源三源徽标/动作类型词表/目标病区/启用态；新建钮卡头）+ 右 10 栏执行
// 日志卷宗卡（FAILED 行=左缘印泥朱 3px 规线+行级「重试」link——失败行行级视觉与动作双通道，
// PM 重排裁决）+ 规则 CRUD 弹窗（640px 挂 .fuy-dialog、表单容器挂 .fuy-form；触发源与动作
// 类型下拉走冻结词表；触发条件/动作配置 JSON 显式 try/catch 校验禁裸 parse，非法零出网；
// enabled 缺省开；未保存草稿守卫 before-close 全通道）。规则命中与动作投递全归后端联动引擎，
// 前端只承载治理面。
// 换脸不换业务：规则 CRUD 状态机/JSON 配置/草稿守卫/removingId/retryingNo 在途守卫/重试链
// 零变动；全部写操作自带在途守卫（入口早退先于一切 await）。成功反馈=钤印（已保存/已重试，
// 契约 ⑧.5 v-if 挂载制，失败与校验拦截=ElMessage 批注条——钤印永不承失败；删除成功反馈仍
// 归 message，蓝图丙-6.4 未裁改）。目标病区下拉=listOrgs({type:'WARD'}) 真数据三态（册级
// 总则 G4：ward.ts WARD_OPTIONS 跨域假常量清零，本页零消费——目标病区可空=全院为合法业务
// 语义，失败态不阻断全院规则写入，禁自动落首项）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  ACTION_TYPE_LABELS,
  LINKAGE_RESULT_LABELS,
  TRIGGER_SOURCE_LABELS,
  linkageLogs,
  linkageRules,
} from '@/api/iot';
import type { LinkageLogVO, LinkageRuleVO, SaveLinkageRuleRequest } from '@/api/iot';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAuthStore } from '@/stores/auth';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ==================== 门牌页首批注行（「谁 · 时刻」） ==================== */

/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=入院登记台先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认——已保存/已重试；失败与业务拒绝归 message 批注条/拦截器，钤印
 * 永不承失败） */
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
  // 收场定时器仅翻转内部展示 ref（零 DOM/出网副作用），卸载清理防迟触发
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

/** 触发源中文词表反查（触发源列徽标） */
function triggerSourceLabel(code: string | undefined): string {
  return TRIGGER_SOURCE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 动作类型中文词表反查（动作类型列） */
function actionTypeLabel(code: string | undefined): string {
  return ACTION_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 触发源徽标状态类（fuy-rule-tag--{source} 契约类，色值经语义 token 承载） */
function triggerSourceClass(code: string | undefined): string {
  return `fuy-rule-tag--${(code ?? '').toLowerCase()}`;
}

/** 执行结果中文词表反查（结果列徽标） */
function resultLabel(code: string | undefined): string {
  return LINKAGE_RESULT_LABELS[code ?? ''] ?? code ?? '—';
}

/** 执行结果徽标状态类（fuy-linkage-tag--{result} 契约类） */
function resultClass(code: string | undefined): string {
  return `fuy-linkage-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 规则列表 ==================== */
const rows = ref<LinkageRuleVO[]>([]);

/** 页脚数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——G8 只叙事实） */
const loadedAt = ref<string | null>(null);

/** 加载规则列表（量小全量直出，无分页）：loading 骨架经 useAsyncTask 收拢（EX-42 范式
 * 迁移，行为与迁移前一致——失败弹错归响应拦截器；驻留旧清单；成功分支回填页脚数据时点） */
const { loading: listLoading, run: loadList } = useAsyncTask(async () => {
  rows.value = await linkageRules.list();
  loadedAt.value = new Date().toISOString();
});

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const loadedAtLabel = computed(() => (loadedAt.value === null ? '—' : formatTime(loadedAt.value)));

/* ==================== 执行日志 ==================== */
/** 结果筛选（空串=全部三态） */
const resultFilter = ref('');

/** 日志首拉落地标志（FAILED 计数胶囊初载骨架态判据：落地前禁伪 0 值——AlarmRuleView 先例
 * 同构；落地后恒显诚实计数含 0） */
const logLoaded = ref(false);

/** 加载执行日志（ruleId/triggerSource/actionResult 过滤由后端承载）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错
 * 归响应拦截器；驻留旧清单；成功分支翻转首拉落地标志） */
const {
  rows: logRows,
  loading: logLoading,
  fetch: loadLogs,
} = usePagedList({
  params: () => ({
    actionResult:
      resultFilter.value === '' ? undefined : (resultFilter.value as LinkageLogVO['actionResult']),
  }),
  fetcher: ({ actionResult, page, size }) => linkageLogs.page({ actionResult, page, size }),
  pageSize: 50,
  onSuccess: () => {
    logLoaded.value = true;
  },
});

/** FAILED 计数（蓝图丙-6.2：门牌印泥朱胶囊——前端自已加载日志行集过滤 FAILED 派生，零出网；
 * 重试/结果筛选重拉后自然派生更新，失败量免扫表） */
const failedLogCount = computed(
  () => logRows.value.filter((row) => row.actionResult === 'FAILED').length,
);

/** 执行日志行级规线类（蓝图丙-6.2 PM 重排裁决：FAILED 行=左缘印泥朱 3px 规线——行级视觉
 * 与行内「重试」link 动作双通道；成功/待执行行零规线类） */
function logRowClass({ row }: { row: LinkageLogVO }): string {
  return row.actionResult === 'FAILED' ? 'fuy-linkage-row--failed' : '';
}

/** 门牌刷新（规则与执行日志双拉：FAILED 计数胶囊派生自日志行集，刷新须同步日志源防陈旧计数） */
function refreshAll(): void {
  void loadList();
  void loadLogs();
}

/** 重试在途行锚点（null=无在途；行级守卫防双击窗口内重复投递联动动作——病区播报/护理任务
 * 类副作用真实发生、retryCount 双计） */
const retryingNo = ref<string | null>(null);

/**
 * 重试失败联动（按 linkageNo 重投动作；重试计数累加由后端承载）：行级在途守卫（进入即判/
 * finally 复位）防双击窗口内重复投递；成功反馈=钤印「已重试」（蓝图丙-6.4）。
 *
 * @param row 重试目标日志行
 */
async function onRetry(row: LinkageLogVO): Promise<void> {
  if (row.linkageNo === undefined || retryingNo.value !== null) {
    return;
  }
  retryingNo.value = row.linkageNo;
  try {
    await linkageLogs.retry(row.linkageNo);
    showStamp('已重试');
    await loadLogs();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    retryingNo.value = null;
  }
}

/* ==================== 规则 CRUD 弹窗 ==================== */
const dialogVisible = ref(false);
const saving = ref(false);
/** 编辑目标（null=新建态；承载 id 供 update 整单替换） */
const editingId = ref<string | null>(null);
const form = ref({
  ruleName: '',
  triggerSource: 'ALARM_TRIGGERED',
  triggerConditionText: '{}',
  actionType: 'NOTIFY',
  actionConfigText: '',
  targetWardId: '',
  enabled: true,
});

/* ---------- 目标病区下拉（G4：ward.ts WARD_OPTIONS 跨域假常量清零 → listOrgs WARD 档
 * 真数据三态；本页 ward.ts 零消费） ---------- */
/** 病区选项（启用机构清单，sort 升序；目标病区可空=全院为合法业务态） */
const wardOptions = ref<OrgVO[]>([]);
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
});
const wardLoading = wardLoad.loading;
const wardError = wardLoad.error;

/** 病区下拉禁用态：仅清单在途禁用（失败态「全院」空值仍可选——目标病区可空业务语义，
 * 失败不阻断全院规则写入，与绑定表单强制病区语义分叉） */
const wardDisabled = computed(() => wardLoading.value);

/** 病区清单重试（失败恢复同路；装载与重试共用汇合点） */
function onWardRetry(): void {
  void wardLoad.run();
}

/**
 * JSON 文本显式解析（禁裸 parse）：空串视为无配置（undefined）；非空须为合法 JSON 对象
 * （数组/标量拒绝）。
 *
 * @param text 文本框原文（用户输入，来源不可信）
 * @return 合法对象或 undefined（空串）；非法返回 null（调用方零出网拦截）
 */
function parseJsonObject(text: string): Record<string, unknown> | undefined | null {
  const trimmed = text.trim();
  if (trimmed === '') {
    return undefined;
  }
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 打开新建弹窗（空表单，enabled 缺省开；目标病区缺省「全院」空值） */
function openCreate(): void {
  editingId.value = null;
  form.value = {
    ruleName: '',
    triggerSource: 'ALARM_TRIGGERED',
    triggerConditionText: '{}',
    actionType: 'NOTIFY',
    actionConfigText: '',
    targetWardId: '',
    enabled: true,
  };
  // 未保存草稿守卫基准（EX-46/FE-A2-06）：表单相对打开时的快照偏离即视为脏
  formSnapshot = JSON.stringify(form.value);
  dialogVisible.value = true;
}

/** 打开编辑弹窗（预填回显原规则值；JSON 对象格式化为两空格缩进便于编辑） */
function openEdit(row: LinkageRuleVO): void {
  editingId.value = row.id ?? null;
  form.value = {
    ruleName: row.ruleName ?? '',
    triggerSource: row.triggerSource ?? 'ALARM_TRIGGERED',
    triggerConditionText:
      row.triggerCondition === undefined ? '' : JSON.stringify(row.triggerCondition, null, 2),
    actionType: row.actionType ?? 'NOTIFY',
    actionConfigText:
      row.actionConfig === undefined ? '' : JSON.stringify(row.actionConfig, null, 2),
    targetWardId: row.targetWardId ?? '',
    enabled: row.enabled ?? true,
  };
  // 未保存草稿守卫基准（EX-46/FE-A2-06）
  formSnapshot = JSON.stringify(form.value);
  dialogVisible.value = true;
}

/** 弹窗打开时的表单快照（未保存草稿守卫比对基准；保存成功路径不走守卫直接关窗） */
let formSnapshot = '';

/** 关闭前未保存草稿守卫（EX-46/FE-A2-06）：表单相对打开时有改动 → 显式确认丢弃后才
 * 放行关闭，防误触（取消按钮/右上角 X）丢草稿；无改动直接放行零打扰。文案三段
 * （消息/标题/按钮）逐字冻结。
 *
 * @return true=允许关闭（无改动或已确认丢弃）；false=驻留弹窗保留草稿
 */
async function confirmDiscardAndClose(): Promise<boolean> {
  if (JSON.stringify(form.value) === formSnapshot) {
    return true;
  }
  try {
    await ElMessageBox.confirm('规则表单已有未保存修改，关闭将丢弃这些内容', '未保存提醒', {
      type: 'warning',
      confirmButtonText: '丢弃并关闭',
      cancelButtonText: '继续编辑',
    });
    return true;
  } catch {
    // 拒绝丢弃：弹窗驻留，草稿保留
    return false;
  }
}

/** footer 取消按钮关闭入口（走未保存草稿守卫） */
async function onCancelDialog(): Promise<void> {
  if (await confirmDiscardAndClose()) {
    dialogVisible.value = false;
  }
}

/** 弹窗 before-close 入口（右上角 X/ESC/遮罩点击；与取消按钮同守卫） */
function onDialogBeforeClose(done: () => void): void {
  void confirmDiscardAndClose().then((allowed) => {
    if (allowed) {
      done();
    }
  });
}

/**
 * 保存规则：必填与 JSON 显式校验（零出网）→ 出网（新建/整单替换）→ 关窗刷新；成功反馈=
 * 钤印「已保存」（蓝图丙-6.4，新建/编辑同族）。病区清单失败态不阻断保存（「全院」空值=
 * 合法业务态，targetWardId 落 undefined）。
 */
async function onSave(): Promise<void> {
  if (saving.value) {
    return;
  }
  if (form.value.ruleName.trim() === '') {
    void ElMessage.warning('请填写规则名称');
    return;
  }
  const triggerCondition = parseJsonObject(form.value.triggerConditionText);
  if (triggerCondition === null) {
    void ElMessage.warning('触发条件须为合法 JSON 对象，请修正后再试');
    return;
  }
  const actionConfig = parseJsonObject(form.value.actionConfigText);
  if (actionConfig === null) {
    void ElMessage.warning('动作配置须为合法 JSON 对象，请修正后再试');
    return;
  }
  saving.value = true;
  const payload = {
    ruleName: form.value.ruleName.trim(),
    triggerSource: form.value.triggerSource as SaveLinkageRuleRequest['triggerSource'],
    triggerCondition: triggerCondition ?? {},
    actionType: form.value.actionType as SaveLinkageRuleRequest['actionType'],
    actionConfig,
    targetWardId:
      form.value.targetWardId.trim() === '' ? undefined : form.value.targetWardId.trim(),
    enabled: form.value.enabled,
  };
  try {
    if (editingId.value === null) {
      await linkageRules.create(payload);
    } else {
      await linkageRules.update(editingId.value, payload);
    }
    // 成功=钤印「已保存」（蓝图丙-6.4：文案=动词结果，≤1200ms 收场）
    showStamp('已保存');
    dialogVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    saving.value = false;
  }
}

/** 删除在途行锚点（null=无在途；行级守卫防双击重复提交删除） */
const removingId = ref<string | null>(null);

/**
 * 删除规则（规则删除后不再命中；历史执行日志保留由后端承载）：出网前显式确认带回显
 * 摘要（EX-46/FE-A2-05，范式对齐 AlarmRuleView.onRemoveRule）——删除为不可逆操作，
 * 误触不得直接出网；行级在途守卫（进入即判/finally 复位）覆盖确认弹窗期与出网期，
 * 防双击窗口内重复出网。删除成功反馈仍归 message 批注条（蓝图丙-6.4 未裁改）。
 *
 * @param row 删除目标规则行
 */
async function onRemove(row: LinkageRuleVO): Promise<void> {
  if (row.id === undefined || removingId.value !== null) {
    return;
  }
  // 在途锚点先于确认置位：确认弹窗打开期间的双击同样被守卫拦截
  removingId.value = row.id;
  try {
    try {
      await ElMessageBox.confirm(
        `即将删除规则「${row.ruleName ?? row.id}」，删除后该规则不再命中执行，确认？`,
        '规则删除确认',
        { confirmButtonText: '确认删除', cancelButtonText: '取消' },
      );
    } catch {
      // 用户取消：零出网，守卫经 finally 复位可再次发起
      return;
    }
    await linkageRules.remove(row.id);
    void ElMessage.success(`规则已删除：${row.ruleName ?? row.id}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    removingId.value = null;
  }
}

onMounted(() => {
  void loadList();
  void loadLogs();
  void wardLoad.run();
});
</script>

<template>
  <div class="fuy-page linkage-rule">
    <!-- 页内级联容器（契约 ⑦.4）：页面根节点禁挂 .fuy-stagger——stagger 挂本层区块
         （product-flow/binding-flow 同构形态），本层承接 flex+gap 承担纵排与区块间距；
         级联序：门牌 0 / 两栏 1 / 页脚 2 -->
    <div class="fuy-stagger linkage-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（FAILED 计数印泥朱
           胶囊 + 刷新）+ 批注行 + 2px 墨规收底（脸归全局族） -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">联动规则</h1>
          <span class="fuy-page-en" aria-hidden="true">LINKAGE</span>
        </div>
        <div class="fuy-page-status">
          <!-- FAILED 计数（蓝图丙-6.2：前端自日志行集过滤 FAILED 派生零出网）：
               首拉未落地以静态骨架占位（禁伪 0 值），落地后恒显诚实计数（含 0） -->
          <span v-if="!logLoaded" class="fuy-sk linkage-failed-sk" aria-hidden="true"></span>
          <span v-else class="fuy-status-pill fuy-status-pill--danger"
            >FAILED {{ failedLogCount }}</span
          >
          <el-button :loading="listLoading || logLoading" @click="refreshAll">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          触发源 × 动作类型治理 · 命中执行归联动引擎 · 失败可重试 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 两栏 lg 14/10（既有）：左规则列表卷宗卡 / 右执行日志卷宗卡 -->
      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：规则列表卡（.fuy-dense 密排挂卡容器构成后代关系；量小全量直出） -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense linkage-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">规则列表</h2>
              <div class="linkage-card-tools">
                <span class="linkage-card-note">共 {{ rows.length }} 条</span>
                <!-- 新建规则（PR-4F #36，IOT_ADMIN 绑定）v-perm 直挂——无码 DOM 移除（D-34）；
                     编辑/删除/重试同码收口联动规则全生命周期写面；规则弹窗「保存规则」提交
                     随容器免挂接（入口隐藏即弹窗不可达） -->
                <el-button
                  v-perm="'iot:linkage:btn:manage'"
                  type="primary"
                  size="small"
                  @click="openCreate"
                  >新建规则</el-button
                >
              </div>
            </header>
            <div v-loading="listLoading" class="fuy-card-body linkage-list-body">
              <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
                <el-table-column label="规则名称" min-width="150">
                  <template #default="{ row }">{{ row.ruleName }}</template>
                </el-table-column>
                <el-table-column label="触发源" width="96">
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      class="fuy-tag-aa"
                      :class="triggerSourceClass(row.triggerSource)"
                    >
                      {{ triggerSourceLabel(row.triggerSource) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="动作类型" width="96">
                  <template #default="{ row }">{{ actionTypeLabel(row.actionType) }}</template>
                </el-table-column>
                <el-table-column label="目标病区" width="80">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.targetWardId ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="启用" width="70">
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      class="fuy-tag-aa"
                      :class="row.enabled ? 'fuy-rule-tag--enabled' : 'fuy-rule-tag--disabled'"
                    >
                      {{ row.enabled ? '启用' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="110" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <!-- 编辑/删除（PR-4F #36）与新建同码 v-perm 直挂——编辑弹窗提交随容器；
                         删除在途 :loading/:disabled 数据态与权限判定两层正交 -->
                    <el-button
                      v-perm="'iot:linkage:btn:manage'"
                      link
                      type="primary"
                      size="small"
                      @click="openEdit(row)"
                      >编辑</el-button
                    >
                    <el-button
                      v-perm="'iot:linkage:btn:manage'"
                      link
                      type="danger"
                      size="small"
                      :loading="removingId === row.id"
                      :disabled="removingId !== null"
                      @click="onRemove(row)"
                      >删除</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（G6/契约 ⑥）：主句「暂无联动规则」+ 说明给下一步；默认纸箱插画在
                   .fuy-page 子树由全局段压制，本页走自绘空态脸零插画 -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无联动规则</p>
                <p class="fuy-empty-hint">
                  点击卡头「新建规则」建立第一条联动规则，触发源命中后由联动引擎执行。
                </p>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：执行日志卡（FAILED 行=左缘印泥朱规线+行级重试 link，失败可扫可处置） -->
        <el-col :md="24" :lg="10">
          <section class="fuy-card fuy-dense linkage-log-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">执行日志</h2>
              <div class="linkage-card-tools">
                <span class="linkage-card-note">共 {{ logRows.length }} 条</span>
                <select
                  v-model="resultFilter"
                  class="linkage-result-select"
                  aria-label="执行结果筛选"
                  @change="loadLogs"
                >
                  <option value="">全部结果</option>
                  <option v-for="(label, code) in LINKAGE_RESULT_LABELS" :key="code" :value="code">
                    {{ label }}
                  </option>
                </select>
              </div>
            </header>
            <div v-loading="logLoading" class="fuy-card-body linkage-log-body">
              <el-table
                v-if="logRows.length > 0"
                :data="logRows"
                class="fuy-dense linkage-log-table"
                size="small"
                :row-class-name="logRowClass"
              >
                <el-table-column label="联动号" min-width="120">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.linkageNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="触发源" width="88">
                  <template #default="{ row }">{{
                    triggerSourceLabel(row.triggerSource)
                  }}</template>
                </el-table-column>
                <el-table-column label="动作" width="88">
                  <template #default="{ row }">{{ actionTypeLabel(row.actionType) }}</template>
                </el-table-column>
                <el-table-column label="结果" width="72">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa" :class="resultClass(row.actionResult)">
                      {{ resultLabel(row.actionResult) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="重试" width="56">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.retryCount ?? 0 }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="执行时间" width="96">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.executedAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="错误信息" min-width="110">
                  <template #default="{ row }">
                    <span :title="row.errorMsg">{{ row.errorMsg ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="64" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <!-- 失败重试（PR-4F #36）与规则写面同码 v-perm 直挂——FAILED v-if 状态机
                         数据态与权限判定两层正交；在途守卫防重复投递联动动作 -->
                    <el-button
                      v-if="row.actionResult === 'FAILED'"
                      v-perm="'iot:linkage:btn:manage'"
                      link
                      type="primary"
                      size="small"
                      :loading="retryingNo === row.linkageNo"
                      :disabled="retryingNo !== null"
                      @click="onRetry(row)"
                      >重试</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（G6/契约 ⑥）：主句「暂无执行日志」+ 说明给下一步 -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无执行日志</p>
                <p class="fuy-empty-hint">
                  联动引擎命中执行后日志在此落档；可调整结果筛选或点击刷新。
                </p>
              </div>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ③ 规则 CRUD 弹窗（新建/编辑共用；挂 .fuy-dialog=衬线标题+头部发丝全局脸，640px
           宽档；表单容器挂 .fuy-form 承文书脸；触发条件与动作配置 JSON 显式校验；X/ESC/取消
           均走未保存草稿守卫 EX-46/FE-A2-06） -->
      <el-dialog
        v-model="dialogVisible"
        :title="editingId === null ? '新建联动规则' : '编辑联动规则'"
        width="640px"
        class="fuy-dialog"
        :before-close="onDialogBeforeClose"
      >
        <div class="fuy-form linkage-form">
          <label class="linkage-field-label">规则名称（必填）</label>
          <input
            v-model="form.ruleName"
            class="linkage-input"
            placeholder="如：危急告警转呼叫"
            aria-label="规则名称"
          />
          <div class="linkage-field-grid">
            <div class="linkage-field">
              <label class="linkage-field-label">触发源</label>
              <select v-model="form.triggerSource" class="linkage-input" aria-label="触发源">
                <option v-for="(label, code) in TRIGGER_SOURCE_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
            <div class="linkage-field">
              <label class="linkage-field-label">动作类型</label>
              <select v-model="form.actionType" class="linkage-input" aria-label="动作类型">
                <option v-for="(label, code) in ACTION_TYPE_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </div>
          <label class="linkage-field-label">触发条件 JSON（键值对形态）</label>
          <textarea
            v-model="form.triggerConditionText"
            class="linkage-input"
            rows="3"
            placeholder='如：{"level":"CRITICAL"}'
            aria-label="触发条件 JSON"
          ></textarea>
          <label class="linkage-field-label">动作配置 JSON（可空）</label>
          <textarea
            v-model="form.actionConfigText"
            class="linkage-input"
            rows="2"
            placeholder='如：{"priority":"high"}'
            aria-label="动作配置 JSON"
          ></textarea>
          <div class="linkage-field-grid">
            <div class="linkage-field">
              <label class="linkage-field-label" for="linkage-ward">目标病区（可空=全院）</label>
              <!-- 目标病区下拉换真数据（G4：ward.ts WARD_OPTIONS 跨域假常量清零→listOrgs
                   WARD 档）；「全院」空值为合法业务态（目标病区可空），仅清单在途禁用 -->
              <select
                id="linkage-ward"
                v-model="form.targetWardId"
                class="linkage-input"
                aria-label="目标病区"
                :disabled="wardDisabled"
              >
                <option value="">全院</option>
                <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
                  {{ org.orgName }}（{{ org.orgCode }}）
                </option>
              </select>
              <!-- 清单失败=说问题给恢复（G4/G6：不伪装空选项）；「全院」空值仍可选不阻断保存 -->
              <div v-if="wardError !== null" class="linkage-ward-error" role="alert">
                <span>病区清单加载失败，暂无法选择病区</span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :loading="wardLoading"
                  @click="onWardRetry"
                  >重试</el-button
                >
              </div>
            </div>
            <div class="linkage-field">
              <label class="linkage-field-label">启用状态</label>
              <select v-model="form.enabled" class="linkage-input" aria-label="启用状态">
                <option :value="true">启用</option>
                <option :value="false">停用</option>
              </select>
            </div>
          </div>
        </div>
        <template #footer>
          <el-button size="small" @click="onCancelDialog">取消</el-button>
          <el-button
            type="primary"
            size="small"
            :loading="saving"
            :disabled="saving"
            @click="onSave"
            >保存规则</el-button
          >
        </template>
      </el-dialog>

      <!-- ④ 页脚批注（G1 纵向序收尾，契约 ⑧.8）：数据时点=最近一次规则清单成功加载时点 +
           直出/分页口径语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ loadedAtLabel }} · 规则全量直出 · 日志 50 条/页 · 手动刷新取数
      </footer>

      <!-- 钤印确认浮层（契约 ⑧.5）：只承成功确认——「已保存/已重试」；失败与校验拦截走
           ElMessage 批注条不盖钤印；v-if 挂载制=入院登记台先例同构，挂载即 is-show（stamp-in
           特异性压过 rise），seq 递增重挂重放落章动画，≤1200ms 收场 -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4）：页面根不挂 .fuy-stagger 后根级 flex gap 失效，纵排与区块间距
   由本层同值承接（.fuy-page 同构 flex+gap，product-flow/binding-flow 同构），保证视觉零漂移 */
.linkage-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：FAILED 计数胶囊与刷新钮横排（alarm-flow 同构） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* FAILED 计数骨架（首拉落地前占位）：静态金洗纸胶囊形零 shimmer，定尺寸锁 CLS */
.linkage-failed-sk {
  width: 88px;
  height: 24px;
  border-radius: var(--fuy-radius-full);
}

/* 卡头右侧工具区：计数注记/新建钮/筛选控件 */
.linkage-card-tools {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 卡头副注：行集计数（12px 灰墨，G1 卡头副注归位） */
.linkage-card-note {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 列表/日志卡体 CLS 锁（蓝图丙-6.6：骨架锁塌陷——binding-list-body 同构口径） */
.linkage-list-body,
.linkage-log-body {
  box-sizing: border-box;
  min-height: 240px;
}

/* FAILED 行左缘印泥朱 3px 规线（蓝图丙-6.2 PM 重排裁决：失败行行级视觉通道，与行内
   「重试」link 动作通道双轨）：inset 盒影落首格左缘随行高贯通，印泥朱=状态朱语义通道
   --fuy-color-danger-text（非装饰朱砂），色不单独承义——结果列「失败」徽标文字同在 */
.linkage-log-table :deep(tr.fuy-linkage-row--failed > td:first-child) {
  box-shadow: inset 3px 0 0 0 var(--fuy-color-danger-text);
}

/* 结果筛选（native select 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道——石规描边+
   最亮纸白，binding-status-select 同形） */
.linkage-result-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 弹窗表单基元（native input/select/textarea 与 EP 密度口径对齐；围合取值直取 --fuy-*
   语义通道——契约 ⑤#2 输入围合=最亮纸白底+石规描边，表单容器挂 .fuy-form 承全局表单脸） */
.linkage-input {
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

textarea.linkage-input {
  resize: vertical;
}

.linkage-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.linkage-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 病区清单失败恢复条（G4/G6 说问题给恢复：warning 语义左缘+洗底经 A-5 已批 EP light-9
   语义通道，零新色值；binding-ward-error 同构） */
.linkage-ward-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--el-color-warning-light-9);
  border-left: 3px solid var(--fuy-color-warning-text);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
}

/* 触发源三源徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-rule-tag--alarm_triggered {
  border-color: var(--fuy-color-rule-device-alarm);
  color: var(--fuy-color-rule-device-alarm);
}

.fuy-rule-tag--telemetry_anomaly {
  border-color: var(--fuy-color-rule-threshold);
  color: var(--fuy-color-rule-threshold);
}

.fuy-rule-tag--device_status {
  border-color: var(--fuy-color-rule-offline);
  color: var(--fuy-color-rule-offline);
}

/* 启用态两值徽标 */
.fuy-rule-tag--enabled {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-rule-tag--disabled {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

/* 执行结果三态徽标 */
.fuy-linkage-tag--success {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-linkage-tag--failed {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

.fuy-linkage-tag--pending {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}
</style>
