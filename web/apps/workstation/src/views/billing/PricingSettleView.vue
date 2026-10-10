<script setup lang="ts">
// 划价结算页（FU-M13-02/03/04 收费员工作台 · 暖纸卷宗 P07 蓝图重排）：检索条 → 划价行编辑+
// 预计价 → 待收费用回显 → 预结算锁价 → 确认弹框 → 正式结算出网；金额全程 string 承载、
// 仅经 fenToYuanDisplay 展示，页面零金额运算（总 Spec D5）；幂等由后端 settleNo 终态承载，
// 前端不生成任何流水键。弹错归响应拦截器（web A.3-2）；失败驻留旧结果。
// 构图（蓝图 P07.2/7.3「换脸不换业务」）：门牌页首（衬线标题 + 签认人·时刻批注行）→
// 检索域 .fuy-filter（患者号/就诊号/查询墨实底钮｜支付方式下拉｜手工计费钮右挂）→ 主从
// 工作区 grid 3fr 2fr——双卡纵叠改「划价主列 + 待收/结算 sticky 辅列」，资金动作链
// （预结算 → 确认结算 → 成功横幅）右列纵向收口；手工计费弹窗挂 .fuy-dialog + .fuy-form。
// 金额计算/试算/结算出网逻辑零改动；ElMessageBox 函数式确认族维持 EP 默认皮不挂类（总则 4）；
// 页根不挂 .fuy-stagger（契约 ⑦.4 路由过渡归 MainLayout），页内 stagger 两档（主列 0/辅列 1）。
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
// ElMessageBox 确认弹窗在模板外使用，按需样式手动补引（F-1 缺口闭合）
import 'element-plus/es/components/message-box/style/css';
import { listFees, manualCharge, previewSettlement, quote, settle } from '@/api/billing';
import type {
  FeeRecordVO,
  ManualChargeRequest,
  QuoteVO,
  SettleRequest,
  SettlementPreviewRequest,
  SettlementPreviewVO,
  SettlementVO,
} from '@/api/billing';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { fenToYuanDisplay } from '@/utils/money';

// 会话入口（门牌批注行「谁」签认人；元素权限仍由 v-perm 判定，互不影响）
const auth = useAuthStore();

/** 门牌批注行「谁」：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/**
 * 门牌批注行「何时」（YYYY-MM-DD 周X）：与首页门牌同语法的病历页眉日期批注，
 * 纯本地时钟零出网。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 患者号（建档 id，string 承载雪花 ID；划价/预结算/手工计费入参） */
const patientId = ref('');
/** 就诊号（CF-3，检索与全部操作的锚点） */
const visitId = ref('');

/** 支付方式代码（契约直取 PayerType 五档字面量联合；非法 code 由后端 400 承载，前端不另设词表校验） */
type PayerTypeCode = SettlementPreviewRequest['payerType'];

/** 支付方式五档词表（与后端 PayerType 枚举同源的内联常量；中文标签供工具栏下拉展示与
 *  结算确认文案插值共用，W-41 参数化后单一来源） */
const PAYER_TYPE_OPTIONS: ReadonlyArray<{ value: PayerTypeCode; label: string }> = [
  { value: 'SELF_PAY', label: '自费' },
  { value: 'CITY_INS', label: '市医保' },
  { value: 'PROV_INS', label: '省医保' },
  { value: 'OUTSIDE_INS', label: '异地医保' },
  { value: 'COMM_INS', label: '商业保险' },
];

/** 所选支付方式（默认自费）：预结算出网携值；医保档预览拆分可用但结算被前置守卫拦截
 *  （通道待 W-80 接入），确认文案随所选档中文标签联动 */
const payerType = ref<PayerTypeCode>('SELF_PAY');

/** 划价行编辑模型（与 QuoteRequest.Line 同构；quantity 为数量非金额，number 合法） */
interface QuoteLineEdit {
  itemCode: string;
  quantity: number;
}
/** 划价编辑行（默认一行空行，支持增删） */
const quoteLines = reactive<QuoteLineEdit[]>([{ itemCode: '', quantity: 1 }]);
/** 预计价结果（后端逐行价+合计，金额 string 原样承载，禁 number 转换点） */
const quoteResult = ref<QuoteVO | null>(null);

