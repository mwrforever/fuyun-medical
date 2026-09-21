<script setup lang="ts">
// 划价结算页（FU-M13-02/03/04 收费员工作台）：检索条 → 划价行编辑+预计价 → 待收费用回显 →
// 预结算锁价 → 确认弹框 → 正式结算出网；金额全程 string 承载、仅经 fenToYuanDisplay 展示，
// 页面零金额运算（总 Spec D5）；幂等由后端 settleNo 终态承载，前端不生成任何流水键。
// 弹错归响应拦截器（web A.3-2）；失败驻留旧结果。
import { reactive, ref } from 'vue';
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
  SettlementPreviewVO,
  SettlementVO,
} from '@/api/billing';
import { fenToYuanDisplay } from '@/utils/money';

/** 患者号（建档 id，string 承载雪花 ID；划价/预结算/手工计费入参） */
const patientId = ref('');
/** 就诊号（CF-3，检索与全部操作的锚点） */
const visitId = ref('');

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
/** 预结算草稿（确认结算以回传 settleNo/totalAmount 为唯一出网依据） */
const preview = ref<SettlementPreviewVO | null>(null);
/** 最近一次结算终态（成功横幅展示，string 金额原样透出） */
const settled = ref<SettlementVO | null>(null);

const feesLoading = ref(false);
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

/** 加载待收费用（后端 0 基缺省分页，本页取 PENDING 行展示） */
async function loadFees(): Promise<void> {
  feesLoading.value = true;
  try {
    const page = await listFees({ visitId: visitId.value.trim() });
    pendingFees.value = page.content.filter((row) => row.status === 'PENDING');
  } catch {
    // 失败弹错归响应拦截器；驻留旧结果
  } finally {
    feesLoading.value = false;
  }
}

/** 查询费用：就诊号空前置拦截不出网 */
async function handleQueryFees(): Promise<void> {
  if (!requireVisit()) {
    return;
  }
  await loadFees();
}

/** 增一行划价编辑行 */
function handleAddLine(): void {
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
 * 预结算：自费口径本地聚合锁价（医保模拟回执后续接入，payerType 暂固定 SELF_PAY）。
 * 结果仅存草稿（settleNo/totalAmount），确认结算以此出网，前端不生成流水键。
 */
async function handlePreview(): Promise<void> {
  if (!requireVisit()) {
    return;
  }
  if (patientId.value.trim() === '') {
    void ElMessage.warning('请输入患者号');
    return;
  }
  previewing.value = true;
  try {
    preview.value = await previewSettlement({
      patientId: patientId.value.trim(),
      visitId: visitId.value.trim(),
      payerType: 'SELF_PAY',
    });
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    previewing.value = false;
  }
}

/**
 * 确认结算：弹框核对总额 → settle 以预结算回传 settleNo + 全现金单行 payments 出网
 * （2026-09-17 裁决①：Σamount==totalAmount 勾稽由后端 BILL-1016 兜底，页面零运算直透
 *  string 分值；就诊卡 CARD_BALANCE 混行随选卡页后续模块接入）。
 */
async function handleSettle(): Promise<void> {
  const draft = preview.value;
  if (draft === null || draft.settleNo === undefined) {
    void ElMessage.warning('请先执行预结算');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `应缴总额 ${fenToYuanDisplay(draft.totalAmount ?? '0')} 元（现金），确认结算？`,
      '结算确认',
      { type: 'warning' },
    );
  } catch {
    // 用户取消：草稿驻留，可再次点击结算
    return;
  }
  settling.value = true;
  try {
    settled.value = await settle({
      settleNo: draft.settleNo,
      payments: [{ method: 'CASH', amount: draft.totalAmount ?? '' }],
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
  <div class="pricing-settle">
    <el-card class="pricing-settle-main">
      <template #header>划价结算</template>
      <div class="pricing-settle-bar">
        <el-input v-model="patientId" placeholder="患者号" class="pricing-settle-input" clearable />
        <el-input v-model="visitId" placeholder="就诊号" class="pricing-settle-input" clearable />
        <el-button :loading="feesLoading" @click="handleQueryFees">查询费用</el-button>
        <el-button @click="openManual">手工计费</el-button>
      </div>

      <!-- 划价行编辑区：itemCode/quantity 两列可增删行（金额由后端按快照算，前端不填） -->
      <h4 class="pricing-settle-section">预计价（划价）</h4>
      <el-table :data="quoteLines" size="small" class="pricing-settle-quote-edit">
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
      </el-table>
      <div class="pricing-settle-actions">
        <el-button @click="handleAddLine">增行</el-button>
        <el-button type="primary" :loading="quoting" @click="handleQuote">划价</el-button>
      </div>

      <!-- 划价结果：金额经 fenToYuanDisplay 分→元展示；无对照行标「仅自费」 -->
      <template v-if="quoteResult">
        <h4 class="pricing-settle-section">
          划价结果（合计 {{ fenToYuanDisplay(quoteResult.totalAmount ?? '0') }} 元）
        </h4>
        <el-table :data="quoteResult.lines ?? []" size="small">
          <el-table-column prop="itemName" label="项目" min-width="160" />
          <el-table-column prop="itemCode" label="编码" min-width="120" />
          <el-table-column label="单价（元）" width="120">
            <template #default="{ row }">{{ fenToYuanDisplay(row.unitPrice ?? '0') }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="90" />
          <el-table-column label="金额（元）" width="120">
            <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
          </el-table-column>
          <el-table-column label="自费标记" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.selfExpenseOnly" type="warning">仅自费</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card class="pricing-settle-main">
      <template #header>待收费用</template>
      <el-table v-loading="feesLoading" :data="pendingFees" size="small">
        <el-table-column prop="feeNo" label="费用号" min-width="180" />
        <el-table-column prop="itemNameSnapshot" label="项目" min-width="140" />
        <el-table-column label="单价（元）" width="110">
          <template #default="{ row }">{{
            fenToYuanDisplay(row.unitPriceSnapshot ?? '0')
          }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column label="金额（元）" width="110">
          <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
        </el-table-column>
      </el-table>
      <div class="pricing-settle-actions">
        <el-button :loading="previewing" @click="handlePreview">预结算</el-button>
        <el-button
          type="primary"
          :disabled="preview === null"
          :loading="settling"
          @click="handleSettle"
        >
          确认结算
        </el-button>
      </div>
      <el-alert
        v-if="settled"
        :title="`结算完成：${settled.settleNo ?? ''}，总额 ${fenToYuanDisplay(settled.totalAmount ?? '0')} 元`"
        type="success"
        show-icon
        :closable="false"
        class="pricing-settle-done"
      />
    </el-card>

    <!-- 手工计费弹窗（FU-M13-02 补录通道，理由必填留痕） -->
    <el-dialog v-model="manualVisible" title="手工计费" width="420px">
      <el-form label-width="90px">
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
/* 视图级样式隔离（web A.1-2） */
.pricing-settle {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 1080px;
}

.pricing-settle-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.pricing-settle-input {
  max-width: 240px;
}

.pricing-settle-section {
  margin: 16px 0 8px;
  font-size: 14px;
  color: var(--el-text-color-primary);
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
