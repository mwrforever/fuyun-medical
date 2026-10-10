<script setup lang="ts">
// 药房发药工作台（FU-M06-04 · 暖纸卷宗换脸重排，逐页蓝图 P11）：待发队列（PENDING_DISPENSE）
// → 选处方回显发药单 → 配药（追溯码逐码录入）→ 核对（第二药师）→ 发药签名；双签分权由后端
// 硬守卫（PH-1011），前端以「当前用户 ID=调配人时禁用核对/发药」为辅助启停面。弹错归响应拦截器。
// 构图（蓝图 P11「换脸不换业务」）：门牌页首（衬线标题 + 队列计数胶囊「待发+调剂中合计」+
// 签认人·时刻批注行）→ 左队列卡（10fr）→ 右发药单卡（14fr）：单据头=发药单号+单状态 tag+
// 双签步点条（CREATED→PICKING→PICKED→ISSUED 四节点纯展示，当前步高亮）→ 明细行编辑 → 动作区
// 按当前步视觉分级（主步墨实底/次步描边，启停逻辑零变动）。五步业务流（listPrescriptions×2/
// listDispenses/pickDispense/verifyDispense/issueDispense）与数据契约重排前逐字一致。
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
// ElMessageBox 发药确认弹窗在模板外使用，按需样式手动补引（F-1 缺口闭合）
import 'element-plus/es/components/message-box/style/css';
import {
  issueDispense,
  listDispenses,
  listPrescriptions,
  pickDispense,
  verifyDispense,
} from '@/api/pharmacy';
import type { DispenseVO, PrescriptionVO } from '@/api/pharmacy';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { dispenseStatusText } from '@/utils/dispenseDisplay';
import { useAuthStore } from '@/stores/auth';

/** 待发处方队列（status=PENDING_DISPENSE） */
const queue = ref<PrescriptionVO[]>([]);
/** 当前处方对应的发药单（null=未放行未选单） */
const dispense = ref<DispenseVO | null>(null);
/** 逐行追溯码录入模型（键=发药明细 id，与 PickLine.traceCodes 同构） */
const traceInputs = reactive<Record<string, string>>({});
const auth = useAuthStore();
/**
 * 发药动作在途标志：配药/核对/发药签名三按钮按单据状态互斥启用（CREATED/PICKING/PICKED
 * 任一时刻至多一个动作可发），共用一标志即可全覆盖；防双击二次出网与发药确认弹窗双开。
 */
const dispensing = ref(false);

// 门牌批注行「谁」：会话显示名真值（空值 — 占位零伪数据）
const signerName = computed(() => auth.user?.displayName ?? '—');

/**
 * 门牌批注行「何时」（YYYY-MM-DD 周X）：与首页门牌同语法的日期批注，纯本地时钟零出网。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 门牌队列计数胶囊（待发+调剂中合计）：队列内存取值零额外出网（蓝图 P11.2） */
const queueCount = computed(() => queue.value.length);

/** 发药单状态 tag 色型映射（§4.3 映射法；文案词表走 utils/dispenseDisplay 单源——
 * 与退药受理页共用，色型仅本页消费故页内承载）：待配药 primary、配药中 warning、
 * 待发药签名 success、已发药 info；未知态归 info 防不确定色彩语义。
 * tag 挂发药单卡头使三按钮启停语义显性化，不改按钮启停逻辑 */
const dispenseStatusTagType: Record<string, 'primary' | 'success' | 'warning' | 'info'> = {
  CREATED: 'primary',
  PICKING: 'warning',
  PICKED: 'success',
  ISSUED: 'info',
};

/**
 * 双签步点条四节点（蓝图 P11.2 纯展示零逻辑）：发药单状态机 CREATED→PICKING→PICKED→ISSUED
 * 的业务镜像，节点文案=动作语义（拾取/核对/发药/完成）；仅作视觉分级数据源，不参与任何启停。
 */
const DISPENSE_STEPS = [
  { status: 'CREATED', label: '拾取' },
  { status: 'PICKING', label: '核对' },
  { status: 'PICKED', label: '发药' },
  { status: 'ISSUED', label: '完成' },
] as const;

/** 当前步序（0-3）：按发药单状态在步点条中的位次；未知态 -1=进度不可判定，全节点按未到步弱化（不猜进度） */
const currentStepIndex = computed(() =>
  DISPENSE_STEPS.findIndex((step) => step.status === (dispense.value?.status ?? '')),
);

