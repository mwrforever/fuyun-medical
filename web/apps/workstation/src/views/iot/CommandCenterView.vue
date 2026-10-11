<script setup lang="ts">
// 命令中心页（/iot/commands，M16 命令下发安全面前端面 · 暖纸卷宗换脸重排 丙-5，「换脸不换
// 业务」：两步禁用时序、challenge 生命周期、参数 JSON 校验文案、跨页白名单口径零变动）：
// 门牌页首（衬线标题 + 状态位=刷新 + 批注行「challenge 两步安全门 · 治疗级命令须已登记产品
// 命令白名单（维护入口：产品与物模型，与丙-1 命令登记互引）· 白名单校验由后端两步把守 ·
// 谁·时刻」）→ 左 9 栏下发台卡（两步门禁「签批」脸：第一步获取挑战 → 挑战取得后「挑战凭据」
// 纸条（悬停暖底 gray-50 洗底+发丝描边+challengeId 回显+有效期，fuy-content-fade 显影；不用
// 金洗底避免与「选中」语义混淆）→ 第二步确认下发墨实底主操作 → 放弃本次挑战 link）+ 右 15 栏
// 命令日志卡（五态徽标 + 安全级白名单标注列 + .fuy-empty 空态脸）。
// 成功反馈=钤印「已下发」（契约 ⑧.5 v-if 挂载制）；失败与显式校验拦截=ElMessage 批注条，
// 钤印永不承失败。全部写操作自带在途守卫（入口早退先于一切 await）+ 显式校验零出网（禁裸
// parse——参数 JSON 显式 try/catch 收窄）。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { commands, devices, COMMAND_STATUS_LABELS, SAFETY_LEVEL_LABELS } from '@/api/iot';
import type { CommandLogVO, ConfirmChallengeVO, DeviceVO } from '@/api/iot';
import { useAuthStore } from '@/stores/auth';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ---------- 门牌批注行：签认人（会话身份）· 时刻（本地时钟零出网，丙-3 先例同构） ---------- */
/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 钤印确认反馈（契约 ⑧.5） ==================== */
/** 钤印文案（动词结果「已下发」；失败/校验拦截走 ElMessage 批注条不盖钤印） */
const stampText = ref('');
/** 钤印可见态（v-if 挂载制：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为常显空朱块） */
const stampVisible = ref(false);
/** 钤印重挂序号：递增强制重挂节点重放 fuy-stamp-in 落章动画 */
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印并 ≤1200ms 收场（动画收尾后 v-if 卸载，浮层不驻留遮挡工作面） */
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