/** 待收费用行（listFees 回显，仅 PENDING 状态进入本表） */
const pendingFees = ref<FeeRecordVO[]>([]);
/** 预结算草稿（确认结算以回传 settleNo/totalAmount 为唯一出网依据；档位切换即作废——
 *  草稿与所选支付方式强一致，见下方 watch） */
const preview = ref<SettlementPreviewVO | null>(null);

// 切档即作废预结算草稿（W-41 补）：草稿金额/流水键均按预结算当时档位生成（医保档为贯标
// 校验+网关拆分后的 PRESETTLED 形态）。若草稿跨档存活——典型如医保草稿切回自费档——settle
// 守卫按当前档（自费）放行，buildPaymentLines 将以医保草稿的 totalAmount 全 CASH 出网且
// settleNo 落在医保单上，形成医保单被全现金结算的勾稽语义错位。作废后 settle 入口由
// 「请先执行预结算」既有守卫自然拦截，强制按新档重做预结算。
watch(payerType, () => {
  preview.value = null;
});
/** 最近一次结算终态（成功横幅展示，string 金额原样透出）。常驻业务锚点：横幅不随新查询
 * 自动清除，驻留至下一次结算成功覆盖——新查询后旧横幅与摘要并存属既定口径，仅注记不改逻辑 */
const settled = ref<SettlementVO | null>(null);

const quoting = ref(false);
const previewing = ref(false);
const settling = ref(false);

/**
 * 就诊号前置校验（空号不出网，防全表扫描；手工计费/划价/预结算共用）。
 *
 * @return true=通过可继续出网
 */
function requireVisit(): boolean {
  if (visitId.value.trim() === '') {
    void ElMessage.warning('请输入就诊号');
    return false;
  }
  return true;
}

/** 加载待收费用（后端 0 基缺省分页，本页取 PENDING 行展示）。承载 EX-45/FE-A1-05
 * 判空与竞态收口：回包 content 缺失兜底空清单（不驻留旧就诊费用误导收费员）；发起时
 * 锚定当前就诊号，回包时已改号（查询/手工计费/结算后刷新多入口并发）则整包丢弃。
 * loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——失败弹错归
 * 响应拦截器、驻留旧结果）。 */
const { loading: feesLoading, run: loadFees } = useAsyncTask(async () => {
  // 发起时锚定当前就诊号：回包前再改号（含手工计费等旁路刷新）即形成在途竞态
  const visitAtRequest = visitId.value.trim();
  const page = await listFees({ visitId: visitAtRequest });
  // 过期回包丢弃：旧就诊慢回包晚到不得覆盖新就诊的待收表
  if (visitId.value.trim() !== visitAtRequest) {
    return;
  }
  // 判空兜底：契约外 content 缺失按空数据处理，防驻留上一就诊的旧费用
  pendingFees.value = (page?.content ?? []).filter((row) => row.status === 'PENDING');
});

/** 查询费用：就诊号空前置拦截不出网 */
async function handleQueryFees(): Promise<void> {
  if (!requireVisit()) {
    return;
  }
  await loadFees();
}

/** 划价行编辑软上限（§9.7-3：防行编辑组件树膨胀，超限前置拦截不出网不增行） */
const QUOTE_LINE_MAX = 20;

/** 增一行划价编辑行（软上限 20 行：超限仅提示驻留，行数与组件树不继续膨胀） */
function handleAddLine(): void {
  if (quoteLines.length >= QUOTE_LINE_MAX) {
    void ElMessage.warning('划价行数已达上限 20 行');
    return;
  }
  quoteLines.push({ itemCode: '', quantity: 1 });
}

/**
 * 删除划价编辑行。
 *
 * @param index 行下标
 */
function handleRemoveLine(index: number): void {
  quoteLines.splice(index, 1);
}