/**
 * 步点条节点态分级（纯展示）：当前步浓墨实心、已过步浓墨空心、未到步弱墨。
 *
 * @param index 节点位次（0-3）
 * @return 节点态类名（is-current/is-passed/is-upcoming）
 */
function stepClass(index: number): string {
  if (currentStepIndex.value < 0 || index > currentStepIndex.value) {
    return 'is-upcoming';
  }
  return index === currentStepIndex.value ? 'is-current' : 'is-passed';
}

/**
 * 动作钮视觉分级（蓝图 P11.2 主步墨实底/次步描边）：仅当前步动作挂 primary 墨实底脸，
 * 其余回落默认描边脸；纯展示分级不改启停（disabled 仍由单据状态+双签+在途既有判定承载）。
 *
 * @param stepIndex 动作对应的步点位次（配药 0 / 核对 1 / 发药签名 2）
 * @return EP 按钮 type（当前步 'primary'，其余 '' 默认描边）
 */
function actionType(stepIndex: number): '' | 'primary' {
  return currentStepIndex.value === stepIndex ? 'primary' : '';
}

/**
 * 加载工作台队列（发药签名成功后重刷）：待发（PENDING_DISPENSE）与调剂中（DISPENSING）
 * 两段合并——后端 pick 即 CAS 处方至 DISPENSING（PH 状态机），单查待发段会使第二药师
 * 无法从队列拉起在途单完成核对/发药签名（双签闭环必需）。Promise.all 双态合并在任务
 * 体内承载；loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——
 * 失败弹错归响应拦截器、驻留旧队列）。
 */
const { loading, run: loadQueue } = useAsyncTask(async () => {
  const [pending, dispensing] = await Promise.all([
    listPrescriptions({ status: 'PENDING_DISPENSE' }),
    listPrescriptions({ status: 'DISPENSING' }),
  ]);
  queue.value = [...pending.content, ...dispensing.content];
});

/**
 * 选处方回显发药单（无单提示未放行）。
 *
 * @param row 队列行（队列点击入参；重刷路径传仅含 rxNo 的最小对象）
 */
async function onSelect(row: PrescriptionVO): Promise<void> {
  try {
    const list = await listDispenses({ rxNo: row.rxNo ?? '' });
    dispense.value = list.length > 0 ? list[0] : null;
    // 重刷前清空逐码录入模型，防上一单残留串行
    Object.keys(traceInputs).forEach((key) => delete traceInputs[key]);
    if (!dispense.value) {
      void ElMessage.warning('该处方尚未放行生成发药单');
    }
  } catch {
    // 失败弹错归响应拦截器；驻留旧单
  }
}

/**
 * 双签辅助启停面：调配人=当前会话用户时禁用核对/发药（同人双签最终由后端 PH-1011 硬拒）。
 * 后端留痕的操作者标识为 userId（AuthTokenInterceptor 注入），比对基准取会话 userId 而非 loginName。
 */
function isPicker(): boolean {
  return !!dispense.value && dispense.value.picker === auth.user?.userId;
}

/**
 * 配药：逐码非空前置（无码不结），出网后回显刷新。
 * 入口在途早退守卫：重渲染前到达的第二击直接拦截，根除配药重复出网。
 */
async function onPick(): Promise<void> {
  if (dispensing.value) {
    return;
  }
  const sheet = dispense.value;
  if (!sheet) return;
  const items = (sheet.items ?? []).map((i) => {
    const codes = (traceInputs[i.id ?? ''] ?? '').split(/[，,\s]+/).filter(Boolean);
    return { prescriptionItemId: String(i.prescriptionItemId), traceCodes: codes };
  });
  if (items.some((i) => i.traceCodes.length === 0)) {
    void ElMessage.warning('逐盒追溯码采集为空（无码不结）');
    return;
  }
  // 置位在途（finally 必复位）：锁定配药出网窗口，窗口内重复触发零出网
  dispensing.value = true;
  try {
    await pickDispense(sheet.dispenseNo ?? '', { items });
    void ElMessage.success('配药锁定完成');
    await onSelect({ rxNo: sheet.rxNo });
  } catch {
    // 失败弹错归响应拦截器；录入驻留供补码重试
  } finally {
    dispensing.value = false;
  }
}

