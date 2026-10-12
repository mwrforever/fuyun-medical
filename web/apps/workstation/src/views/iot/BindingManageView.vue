<script setup lang="ts">
// 设备绑定页（/iot/bindings，M14 FU-M14-03 绑定生命周期前端面 · 暖纸卷宗换脸重排 丙-3，
// 「换脸不换业务」：绑定/解绑状态机、解绑原因强制拦截、visit 号 14 位 I 格式校验、BOUND 行
// 解绑入口显隐零变动）：门牌页首（衬线标题 + 状态位=状态筛选+刷新 + 批注行「五元组快照
// 权威源 · 绑定历史只增 · 解绑原因强制留痕 · 谁·时刻」）→ 左 15 栏绑定列表卡（五元组收拢为
// 「归属」列组表头——el-table 嵌套列组，扫读宽度收一档；状态三态徽标/解绑原因留痕）+
// 右 9 栏绑定表单卡（visit_id 14 位 I 格式显式校验——I+8 位日期+5 位流水，M04 冻结口径；
// 绑定校验设备未停用/患者在院/床位有效等业务规则单点归后端把守，前端不复制拦截）+
// 解绑原因强制弹窗（转床/消毒/维修/出院/调拨口径，空原因零出网拦截）。绑定历史只增：
// 解绑受理置 UNBINDING 由后端承载。
// 成功反馈=钤印（「已绑定/已受理解绑」，UNBINDING 受理异步语义文案区分，契约 ⑧.5 v-if
// 挂载制）；失败与显式校验拦截=ElMessage 批注条，钤印永不承失败。
// 病区下拉=listOrgs({type:'WARD'}) 真数据三态（册级总则 G4：IOT_WARD_OPTIONS 假常量清零，
// 本页零消费）；失败态出网值锚定真实病区编码，空病区编码零出网。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 显式校验零出网（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底展示。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { bindings, BIND_TYPE_LABELS, BINDING_STATUS_LABELS } from '@/api/iot';
import type { BindingVO } from '@/api/iot';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAuthStore } from '@/stores/auth';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ---------- 门牌批注行：签认人（会话身份）· 时刻（本地时钟零出网，乙-5 先例同构） ---------- */
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
/** 钤印文案（动词结果「已绑定/已受理解绑」；失败/校验拦截走 ElMessage 批注条不盖钤印） */
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

/* ==================== 绑定列表 ==================== */
/** 状态筛选（空串=全部三态） */
const statusFilter = ref('');

/** 加载绑定列表（deviceId/wardId/status 过滤由后端承载，前端按返回序直出）：页码/行集/
 * 加载态经 usePagedList 收拢（EX-49 范式，固定首页 size 50 直出——失败弹错归响应拦截器；
 * 驻留旧清单） */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    status: statusFilter.value === '' ? undefined : (statusFilter.value as BindingVO['status']),
  }),
  fetcher: ({ status, page, size }) => bindings.list({ status, page, size }),
  pageSize: 50,
});

/** 绑定状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return BINDING_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 绑定状态徽标状态类（fuy-binding-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-binding-tag--${(code ?? '').toLowerCase()}`;
}

/** 绑定模式中文词表反查（模式列） */
function bindTypeLabel(code: string | undefined): string {
  return BIND_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/* ==================== 绑定表单 ==================== */
const binding = ref(false);
const bindForm = ref({
  deviceId: '',
  patientId: '',
  visitId: '',
  bedId: '',
  wardId: '',
  bindType: 'FIXED',
  bindReason: '',
});

/* ---------- 绑定表单病区下拉（G4：IOT_WARD_OPTIONS 假常量清零 → listOrgs WARD 档真数据
 * 三态；装载即拉，失败说问题给恢复） ---------- */
/** 病区选项（启用机构清单，sort 升序；空清单/失败态下拉禁用——出网值锚定真实病区编码） */
const wardOptions = ref<OrgVO[]>([]);
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
  // 清单就绪即落默认病区（装载与重试共用汇合点；已选值不覆盖）
  if (bindForm.value.wardId === '' && wardOptions.value.length > 0) {
    bindForm.value.wardId = wardOptions.value[0]?.orgCode ?? '';
  }
});
const wardLoading = wardLoad.loading;
const wardError = wardLoad.error;

/** 病区下拉禁用态：清单在途或空清单（失败同形）皆不可选 */
const wardDisabled = computed(() => wardLoading.value || wardOptions.value.length === 0);

/** 病区清单重试（失败恢复同路：清单到达即落默认病区） */
function onWardRetry(): void {
  void wardLoad.run();
}

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，M04 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/**
 * 绑定提交：结构必填显式校验（设备/患者/就诊号 14 位格式/病区编码就绪，零出网）→ 出网 →
 * 清表单刷新。入口在途早退守卫防双击重复绑定。
 */