/** 预计价：就诊号/患者号前置拦截，仅送有效行（itemCode 非空），结果渲染不含落库 */
async function handleQuote(): Promise<void> {
  if (!requireVisit()) {
    return;
  }
  if (patientId.value.trim() === '') {
    void ElMessage.warning('请输入患者号');
    return;
  }
  const lines = quoteLines
    .filter((l) => l.itemCode.trim() !== '')
    .map((l) => ({ itemCode: l.itemCode.trim(), quantity: l.quantity }));
  if (lines.length === 0) {
    void ElMessage.warning('请至少录入一行划价项目');
    return;
  }
  quoting.value = true;
  try {
    quoteResult.value = await quote({
      patientId: patientId.value.trim(),
      visitId: visitId.value.trim(),
      lines,
    });
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    quoting.value = false;
  }
}

/** 手工计费弹窗状态与表单（红线 3：操作者由后端登录上下文注入，前端不传） */
const manualVisible = ref(false);
const manualSubmitting = ref(false);
const manualForm = reactive<Omit<ManualChargeRequest, 'patientId' | 'visitId'>>({
  itemCode: '',
  quantity: 1,
  reason: '',
});

/** 打开手工计费弹窗（先验就诊/患者号，防弹窗提交时才拦截的体验断层） */
function openManual(): void {
  if (!requireVisit()) {
    return;
  }
  if (patientId.value.trim() === '') {
    void ElMessage.warning('请输入患者号');
    return;
  }
  manualVisible.value = true;
}

/** 提交手工计费：项目编码与理由必填，成功后关闭并刷新待收表 */
async function submitManual(): Promise<void> {
  if (manualForm.itemCode.trim() === '' || manualForm.reason.trim() === '') {
    void ElMessage.warning('项目编码与理由必填');
    return;
  }
  manualSubmitting.value = true;
  try {
    await manualCharge({
      patientId: patientId.value.trim(),
      visitId: visitId.value.trim(),
      itemCode: manualForm.itemCode.trim(),
      quantity: manualForm.quantity,
      reason: manualForm.reason.trim(),
    });
    void ElMessage.success('手工计费已入账');
    manualVisible.value = false;
    manualForm.itemCode = '';
    manualForm.reason = '';
    await loadFees();
  } catch {
    // 失败弹错归响应拦截器；弹窗驻留防补录数据丢失
  } finally {
    manualSubmitting.value = false;
  }
}

/**
 * 预结算：出网携工具栏所选支付方式（自费=本地聚合锁价；医保档=后端贯标校验+网关拆分
 * PRESETTLED）。结果仅存草稿（settleNo/totalAmount），确认结算以此出网，前端不生成流水键。
 */
async function handlePreview(): Promise<void> {
  if (!requireVisit()) {
    return;
  }
  if (patientId.value.trim() === '') {
    void ElMessage.warning('请输入患者号');
    return;
  }
  // 发起时锚定当前支付方式（D-1/E-1 在途竞态守卫，本页 loadFees 的 visitId 锚定丢弃 /
  // usePagedList 序号守卫同族）：await 挂起期间切档，watch 已按作废语义清场，旧档慢回包
  // 晚到若无条件赋值将复活跨档草稿（医保 PRESETTLED+当前自费档→settle 守卫放行→医保单
  // 全 CASH 结算）——回包时档位已变即整包丢弃，强制按新档重做预结算
  const payerTypeAtRequest = payerType.value;
  previewing.value = true;
  try {
    const vo = await previewSettlement({
      patientId: patientId.value.trim(),
      visitId: visitId.value.trim(),
      payerType: payerType.value,
    });
    // 过期回包丢弃：旧档慢回包不得复活已清空的草稿（与切档作废 watch 强一致）
    if (payerType.value !== payerTypeAtRequest) {
      return;
    }
    preview.value = vo;
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    previewing.value = false;
  }
}