/**
 * 核对：按钮启停仅辅助，同人双签由后端拒。
 * 入口在途早退守卫：重渲染前到达的第二击直接拦截，根除核对重复出网。
 */
async function onVerify(): Promise<void> {
  if (dispensing.value) {
    return;
  }
  const sheet = dispense.value;
  if (!sheet) return;
  // 置位在途（finally 必复位）：锁定核对出网窗口，窗口内重复触发零出网
  dispensing.value = true;
  try {
    await verifyDispense(sheet.dispenseNo ?? '');
    void ElMessage.success('核对通过');
    await onSelect({ rxNo: sheet.rxNo });
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    dispensing.value = false;
  }
}

/**
 * 发药签名：确认弹框后出网（终笔，费用占用生效）；取消驻留不重刷。
 * 在途守卫必须先于确认弹框置位：弹窗未决窗口内到达的第二击在入口即被拦截，防弹双窗二次出网。
 */
async function onIssue(): Promise<void> {
  if (dispensing.value) {
    return;
  }
  const sheet = dispense.value;
  if (!sheet) return;
  dispensing.value = true;
  try {
    try {
      // 不可逆终笔的防错确认（§4.4 禁裸确认）：单号前置（先单据后动作的阅读顺序）+ 显式中文按钮——
      // ElMessageBox 函数式挂载不继承 ConfigProvider locale，不传 confirmButtonText 会渲染英文 OK/Cancel（D-21 断言现代化 PR 闭合，批复留痕见 CHANGELOG）
      await ElMessageBox.confirm(
        `发药单 ${sheet.dispenseNo ?? ''} 签名后药品出库且不可逆，确认发药？`,
        '发药签名',
        { confirmButtonText: '确认发药', cancelButtonText: '取消' },
      );
    } catch {
      // 用户取消：发药单驻留，可再次点击发药（在途复位交外层 finally）
      return;
    }
    await issueDispense(sheet.dispenseNo ?? '');
    void ElMessage.success('发药完成');
    dispense.value = null;
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器；单据驻留供重试
  } finally {
    dispensing.value = false;
  }
}

onMounted(loadQueue);
</script>