onUnmounted(() => {
  // 收场定时器仅翻转内部展示 ref（零 DOM/出网副作用），卸载清理防迟触发
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

/** 命令状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return COMMAND_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 命令状态徽标状态类（fuy-command-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-command-tag--${(code ?? '').toLowerCase()}`;
}

/** 安全级中文词表反查（白名单标注列） */
function safetyLabel(code: string | undefined): string {
  return SAFETY_LEVEL_LABELS[code ?? ''] ?? code ?? '—';
}

/** 安全级徽标状态类（fuy-safety-tag--{level} 契约类：治疗级琥珀高亮承新值） */
function safetyClass(code: string | undefined): string {
  return `fuy-safety-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 下发台（challenge 两步确认） ==================== */
const deviceOptions = ref<DeviceVO[]>([]);
/** 下发表单（deviceId 来自设备选择，commandName 与 params 为人工输入） */
const form = ref({ deviceId: '', commandName: '', paramsText: '' });
/** 第一步在途守卫 */
const confirming = ref(false);
/** 第二步在途守卫 */
const issuing = ref(false);
/** 挑战态（null=未取得挑战，第二步禁用；取得后承载 challengeId 与有效期回显） */
const challenge = ref<ConfirmChallengeVO | null>(null);

/**
 * 参数 JSON 显式解析（禁裸 parse）：空串视为无参数（undefined）；非空须为合法 JSON 对象
 * （键值对形态，数组/标量拒绝）。
 *
 * @return 合法对象或 undefined（无参数）；非法返回 null（调用方零出网拦截）
 */
function parseParamsText(): Record<string, unknown> | undefined | null {
  const text = form.value.paramsText.trim();
  if (text === '') {
    return undefined;
  }
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 加载设备选项（下发台设备选择数据源；全状态直出，停用设备下发由后端两步校验把守） */
async function loadDevices(): Promise<void> {
  try {
    const page = await devices.list({ page: 0, size: 50 });
    deviceOptions.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留空选项
  }
}

/**
 * 第一步·挑战确认：必填与参数 JSON 显式校验（零出网）→ 出网 confirm-challenge →
 * 挑战态落地展示凭据条（challengeId 与有效期）。
 */
async function onConfirmChallenge(): Promise<void> {
  if (confirming.value || issuing.value) {
    return;
  }
  if (form.value.deviceId === '') {
    void ElMessage.warning('请选择设备');
    return;
  }
  if (form.value.commandName.trim() === '') {
    void ElMessage.warning('请填写命令名');
    return;
  }
  const params = parseParamsText();
  if (params === null) {
    void ElMessage.warning('参数须为合法 JSON 对象（键值对形态），请修正后再试');
    return;
  }
  confirming.value = true;
  try {
    challenge.value = await commands.confirmChallenge({
      deviceId: form.value.deviceId,
      commandName: form.value.commandName.trim(),
      params,
    });
  } catch (error) {
    surfaceBizError(error);
  } finally {
    confirming.value = false;
  }
}

/**
 * 第二步·确认下发：未取得挑战前置禁用（防御性兜底再拦一次）→ 出网携 challengeId 下发 →
 * 成功盖钤印「已下发」，清挑战态回第一步并刷新命令日志。
 */
async function onIssue(): Promise<void> {
  if (issuing.value || challenge.value === null) {
    if (challenge.value === null) {
      void ElMessage.warning('请先完成第一步获取挑战标识');
    }
    return;
  }
  issuing.value = true;
  try {
    await commands.issue({
      challengeId: challenge.value.challengeId ?? '',
      deviceId: form.value.deviceId,
      commandName: form.value.commandName.trim(),
      params: parseParamsText() ?? undefined,
    });
    // 成功=钤印「已下发」（蓝图丙-5.4：文案=动词结果，≤1200ms 收场）
    showStamp('已下发');
    challenge.value = null;
    form.value = { deviceId: form.value.deviceId, commandName: '', paramsText: '' };
    await loadLogs();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    issuing.value = false;
  }
}

/** 放弃本次挑战（回第一步；挑战有效期由后端承载，过期自然失效） */
function resetChallenge(): void {
  challenge.value = null;
}

/** 有效期展示串（挑战凭据条回显；后端 long→string 口径） */
const challengeExpiryText = computed(() => {
  const expires = challenge.value?.expiresIn;
  return expires === undefined || expires === '' ? '—' : `${expires}s`;
});

/* ==================== 命令日志 ==================== */
/** 状态筛选（空串=全部五态） */
const statusFilter = ref('');

/** 加载命令日志（status/deviceId 过滤由后端承载，前端按返回序直出）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错
 * 归响应拦截器；驻留旧清单） */
const {
  rows: logRows,
  loading: logLoading,
  fetch: loadLogs,
} = usePagedList({
  params: () => ({
    status: statusFilter.value === '' ? undefined : (statusFilter.value as CommandLogVO['status']),
  }),
  fetcher: ({ status, page, size }) => commands.page({ status, page, size }),
  pageSize: 50,
});

onMounted(() => {
  void loadDevices();
  void loadLogs();
});
</script>

<template>
  <!-- 页内级联容器 command-flow（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内
       区块；丙-3 binding-flow 同构）：三段级联序 页首 0 / 两栏主区 1 / 页脚 2 -->
  <div class="fuy-page command-center">
    <div class="fuy-stagger command-flow">
      <!-- 门牌页首（蓝图丙-5.2/契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（刷新）+
           批注行（原 hint 升批注——跨页白名单口径与「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">命令中心</h1>
          <span class="fuy-page-en" aria-hidden="true">COMMAND CENTER</span>
        </div>
        <div class="fuy-page-status">
          <el-button :loading="logLoading" @click="loadLogs">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          challenge 两步安全门 · 治疗级命令须已登记产品命令白名单（维护入口：产品与物模型）·
          白名单校验由后端两步把守 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：下发台卡（两步门禁「签批」脸，蓝图丙-5.2 PM 裁决） -->
        <el-col :md="24" :lg="9">
          <section class="fuy-card command-issue-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">命令下发台</h2>
            </header>
            <div class="fuy-card-body">
              <div class="fuy-form command-issue-form">
                <label class="command-field-label">设备（必选）</label>
                <select v-model="form.deviceId" class="command-input" aria-label="下发设备">
                  <option value="" disabled>请选择设备</option>
                  <option
                    v-for="device in deviceOptions"
                    :key="device.deviceId"
                    :value="device.deviceId"
                  >
                    {{ device.deviceId }}（{{ device.deviceName }}）
                  </option>
                </select>
                <label class="command-field-label"
                  >命令名（必填，须为产品命令白名单已登记项）</label
                >
                <input
                  v-model="form.commandName"
                  class="command-input fuy-num"
                  placeholder="如：setVentilationParams"
                  aria-label="命令名"
                />
                <label class="command-field-label">命令参数 JSON（可空，键值对形态）</label>
                <textarea
                  v-model="form.paramsText"
                  class="command-input"
                  rows="3"
                  placeholder='如：{"flow":5}'
                  aria-label="命令参数 JSON"
                ></textarea>

                <!-- 两步门禁：第一步签发挑战 → 「挑战凭据」纸条（fuy-content-fade 显影）→
                     第二步携 challengeId 下发（两按钮常驻，第二步未取得挑战前置禁用，禁绕过
                     时序） -->
                <!-- 命令下发两步按钮（PR-4F #34，IOT_ADMIN 绑定）v-perm 同码直挂——获取挑战
                     与确认下发任一步缺码即断链，无码 DOM 移除（D-34）；:disabled 时序门禁
                     （第二步须先取得挑战）与权限判定两层正交；「放弃本次挑战」为纯本地态
                     重置不出网不挂码 -->
                <div class="command-challenge-step">
                  <el-button
                    v-perm="'iot:command:btn:issue'"
                    type="primary"
                    class="command-submit"
                    :loading="confirming"
                    :disabled="challenge !== null || confirming || issuing"
                    @click="onConfirmChallenge"
                    >第一步：获取挑战</el-button
                  >
                  <!-- 挑战凭据纸条（蓝图丙-5.2：悬停暖底 gray-50 洗底+发丝描边，不用金洗底
                       避免与「选中」语义混淆；显影沿 fuy-content-fade 家族） -->
                  <Transition name="fuy-content-fade">
                    <div v-if="challenge !== null" class="command-challenge-credential">
                      <span class="command-credential-head">
                        <span class="command-credential-label">挑战凭据</span>
                        <span class="command-credential-expiry"
                          >有效期 {{ challengeExpiryText }}</span
                        >
                      </span>
                      <span class="command-credential-id fuy-num">{{
                        challenge.challengeId ?? '—'
                      }}</span>
                    </div>
                  </Transition>
                  <el-button
                    v-perm="'iot:command:btn:issue'"
                    type="primary"
                    class="command-submit"
                    :loading="issuing"
                    :disabled="challenge === null || issuing"
                    @click="onIssue"
                    >第二步：确认下发</el-button
                  >
                  <el-button
                    v-if="challenge !== null"
                    link
                    size="small"
                    class="command-challenge-reset"
                    @click="resetChallenge"
                    >放弃本次挑战</el-button
                  >
                </div>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：命令日志卡（五态徽标 + 安全级白名单标注 + 空态脸） -->
        <el-col :md="24" :lg="15">
          <section class="fuy-card fuy-dense command-log-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">命令日志</h2>
              <div class="command-log-head-extra">
                <span class="command-log-count">共 {{ logRows.length }} 条</span>
                <!-- 状态筛选（native select 保留 jsdom 可测口径；五态词表经 COMMAND_STATUS_LABELS） -->
                <select
                  v-model="statusFilter"
                  class="command-status-select"
                  aria-label="命令状态筛选"
                  @change="loadLogs"
                >
                  <option value="">全部状态</option>
                  <option v-for="(label, code) in COMMAND_STATUS_LABELS" :key="code" :value="code">
                    {{ label }}
                  </option>
                </select>
              </div>
            </header>
            <div v-loading="logLoading" class="fuy-card-body command-log-body">
              <el-table v-if="logRows.length > 0" :data="logRows" class="fuy-dense">
                <el-table-column label="命令号" min-width="130">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.commandNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="设备 ID" min-width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.deviceId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="命令名" min-width="140">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.commandName }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="安全级" width="86">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa" :class="safetyClass(row.safetyLevel)">
                      {{ safetyLabel(row.safetyLevel) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="80">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                      {{ statusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作人" width="76">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.operator ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="下发时间" width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.issuedAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="结果时间" width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.resultAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="错误信息" min-width="110">
                  <template #default="{ row }">
                    <span :title="row.errorMsg">{{ row.errorMsg ?? '—' }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（契约 ⑥）：主句=暂无〈业务客体〉+ 说明给下一步（零默认插画） -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无命令日志</p>
                <p class="fuy-empty-hint">
                  两步门禁下发成功的命令在此落档；可调整状态筛选或点击刷新。
                </p>
              </div>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 钤印确认浮层（契约 ⑧.5）：只承成功确认——「已下发」；失败与校验拦截走 ElMessage
           批注条不盖钤印；v-if 挂载制=丙-3 先例同构，挂载即 is-show（stamp-in 特异性压过
           rise），seq 递增重挂重放落章动画，≤1200ms 收场 -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>

      <!-- 页脚批注（契约 ⑧.8）：两步门禁数据语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        challenge 有效期与治疗级白名单校验由后端两步把守 · 下发成功后执行结果见命令日志 · 命令日志
        50 条/页 · 手动刷新取数
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4）：页面根不挂 .fuy-stagger 后根级 flex gap 失效，纵排与区块间距
   由本层同值承接（.fuy-page 同构 flex+gap，丙-3 binding-flow 同构），保证视觉零漂移 */
.command-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：刷新右挂（蓝图丙-5.2） */
.fuy-page-status {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 日志卡头右侧：行集计数 + 状态筛选（G1 卡头副注归位） */
.command-log-head-extra {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.command-log-count {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 日志卡主体 min-height 锁 CLS（蓝图丙-5.6：骨架 240px） */
.command-log-body {
  min-height: 240px;
}

/* 状态筛选（native select 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道——石规描边+
   最亮纸白，与下发表单基元同形） */
.command-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 表单基元：native input/select/textarea 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道
   （契约 ⑤#2 输入围合=最亮纸白底+石规描边，表单容器挂 .fuy-form 承全局表单脸） */
.command-input {
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

.command-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 两步门禁「签批」脸：同容器相邻 el-button 取消 EP 默认左距改纵排堆叠（自有前缀组合
   选择器承载，非裸改 .el-*） */
.command-challenge-step .el-button + .el-button {
  margin-left: 0;
}

.command-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

/* 挑战凭据纸条（蓝图丙-5.2 PM 裁决）：悬停暖底 --fuy-palette-gray-50 洗底 + 发丝描边（不用
   金洗底 --fuy-palette-brand-100，避免与「选中」语义混淆）；显影走 Transition
   fuy-content-fade（motion.css 既有过渡类，零新 keyframes） */
.command-challenge-credential {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  margin-top: var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
}

.command-credential-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--fuy-space-2);
}

.command-credential-label {
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--fuy-color-text-secondary);
}

.command-credential-expiry {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

.command-credential-id {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
  word-break: break-all;
}

.command-challenge-reset {
  margin-top: var(--fuy-space-2);
}

/* 命令状态五态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-command-tag--issued {
  border-color: var(--fuy-color-brand);
  color: var(--fuy-color-brand);
}

.fuy-command-tag--delivered {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-command-tag--success {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-command-tag--failed {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

.fuy-command-tag--timeout {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

/* 安全级白名单标注徽标：治疗级琥珀高亮（给药/通气参数类，fuy-safety-tag 承新值），安全级弱化 */
.fuy-safety-tag--safety {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-safety-tag--treatment {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}
</style>