/**
 * 组装 settle 支付明细（纯函数抽取，行为与原内联逐字一致，W-41）：自费档全现金单行——
 * 金额取预结算回传 totalAmount 的 string 分值直透（零运算零转换，Σamount==totalAmount
 * 勾稽由后端 BILL-1016 兜底）。医保档形态归 W-80 口径裁决（结算入口已前置拦截，本函数
 * 不会被医保档触达）。
 *
 * @param draft 预结算草稿（totalAmount 为 string 分值，允许缺省回退空串）
 * @return 支付明细（当前仅自费单行 CASH）
 */
function buildPaymentLines(draft: SettlementPreviewVO): SettleRequest['payments'] {
  return [{ method: 'CASH', amount: draft.totalAmount ?? '' }];
}

/**
 * 确认结算：非自费档前置守卫拦截（W-41，文案随所选档中文标签参数化——D-2）→ 弹框核对总额
 * （文案随所选支付方式中文标签联动）→ settle 以预结算回传 settleNo + 支付明细出网（幂等由
 * 后端 settleNo 终态承载；就诊卡 CARD_BALANCE 混行随选卡页后续模块接入）。
 */
async function handleSettle(): Promise<void> {
  // W-41：非自费档结算通道待接入——PaymentMethod 词表无医保基金/商保支付通道（六值 CASH/
  // BANK/SCAN/ONLINE/CARD_BALANCE/CHARGE_ON_CREDIT），payments 非自费组装形态归 W-80 口径
  // 裁决；前置拦截防非自费单被全现金额外误结算（勾稽语义错位：基金部分将被记作现金）
  if (payerType.value !== 'SELF_PAY') {
    // 拦截文案随所选档中文标签参数化（D-2）：商业保险档语义不再错位统称「医保」，
    // 通道统一口径不变——仅自费可正式结算，可先按所选档预览拆分
    const payerLabel =
      PAYER_TYPE_OPTIONS.find((opt) => opt.value === payerType.value)?.label ?? '自费';
    void ElMessage.warning(
      `${payerLabel}结算通道待接入，当前仅支持自费结算（可先预览${payerLabel}拆分）`,
    );
    return;
  }
  const draft = preview.value;
  if (draft === null || draft.settleNo === undefined) {
    void ElMessage.warning('请先执行预结算');
    return;
  }
  // 确认文案插值所选支付方式中文标签（守卫后此处恒为自费档，插值保持参数化形态供 W-80 接入复用）
  const payerLabel =
    PAYER_TYPE_OPTIONS.find((opt) => opt.value === payerType.value)?.label ?? '自费';
  try {
    // R-3：ElMessageBox 函数式挂载不继承 ConfigProvider locale（默认渲染英文 OK/Cancel），
    // 按钮文案显式中文（PatientDetailView 先例同款）；函数式弹窗族不可挂类维持 EP 默认皮
    // （暖纸总则 4，蓝图不要求改其视觉）
    await ElMessageBox.confirm(
      `应缴总额 ${fenToYuanDisplay(draft.totalAmount ?? '0')} 元（${payerLabel}），确认结算？`,
      '结算确认',
      {
        type: 'warning',
        confirmButtonText: '确认结算',
        cancelButtonText: '取消',
      },
    );
  } catch {
    // 用户取消：草稿驻留，可再次点击结算
    return;
  }
  settling.value = true;
  try {
    settled.value = await settle({
      settleNo: draft.settleNo,
      payments: buildPaymentLines(draft),
    });
    void ElMessage.success('结算成功');
    preview.value = null;
    await loadFees();
  } catch {
    // 失败弹错归响应拦截器；草稿驻留供重试（幂等由后端 settleNo 终态承载）
  } finally {
    settling.value = false;
  }
}
</script>