<template>
  <!-- 双卡进场 stagger（§6.1）挂 el-row 而非根 div：.fuy-stagger > * 只匹配直接子元素，
       挂根 div 时唯一子元素是 el-row，级联退化为整行同播且 el-col 上的 index 变量零消费；
       挂 el-row 后两个 el-col 即直接子元素——右列 inline index 1 = 40ms delay，级联真实生效
       （质量门 R1 F-1 修复；与批次 3 双卡页 stagger 直接命中卡元素同语义） -->
  <div class="fuy-page">
    <!-- 门牌页首（契约 ⑧.1/蓝图 P11.2）：衬线标题 + 队列计数胶囊（待发+调剂中合计，amber
         待办语义）+ 签认人·时刻批注行；2px 墨规收底走全局 .fuy-page-head 脸，本页零私有标题样式 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">发药工作台</h1>
      </div>
      <div class="fuy-page-status">
        <span class="fuy-status-pill fuy-status-pill--amber">
          队列 <span class="fuy-num">{{ queueCount }}</span>
        </span>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <el-row :gutter="16" class="fuy-stagger">
      <!-- fuy-dense 挂外层卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为
           后代选择器 .fuy-dense .el-table，挂表格自身不构成后代关系、零生效（批次 2 R1 教训） -->
      <el-col :md="24" :lg="10">
        <!-- 左队列卡（蓝图 P11.2）：原 el-card #header 升卷宗卡头（衬线题+右挂段落语义），
             计数迁门牌胶囊，卡头只留两段队列语义注记 -->
        <section class="fuy-card fuy-dense">
          <header class="fuy-card-head">
            <h2 class="fuy-card-title">工作台队列</h2>
            <div class="fuy-card-extra">待发 / 调剂中</div>
          </header>
          <div class="fuy-card-body">
            <el-table
              :data="queue"
              v-loading="loading"
              highlight-current-row
              class="dispense-workbench-queue"
              @row-click="onSelect"
            >
              <!-- 处方号/就诊号=单据号码类字段，等宽通道承域级「号码类一律 .fuy-num」 -->
              <el-table-column prop="rxNo" label="处方号" min-width="180" class-name="fuy-num" />
              <!-- F-8 注记：PrescriptionVO 无姓名字段，患者列直显雪花 ID 属契约缺口（不虚构字段），
                   后端补姓名后随 P2 演进，此处保持原样 -->
              <el-table-column prop="patientId" label="患者" min-width="150" />
              <el-table-column prop="visitId" label="就诊号" min-width="120" class-name="fuy-num" />
              <el-table-column label="选择" width="64">
                <template #default="{ row }">
                  <!-- 选择按钮：键盘可达的选单第二通道（stop 防与行点击双触发，F-4 收口；
                       文本「选择」不与配药/核对/发药签名既有按钮文案冲突） -->
                  <el-button link type="primary" @click.stop="onSelect(row)">选择</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <!-- 队列空态（契约 ⑥ .fuy-empty 脸禁纸箱插画）：主句合「暂无〈业务客体〉」语法，
                     说明句给下一步 -->
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无待发/调剂中处方</p>
                  <p class="fuy-empty-hint">新处方放行生成发药单后自动入列。</p>
                </div>
              </template>
            </el-table>
          </div>
        </section>
      </el-col>
      <el-col :md="24" :lg="14" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 发药单显隐 §6.7（appear 供首次挂载即播——批次 2 R1 教训）：选单后 200ms 淡入 -->
        <Transition name="fuy-content-fade" appear>
          <!-- 右发药单卡（蓝图 P11.2）：单据头=发药单号+单状态 tag → 双签步点条 → 明细行编辑
               → 动作区按当前步视觉分级；「换脸不换业务」——三动作启停/互斥/双签判定零变动 -->
          <section v-if="dispense" class="fuy-card fuy-dense dispense-workbench-sheet">
            <header class="fuy-card-head">
              <!-- 卡头单号走 .fuy-num 等宽通道（tabular-nums，域级「单据主号一律等宽」；
                   衬线只承题面文字，号码是数据面——字体三分不破） -->
              <h2 class="fuy-card-title">
                发药单 <span class="fuy-num">{{ dispense.dispenseNo }}</span>
              </h2>
              <!-- 单状态 tag 迁卡头右挂（§4.3 映射法；aa 修正 warning/success 文字色）：
                   使三按钮启停语义显性化，不改按钮启停逻辑 -->
              <div class="fuy-card-extra">
                <el-tag
                  :type="dispenseStatusTagType[dispense.status ?? ''] ?? 'info'"
                  class="fuy-tag-aa"
                >
                  {{ dispenseStatusText(dispense.status) }}
                </el-tag>
              </div>
            </header>
            <div class="fuy-card-body">
              <!-- 双签步点条（蓝图 P11.2 禁照抄点）：四节点纯展示使状态机可见——当前步浓墨
                   实心、已过步浓墨空心、未到步弱墨；静态形状零动画，成本≈0；aria-current
                   承第二通道（色永不单独承义） -->
              <ol class="dispense-workbench-steps" aria-label="双签发药进度">
                <li
                  v-for="(step, index) in DISPENSE_STEPS"
                  :key="step.status"
                  class="dispense-workbench-step"
                  :class="stepClass(index)"
                  :aria-current="index === currentStepIndex ? 'step' : undefined"
                >
                  <i class="dispense-workbench-step-dot" aria-hidden="true"></i>
                  <span class="dispense-workbench-step-label">{{ step.label }}</span>
                </li>
              </ol>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="调配人">{{
                  dispense.picker || '—'
                }}</el-descriptions-item>
                <el-descriptions-item label="核对人">{{
                  dispense.verifier || '—'
                }}</el-descriptions-item>
              </el-descriptions>
              <el-table :data="dispense.items ?? []" class="dispense-workbench-items">
                <!-- 项目编码/批次号=号码族列，与应发数字列同挂 .fuy-num 等宽（域级一致性） -->
                <el-table-column
                  prop="itemCode"
                  label="项目"
                  min-width="110"
                  class-name="fuy-num"
                />
                <el-table-column
                  prop="requestedQuantity"
                  label="应发"
                  width="90"
                  align="right"
                  class-name="fuy-num"
                />
                <el-table-column prop="batchNo" label="批次" min-width="130" class-name="fuy-num" />
                <el-table-column label="追溯码录入" min-width="220">
                  <template #default="{ row }">
                    <el-input
                      v-model="traceInputs[row.id ?? '']"
                      :disabled="dispense.status !== 'CREATED'"
                      placeholder="逐盒扫码，逗号分隔"
                    />
                  </template>
                </el-table-column>
              </el-table>
              <div class="dispense-workbench-actions">
                <!-- 在途防抖（W-22⑥）：:disabled 叠加在途标志 + :loading 双保险，三动作互斥共用 dispensing；
                     配药/核对/发药签名三入口同码 v-perm 直挂（PR-4F #19，PHARMACIST 绑定）——
                     无码 DOM 移除（D-34），与 isPicker 双签 :disabled 数据态正交叠加。
                     视觉分级（蓝图 P11.2）：仅当前步动作挂 type=primary 墨实底、其余默认描边——
                     actionType 纯展示分级，:disabled/:loading 启停面零变动 -->
                <el-button
                  v-perm="'pharmacy:dispense:btn:issue'"
                  :type="actionType(0)"
                  :disabled="dispense.status !== 'CREATED' || dispensing"
                  :loading="dispensing"
                  @click="onPick"
                  >配药</el-button
                >
                <el-button
                  v-perm="'pharmacy:dispense:btn:issue'"
                  :type="actionType(1)"
                  :disabled="dispense.status !== 'PICKING' || isPicker() || dispensing"
                  :loading="dispensing"
                  :title="isPicker() ? '调配人不可自行核对/发药' : undefined"
                  @click="onVerify"
                  >核对</el-button
                >
                <el-button
                  v-perm="'pharmacy:dispense:btn:issue'"
                  :type="actionType(2)"
                  :disabled="dispense.status !== 'PICKED' || isPicker() || dispensing"
                  :loading="dispensing"
                  :title="isPicker() ? '调配人不可自行核对/发药' : undefined"
                  @click="onIssue"
                  >发药签名</el-button
                >
              </div>
            </div>
          </section>
        </Transition>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：暖纸卷宗换脸（蓝图 P11）——门牌页首/卷宗卡/空态脸样式全部由
   element-plus.css 全局挂类承载（.fuy-page-head/.fuy-card/.fuy-empty 既有类零改动），本块只留
   双签步点条纯 CSS 与表格区 CLS 锁定/明细表/按钮组间距；字号间距颜色全部 token 化，
   零新色值、零新增 @keyframes、零动画（步点条=静态形状） */