async function onBind(): Promise<void> {
  if (binding.value) {
    return;
  }
  if (bindForm.value.deviceId.trim() === '') {
    void ElMessage.warning('请填写设备 ID');
    return;
  }
  if (bindForm.value.patientId.trim() === '') {
    void ElMessage.warning('请填写患者号');
    return;
  }
  if (!VISIT_NO_PATTERN.test(bindForm.value.visitId.trim())) {
    void ElMessage.warning('就诊号应以 I 开头共 14 位（I+日期+流水）');
    return;
  }
  if (bindForm.value.wardId === '') {
    // 病区清单未就绪（装载中/失败态）禁出网：五元组快照的病区编码必须锚定真实机构清单
    void ElMessage.warning('病区清单未就绪，暂无法绑定');
    return;
  }
  binding.value = true;
  try {
    await bindings.bind({
      deviceId: bindForm.value.deviceId.trim(),
      patientId: bindForm.value.patientId.trim(),
      visitId: bindForm.value.visitId.trim(),
      bedId: bindForm.value.bedId.trim() === '' ? undefined : bindForm.value.bedId.trim(),
      wardId: bindForm.value.wardId,
      bindType: bindForm.value.bindType as 'FIXED' | 'MOBILE',
      bindReason:
        bindForm.value.bindReason.trim() === '' ? undefined : bindForm.value.bindReason.trim(),
    });
    // 成功=钤印「已绑定」（蓝图丙-3.4：文案=动词结果，≤1200ms 收场）
    showStamp('已绑定');
    bindForm.value = {
      deviceId: '',
      patientId: '',
      visitId: '',
      bedId: '',
      wardId: wardOptions.value[0]?.orgCode ?? '',
      bindType: 'FIXED',
      bindReason: '',
    };
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    binding.value = false;
  }
}

/* ==================== 解绑弹窗（原因强制） ==================== */
const unbindVisible = ref(false);
const unbinding = ref(false);
/** 解绑目标设备（弹窗上下文锚点） */
const unbindTarget = ref<BindingVO | null>(null);
const unbindReason = ref('');

/** 打开解绑弹窗（仅 BOUND 态行暴露入口） */
function openUnbind(row: BindingVO): void {
  unbindTarget.value = row;
  unbindReason.value = '';
  unbindVisible.value = true;
}

/** 解绑目标摘要行（弹窗回显锚点） */
const unbindSummary = computed(() => {
  const row = unbindTarget.value;
  if (row === null) {
    return '';
  }
  return `${row.deviceId ?? ''} · ${row.patientId ?? ''} · ${row.visitId ?? ''}`;
});

/** 确认解绑：原因强制显式校验（空原因零出网）→ 出网 → 关窗刷新。 */
async function onUnbind(): Promise<void> {
  if (unbinding.value) {
    return;
  }
  if (unbindReason.value.trim() === '') {
    void ElMessage.warning('请填写解绑原因');
    return;
  }
  unbinding.value = true;
  try {
    await bindings.unbind(unbindTarget.value?.deviceId ?? '', {
      reason: unbindReason.value.trim(),
    });
    // 成功=钤印「已受理解绑」（蓝图丙-3.4：UNBINDING 受理异步语义文案区分，终态由后端落定）
    showStamp('已受理解绑');
    unbindVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    unbinding.value = false;
  }
}

onMounted(() => {
  void loadList();
  void wardLoad.run();
});
</script>