<template>
  <!-- 暖纸卷宗 P07 重排：页面纵向序=门牌页首 → 筛选卡 → 主从工作区（契约 ⑧/总则 6）。
       页根禁挂 .fuy-stagger（契约 ⑦.4：路由进场过渡归 MainLayout，防双重进场节奏）——
       琢段移交的页根 stagger 迁页内主从两列（蓝图 P07.4 stagger 两档：划价卡 0 / 待收卡 1） -->
  <div class="fuy-page">
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 签认人·时刻批注行 + 2px 墨规收底（脸样式归全局
         .fuy-page-head 族，本页零私有标题样式）；原 el-card #header「划价结算」升格于此 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">划价结算</h1>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <!-- 检索域（蓝图 P07.2 + 收费域级「检索先行」）：筛选卡 .fuy-filter——患者号/就诊号/
         查询墨实底钮｜支付方式下拉（W-41 切档作废既有）｜手工计费钮右挂 v-perm 既有（PR-4F #3：
         弹窗提交按钮随本入口同码不可达，免重复挂接） -->
    <section class="fuy-card">
      <div class="fuy-card-body fuy-filter">
        <label for="pricing-settle-patient">患者号</label>
        <el-input
          id="pricing-settle-patient"
          v-model="patientId"
          placeholder="患者号"
          class="pricing-settle-input"
          clearable
        />
        <label for="pricing-settle-visit">就诊号</label>
        <el-input
          id="pricing-settle-visit"
          v-model="visitId"
          placeholder="就诊号"
          class="pricing-settle-input"
          clearable
        />
        <el-button type="primary" :loading="feesLoading" @click="handleQueryFees"
          >查询费用</el-button
        >
        <!-- 支付方式选择（W-41）：预结算出网携值、确认文案联动；医保档仅可预览拆分，
             结算入口前置守卫拦截（通道待 W-80 接入） -->
        <label for="pricing-settle-payer">支付方式</label>
        <!-- 显式 popper 统一挂 fuy-snap-popper（契约 ⑤#5/⑦.5 硬 snap，P01 建档性别下拉同款） -->
        <el-select
          id="pricing-settle-payer"
          v-model="payerType"
          class="pricing-settle-payer"
          popper-class="fuy-snap-popper"
        >
          <el-option
            v-for="opt in PAYER_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <div class="fuy-filter-actions">
          <el-button v-perm="'billing:charge:btn:manual'" @click="openManual">手工计费</el-button>
        </div>
      </div>
    </section>

    <!-- 主从工作区（蓝图 P07.3）：grid 3fr 2fr——双卡纵叠同权重改主从分区；align-items:start
         使右列不被拉伸（拉伸后的列恒贴顶，sticky 失效） -->
    <div class="pricing-settle-workarea">
      <!-- 左：划价主列（stagger index 0）——行编辑表与划价结果同卡上下（撤销第二卡头层级），
           两节以「签」分隔线分界（契约 ⑧.2 卡内分区语法） -->
      <div class="fuy-stagger pricing-settle-main-col">
        <!-- fuy-dense 挂卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为后代
             选择器 .fuy-dense .el-table，挂表格自身不构成后代关系（批次 2 质量门 R1 教训）；
             表格 size="small" 移除——fuy-dense 唯一密度通道，无双轨混用（§9.9-2） -->
        <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 0 }">
          <header class="fuy-card-head">
            <h2 class="fuy-card-title">划价（预计价）</h2>
          </header>
          <div class="fuy-card-body">
            <!-- 划价行编辑区：itemCode/quantity 两列可增删行（金额由后端按快照算，前端不填）；
                 软上限 20 行由增行入口守卫（§9.7-3）；行编辑表零动画（蓝图 P07.4） -->
            <el-table :data="quoteLines" class="pricing-settle-quote-edit">
              <el-table-column label="项目编码" min-width="200">
                <template #default="{ row }">
                  <el-input v-model="row.itemCode" placeholder="项目编码" />
                </template>
              </el-table-column>
              <el-table-column label="数量" width="160">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="1" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="handleRemoveLine($index)">删除</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <!-- 诚实空态脸（契约 ⑥/总则 8）：主句「暂无」语法 + 说明给下一步 -->
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无划价行</p>
                  <p class="fuy-empty-hint">
                    点击「增行」录入划价项目，项目编码留空的行不参与预计价。
                  </p>
                </div>
              </template>
            </el-table>
            <div class="pricing-settle-actions">
              <el-button @click="handleAddLine">增行</el-button>
              <el-button type="primary" :loading="quoting" @click="handleQuote">划价</el-button>
            </div>

            <!-- 「签」分隔（蓝图 P07.2）：行编辑节与划价结果节的卡内文书分界 -->
            <div class="fuy-sign-divider pricing-settle-sign" aria-hidden="true"></div>

            <!-- 划价结果：金额经 fenToYuanDisplay 分→元展示；无对照行标「仅自费」；
                 结果显隐 200ms 淡入（§6.7，appear 供首次挂载即播——批次 2 R1 同款） -->
            <Transition name="fuy-content-fade" appear>
              <div v-if="quoteResult">
                <h4 class="fuy-section-title">
                  划价结果（合计 {{ fenToYuanDisplay(quoteResult.totalAmount ?? '0') }} 元）
                </h4>
                <el-table :data="quoteResult.lines ?? []">
                  <el-table-column prop="itemName" label="项目" min-width="160" />
                  <el-table-column prop="itemCode" label="编码" min-width="120" />
                  <el-table-column
                    label="单价（元）"
                    width="120"
                    align="right"
                    class-name="fuy-num"
                  >
                    <template #default="{ row }">{{
                      fenToYuanDisplay(row.unitPrice ?? '0')
                    }}</template>
                  </el-table-column>
                  <el-table-column
                    prop="quantity"
                    label="数量"
                    width="90"
                    align="right"
                    class-name="fuy-num"
                  />
                  <el-table-column
                    label="金额（元）"
                    width="120"
                    align="right"
                    class-name="fuy-num"
                  >
                    <template #default="{ row }">{{
                      fenToYuanDisplay(row.amount ?? '0')
                    }}</template>
                  </el-table-column>
                  <el-table-column label="自费标记" width="110">
                    <template #default="{ row }">
                      <el-tag v-if="row.selfExpenseOnly" type="warning" class="fuy-tag-aa"
                        >仅自费</el-tag
                      >
                      <span v-else>—</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </Transition>
          </div>
        </section>
      </div>

      <!-- 右：待收/结算 sticky 辅列（stagger index 1，蓝图 P07.2 sticky top 16）——资金动作链
           （预结算 → 确认结算 → 成功横幅）纵向收口的从列，随页面滚动常驻视口（CSS 契约，
           sticky 一次合成零 JS 代价） -->
      <div class="fuy-stagger pricing-settle-side-col">
        <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 1 }">
          <header class="fuy-card-head">
            <h2 class="fuy-card-title">待收费用</h2>
          </header>
          <div class="fuy-card-body">
            <el-table v-loading="feesLoading" :data="pendingFees">
              <el-table-column prop="feeNo" label="费用号" min-width="180" />
              <el-table-column prop="itemNameSnapshot" label="项目" min-width="140" />
              <el-table-column label="单价（元）" width="110" align="right" class-name="fuy-num">
                <template #default="{ row }">{{
                  fenToYuanDisplay(row.unitPriceSnapshot ?? '0')
                }}</template>
              </el-table-column>
              <el-table-column
                prop="quantity"
                label="数量"
                width="80"
                align="right"
                class-name="fuy-num"
              />
              <el-table-column label="金额（元）" width="110" align="right" class-name="fuy-num">
                <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
              </el-table-column>
              <template #empty>
                <!-- 诚实空态脸（契约 ⑥/总则 8）：查前引导与查无项目同脸，主句「暂无」语法 -->
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无待收费用</p>
                  <p class="fuy-empty-hint">输入就诊号查询待收项目；费用补录入账后此处自动刷新。</p>
                </div>
              </template>
            </el-table>
            <!-- 收费员结算动作（PR-4F #4）：预结算与确认结算同码 v-perm 直挂——权限决定在不在
                 DOM，preview 草稿/在途等数据态决定可不可点，两者正交叠加互不覆盖 -->
            <div class="pricing-settle-actions">
              <el-button
                v-perm="'billing:charge:btn:settle'"
                :loading="previewing"
                @click="handlePreview"
                >预结算</el-button
              >
              <el-button
                v-perm="'billing:charge:btn:settle'"
                type="primary"
                :disabled="preview === null"
                :loading="settling"
                @click="handleSettle"
              >
                确认结算
              </el-button>
            </div>
            <!-- 结算成功横幅（常驻业务锚点，驻留至下次结算覆盖）；显隐淡入 §6.7 -->
            <Transition name="fuy-content-fade" appear>
              <el-alert
                v-if="settled"
                :title="`结算完成：${settled.settleNo ?? ''}，总额 ${fenToYuanDisplay(settled.totalAmount ?? '0')} 元`"
                type="success"
                show-icon
                :closable="false"
                class="pricing-settle-done"
              />
            </Transition>
          </div>
        </section>
      </div>
    </div>

    <!-- 手工计费弹窗（FU-M13-02 补录通道，理由必填留痕）：挂 .fuy-dialog 弹层脸（卡面底+
         radius 14+shadow-lg+衬线标题，契约 ⑤#9）+ .fuy-form 表单脸（label 疏排/聚焦墨环/
         错误显影，契约 ⑤#1）；label-width 96px 系 §4.4 统一口径 -->
    <el-dialog v-model="manualVisible" title="手工计费" width="420px" class="fuy-dialog">
      <el-form class="fuy-form" label-width="96px">
        <el-form-item label="项目编码">
          <el-input v-model="manualForm.itemCode" placeholder="收费项目编码" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="manualForm.quantity" :min="1" />
        </el-form-item>
        <el-form-item label="理由">
          <el-input
            v-model="manualForm.reason"
            type="textarea"
            placeholder="补录理由（审计留痕）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualSubmitting" @click="submitManual"
          >确认计费</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：门牌页首/卷宗卡/筛选卡/空态脸/弹层与表单脸全局样式由
   element-plus.css 承载，本块只留页内布局（主从 grid + sticky 右列 + 签分隔节奏）与
   既有按钮组/横幅间距、input 宽度（暖纸 P07 重排） */