.dispense-workbench-queue {
  min-height: 240px; /* 队列表加载/空态切换零塌陷（§7.1 CLS 锁定） */
}

.dispense-workbench-items {
  margin-top: 12px;
}

.dispense-workbench-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

/* 双签步点条（蓝图 P11.3）：高 24px 锁 CLS，几何点+发丝连线纯 CSS；节点间连线复用
   --fuy-border-hairline 整值（语义即 1px 发丝），静态零动画（成本≈0） */
.dispense-workbench-steps {
  display: flex;
  align-items: center;
  height: 24px;
  margin: 0 0 var(--fuy-space-3);
  padding: 0;
  list-style: none;
}

.dispense-workbench-step {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
}

/* 发丝连线：相邻节点间 24px 细缝（发丝规线不越级——卡内细节归发丝） */
.dispense-workbench-step + .dispense-workbench-step::before {
  content: '';
  width: 24px;
  margin: 0 var(--fuy-space-2);
  border-top: var(--fuy-border-hairline);
}

/* 节点几何点：10px 圆点 border-box，默认=未到步弱墨描边 */
.dispense-workbench-step-dot {
  box-sizing: border-box;
  width: 10px;
  height: 10px;
  border: 1px solid var(--fuy-ink-faint);
  border-radius: 50%;
  background: transparent;
}

.dispense-workbench-step-label {
  color: var(--fuy-ink-faint); /* 未到步弱墨文字 */
}

/* 当前步：浓墨实心点 + 浓墨 600 文字（双通道：形状+字重） */
.dispense-workbench-step.is-current .dispense-workbench-step-dot {
  border-color: var(--fuy-color-text-emphasis);
  background: var(--fuy-color-text-emphasis);
}

.dispense-workbench-step.is-current .dispense-workbench-step-label {
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

/* 已过步：浓墨空心点（透明底 2px 墨环）+ 灰墨文字 */
.dispense-workbench-step.is-passed .dispense-workbench-step-dot {
  border: 2px solid var(--fuy-color-text-emphasis);
}

.dispense-workbench-step.is-passed .dispense-workbench-step-label {
  color: var(--fuy-color-text-secondary);
}
</style>