<template>
  <div class="fuy-page binding-manage">
    <!-- 页内级联容器 binding-flow（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内
         区块；乙-5 discharge-flow 同构）：三段级联序 页首 0 / 两栏主区 1 / 页脚 2 -->
    <div class="fuy-stagger binding-flow">
      <!-- 门牌页首（蓝图丙-3.2/契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（状态筛选+
           刷新）+ 批注行（原 hint 升批注——「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">设备绑定</h1>
          <span class="fuy-page-en" aria-hidden="true">BINDING</span>
        </div>
        <div class="fuy-page-status">
          <!-- 状态筛选（native select 保留 jsdom 可测口径；三态词表经 BINDING_STATUS_LABELS） -->
          <select
            v-model="statusFilter"
            class="binding-status-select"
            aria-label="绑定状态筛选"
            @change="loadList"
          >
            <option value="">全部状态</option>
            <option v-for="(label, code) in BINDING_STATUS_LABELS" :key="code" :value="code">
              {{ label }}
            </option>
          </select>
          <el-button :loading="listLoading" @click="loadList">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          五元组快照权威源 · 绑定历史只增 · 解绑原因强制留痕 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：绑定列表卡（蓝图丙-3.2：五元组收拢为「归属」列组表头，扫读宽度收一档） -->
        <el-col :md="24" :lg="15">
          <section class="fuy-card fuy-dense binding-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">绑定列表</h2>
              <span class="binding-card-note">共 {{ rows.length }} 条</span>
            </header>
            <div v-loading="listLoading" class="fuy-card-body binding-list-body">
              <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense binding-table">
                <el-table-column label="设备 ID" min-width="110">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.deviceId }}</span>
                  </template>
                </el-table-column>
                <!-- 归属列组（蓝图丙-3.2 PM 重排裁决：五元组快照收拢为二级表头——父级
                     「归属」发丝分隔经 class-name 挂 binding-group-head 由 scoped :deep
                     承载，禁裸改 .el-*；四子列保持五元组快照原语义零变动） -->
                <el-table-column label="归属" align="center" class-name="binding-group-head">
                  <el-table-column label="患者号" min-width="150">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.patientId }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="就诊号" min-width="130">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.visitId }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="床位" width="70">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.bedId ?? '—' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="病区" width="70">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.wardId }}</span>
                    </template>
                  </el-table-column>
                </el-table-column>
                <el-table-column label="模式" width="80">
                  <template #default="{ row }">
                    {{ bindTypeLabel(row.bindType) }}
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                      {{ statusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="解绑原因" min-width="100">
                  <template #default="{ row }">
                    <span :title="row.unbindReason">{{ row.unbindReason ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="绑定时间" width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.boundAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.status === 'BOUND'"
                      link
                      type="danger"
                      size="small"
                      @click="openUnbind(row)"
                      >解绑</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（契约 ⑥）：主句=暂无〈业务客体〉+ 说明给下一步（零默认插画） -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无绑定记录</p>
                <p class="fuy-empty-hint">
                  在右侧登记表单提交绑定后，绑定行按五元组快照在此落档；也可调整状态筛选或点击
                  刷新。
                </p>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：绑定表单卡（蓝图丙-3.2：绑定表单挂 .fuy-form 文书脸） -->
        <el-col :md="24" :lg="9">
          <section class="fuy-card binding-form-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">设备绑定</h2>
            </header>
            <div class="fuy-card-body">
              <div class="fuy-form binding-form">
                <label class="binding-field-label">设备 ID（必填，须为未停用设备）</label>
                <input
                  v-model="bindForm.deviceId"
                  class="binding-input fuy-num"
                  placeholder="如：dev-009"
                  aria-label="设备 ID"
                />
                <label class="binding-field-label">患者号（必填，患者在院校验归后端）</label>
                <input
                  v-model="bindForm.patientId"
                  class="binding-input fuy-num"
                  placeholder="患者档案编号"
                  aria-label="患者号"
                />
                <label class="binding-field-label">就诊号（必填，I 开头共 14 位）</label>
                <input
                  v-model="bindForm.visitId"
                  class="binding-input fuy-num"
                  placeholder="如：I2026092600009"
                  aria-label="就诊号"
                />
                <div class="binding-field-grid">
                  <div class="binding-field">
                    <label class="binding-field-label" for="binding-ward">病区</label>
                    <!-- 病区下拉换真数据（G4：IOT_WARD_OPTIONS 假常量清零→listOrgs WARD 档）；
                         清单在途/空清单禁用——出网值必须锚定真实病区编码 -->
                    <select
                      id="binding-ward"
                      v-model="bindForm.wardId"
                      class="binding-input"
                      aria-label="病区"
                      :disabled="wardDisabled"
                    >
                      <option
                        v-for="org in wardOptions"
                        :key="org.orgCode"
                        :value="org.orgCode ?? ''"
                      >
                        {{ org.orgName }}（{{ org.orgCode }}）
                      </option>
                    </select>
                    <!-- 清单失败=说问题给恢复（G4/G6：不伪装空选项），重试同路落默认病区 -->
                    <div v-if="wardError !== null" class="binding-ward-error" role="alert">
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
                  <div class="binding-field">
                    <label class="binding-field-label">床位号（固定式建议填写）</label>
                    <input
                      v-model="bindForm.bedId"
                      class="binding-input fuy-num"
                      placeholder="床位有效性校验归后端"
                      aria-label="床位号"
                    />
                  </div>
                </div>
                <label class="binding-field-label">绑定模式</label>
                <select v-model="bindForm.bindType" class="binding-input" aria-label="绑定模式">
                  <option value="FIXED">固定式（设备↔床位，患者随床位推导）</option>
                  <option value="MOBILE">移动式（设备↔患者直绑）</option>
                </select>
                <label class="binding-field-label">绑定原因</label>
                <textarea
                  v-model="bindForm.bindReason"
                  class="binding-input"
                  rows="2"
                  placeholder="如：术后监护"
                  aria-label="绑定原因"
                ></textarea>
                <!-- 确认绑定（PR-4F #33「绑定」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
                     DOM 移除（D-34）；右栏绑定表单常驻（非弹窗），绑定 POST 以本按钮为唯一
                     出口，无码会话表单可填不可出网；:loading/:disabled 在途数据态与权限判定
                     两层正交 -->
                <el-button
                  v-perm="'iot:binding:btn:manage'"
                  type="primary"
                  class="binding-submit"
                  :loading="binding"
                  :disabled="binding"
                  @click="onBind"
                  >确认绑定</el-button
                >
              </div>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 解绑原因强制弹窗（G2：挂 .fuy-dialog 衬线标题 + 头部发丝；表单容器挂 .fuy-form，
           原生 textarea 保留 jsdom 可测口径与 aria 标注） -->
      <el-dialog v-model="unbindVisible" title="设备解绑" width="420px" class="fuy-dialog">
        <div class="fuy-form binding-unbind-form">
          <p class="binding-unbind-target fuy-num">{{ unbindSummary }}</p>
          <label class="binding-field-label" for="binding-unbind-reason"
            >解绑原因（强制：转床/消毒/维修/出院/调拨）</label
          >
          <textarea
            id="binding-unbind-reason"
            v-model="unbindReason"
            class="binding-input"
            rows="2"
            placeholder="解绑原因强制留痕，绑定历史只增"
            aria-label="解绑原因"
          ></textarea>
        </div>
        <template #footer>
          <el-button size="small" @click="unbindVisible = false">取消</el-button>
          <!-- 确认解绑（PR-4F #33「解绑」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
               DOM 移除（D-34），解绑 DELETE 以本按钮为唯一出口：行内「解绑」入口按盘点
               口径不挂码，无码会话仅可开窗读上下文不可出网；:loading/:disabled 在途数据态
               与权限判定两层正交 -->
          <el-button
            v-perm="'iot:binding:btn:manage'"
            type="danger"
            size="small"
            :loading="unbinding"
            :disabled="unbinding"
            @click="onUnbind"
            >确认解绑</el-button
          >
        </template>
      </el-dialog>

      <!-- 钤印确认浮层（契约 ⑧.5）：只承成功确认——「已绑定/已受理解绑」；失败与校验拦截走
           ElMessage 批注条不盖钤印；v-if 挂载制=乙-5 先例同构，挂载即 is-show（stamp-in
           特异性压过 rise），seq 递增重挂重放落章动画，≤1200ms 收场 -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>

      <!-- 页脚批注（契约 ⑧.8）：UNBINDING 异步受理语义（数据语义，禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        绑定历史只增：解绑受理后行状态置「解绑中」，由后端异步落定 · 列表 50 条/页 · 手动刷新取数
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4）：页面根不挂 .fuy-stagger 后根级 flex gap 失效，纵排与区块间距
   由本层同值承接（.fuy-page 同构 flex+gap，乙-5 discharge-flow 同构），保证视觉零漂移 */
.binding-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：状态筛选+刷新右挂（蓝图丙-3.2；乙-5 状态位同位语法） */
.fuy-page-status {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 状态筛选（native select 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道——石规描边+
   最亮纸白，与绑定表单基元同形） */
.binding-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 卡头副注：行集计数（12px 灰墨，G1 卡头副注归位） */
.binding-card-note {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 列表卡主体 min-height 锁 CLS（蓝图丙-3.6：骨架 240px） */
.binding-list-body {
  min-height: 240px;
}

/* 归属列组父级表头发丝分隔（蓝图丙-3.3：父级「归属」发丝分隔=列组边界墨笔细线，经
   class-name 挂类由 :deep 穿透 EP 表头单元格；scoped 规则不落全局层，零裸改 .el-*） */
.binding-table :deep(th.el-table__cell.binding-group-head) {
  border-left: var(--fuy-border-hairline);
}

/* 表单基元：native input/select 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道
   （契约 ⑤#2 输入围合=最亮纸白底+石规描边，表单容器挂 .fuy-form 承全局表单脸） */
.binding-input {
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

.binding-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.binding-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 病区清单失败恢复条（G4/G6 说问题给恢复：warning 语义左缘+洗底经 A-5 已批 EP light-9
   语义通道，零新色值） */
.binding-ward-error {
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

.binding-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

.binding-unbind-target {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 绑定状态三态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-binding-tag--bound {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-binding-tag--unbinding {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-binding-tag--unbound {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}
</style>