/* 主从工作区（蓝图 P07.3）：grid 3fr 2fr；align-items:start 使右列不被拉伸——sticky
   吸附的前提（拉伸后的列恒贴顶，sticky 失效） */
.pricing-settle-workarea {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--fuy-space-4);
  align-items: start;
}

/* 右列 sticky（蓝图 P07.2 待收费用卡 sticky top 16）：随页面滚动常驻视口，资金动作链
   纵向收口不随划价区滚动走散；sticky 一次合成零 JS 代价 */
.pricing-settle-side-col {
  position: sticky;
  top: var(--fuy-space-4);
  align-self: start;
}

/* 列 min-width:0（P09 双列同律）：防表格 min-content（待收表五列合计 620px）撑破 fr 轨道——
   真机 1440 实测无此守卫时主从两列合计 1232px 溢出容器 1112px，成功横幅被截出视口；
   收口后轨道恒等 fr 份额，超额列宽由 el-table 卡内横向滚动承载（业务列与金额口径零变动） */
.pricing-settle-main-col,
.pricing-settle-side-col {
  min-width: 0;
}

/* 表格 min-height 240（蓝图 P07.3 建）：锁行编辑表增删行与待收表加载/空态的 CLS（表体
   不塌陷跳高）；划价结果表随行数自然伸缩不入锁（仅随结果出现，无三态切换） */
.pricing-settle-main-col :deep(.pricing-settle-quote-edit),
.pricing-settle-side-col :deep(.el-table) {
  min-height: 240px;
}

/* 「签」分隔与划价结果节的卡内节奏（蓝图 P07.2 两节以签分隔分界，上 16/下 8 循工作面
   纵向节奏阶） */
.pricing-settle-sign {
  margin: var(--fuy-space-4) 0 var(--fuy-space-2);
}

.pricing-settle-input {
  max-width: 240px;
}

/* 支付方式下拉定宽（W-41）：el-select 默认宽 100% 会撑满工具栏剩余空间，照
   RefundApprovalView 筛选下拉 160px 口径约束（四字中文标签+箭头富余） */
.pricing-settle-payer {
  width: 160px;
}

.pricing-settle-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.pricing-settle-done {
  margin-top: 12px;
}
</style>
