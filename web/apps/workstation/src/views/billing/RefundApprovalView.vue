<script setup lang="ts">
// 退费审批页（FU-M13-04 收费员/审批员工作台）：上区=退费申请（按结算号查摘要 → 选已结算
// 费用行 + 数量 + 理由 → applyRefund），下区=审批队列（status 筛选 + 批准/驳回/执行按态启停）。
// 红线：退费金额由后端按明细聚合，前端只传费用行与数量；双人守卫自审拒绝由后端 403 承载，
// 经响应拦截器统一弹错（web A.3-2）。金额 string 承载仅展示换算（web A.3-6）。
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import {
  applyRefund,
  approveRefund,
  executeRefund,
  getSettlement,
  listFees,
  listRefunds,
  rejectRefund,
} from '@/api/billing';
import type { FeeRecordVO, RefundVO, SettlementVO } from '@/api/billing';
import { fenToYuanDisplay } from '@/utils/money';

/** 退费申请行模型：费用行 + 本行申请退数量（可编辑，上限=原数量） */
interface RefundRow {
  fee: FeeRecordVO;
  qty: number;
}

/** 结算号输入（string 承载业务单号，非雪花 id 但同为字符串契约） */
const settleNo = ref('');
/** 结算摘要（按号查询回显，退费申请锚点） */
const settlement = ref<SettlementVO | null>(null);
/** 该结算单下可退费用行（已结算/部分退状态且归属本结算单） */
const refundRows = ref<RefundRow[]>([]);
/** 当前勾选行（applyRefund 明细来源） */
const selectedRows = ref<RefundRow[]>([]);
/** 退费理由（必填留痕） */
const reason = ref('');
const summaryLoading = ref(false);
const applying = ref(false);

/**
 * 按结算号查摘要并带出可退明细。
 * 空结算号前置拦截不出网；行过滤以 settlementId 归属 + 已结算/部分退状态双条件。
 */
async function handleQuerySettlement(): Promise<void> {
  if (settleNo.value.trim() === '') {
    void ElMessage.warning('请输入结算号');
    return;
  }
  summaryLoading.value = true;
  try {
    const detail = await getSettlement(settleNo.value.trim());
    settlement.value = detail;
    if (detail.visitId === undefined) {
      refundRows.value = [];
      return;
    }
    const page = await listFees({ visitId: detail.visitId });
    refundRows.value = page.content
      .filter(
        (row) =>
          row.settlementId !== undefined &&
          row.settlementId === detail.id &&
          (row.status === 'SETTLED' || row.status === 'PART_REFUND'),
      )
      .map((fee) => ({ fee, qty: 1 }));
    selectedRows.value = [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧摘要
  } finally {
    summaryLoading.value = false;
  }
}

/**
 * 表格勾选回传。
 *
 * @param rows 勾选中的退费行
 */
function handleSelectionChange(rows: RefundRow[]): void {
  selectedRows.value = rows;
}

/** 提交退费申请：勾选行+数量入明细，金额由后端聚合；成功后刷新队列 */
async function handleApply(): Promise<void> {
  if (settlement.value?.id === undefined) {
    void ElMessage.warning('请先按结算号查询结算摘要');
    return;
  }
  if (selectedRows.value.length === 0) {
    void ElMessage.warning('请勾选需退费的费用行');
    return;
  }
  if (reason.value.trim() === '') {
    void ElMessage.warning('退费理由必填');
    return;
  }
  applying.value = true;
  try {
    await applyRefund({
      settlementId: settlement.value.id,
      lines: selectedRows.value.map((row) => ({
        feeId: row.fee.id ?? '',
        refundQuantity: row.qty,
      })),
      reason: reason.value.trim(),
    });
    void ElMessage.success('退费申请已提交');
    reason.value = '';
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器（免审阈值/占用冲突等按后端文案透出）
  } finally {
    applying.value = false;
  }
}

/** 审批队列状态筛选（空串=全部）；词表与生成契约 RefundVO.status 同源 */
const statusFilter = ref('');
/** 审批队列数据（服务端分页，本页缺省单页） */
const queue = ref<RefundVO[]>([]);
const queueLoading = ref(false);

/** 队列状态展示词表（未知态原样透出，防后端扩态即白屏） */
const refundStatusText: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  EXECUTED: '已执行',
  REJECTED: '已驳回',
};

/** 队列动作按态启停（双人守卫与终态不可逆由后端强制，前端仅抑制无效点击） */
function canApprove(row: RefundVO): boolean {
  return row.status === 'PENDING_APPROVAL';
}
function canReject(row: RefundVO): boolean {
  return row.status === 'PENDING_APPROVAL';
}
function canExecute(row: RefundVO): boolean {
  return row.status === 'APPROVED';
}

/** 加载审批队列（status 空=全部） */
async function loadQueue(): Promise<void> {
  queueLoading.value = true;
  try {
    const page = await listRefunds({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
    });
    queue.value = page.content;
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    queueLoading.value = false;
  }
}

/** 批准退费（自审场景后端 403 拒绝，拦截器弹错后驻留队列） */
async function handleApprove(row: RefundVO): Promise<void> {
  try {
    await approveRefund(row.id ?? '');
    void ElMessage.success('已批准');
    await loadQueue();
  } catch {
    // 双人守卫拒绝等错误归响应拦截器统一提示
  }
}

/** 驳回退费（弹窗理由必填，与后端校验同口径） */
async function handleReject(row: RefundVO): Promise<void> {
  let input: { value: string };
  try {
    input = await ElMessageBox.prompt('请输入驳回理由', '驳回退费申请', {
      inputValidator: (text: string) => (text.trim() === '' ? '驳回理由必填' : true),
    });
  } catch {
    // 用户关闭弹窗=放弃驳回
    return;
  }
  try {
    await rejectRefund(row.id ?? '', input.value.trim());
    void ElMessage.success('已驳回');
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器
  }
}

/** 执行退费（审批通过后原路退回；阈值内免审单由后端已直批，此处覆盖 APPROVED 态） */
async function handleExecute(row: RefundVO): Promise<void> {
  try {
    await executeRefund(row.id ?? '');
    void ElMessage.success('已执行原路退回');
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器
  }
}

onMounted(() => {
  void loadQueue();
});
</script>

<template>
  <div class="refund-approval">
    <el-card class="refund-approval-main">
      <template #header>退费申请</template>
      <div class="refund-approval-bar">
        <el-input
          v-model="settleNo"
          placeholder="结算号"
          class="refund-approval-input"
          clearable
          @keyup.enter="handleQuerySettlement"
        />
        <el-button type="primary" :loading="summaryLoading" @click="handleQuerySettlement">
          查询结算
        </el-button>
      </div>
      <el-descriptions v-if="settlement" :column="4" border class="refund-approval-summary">
        <el-descriptions-item label="结算号">{{ settlement.settleNo }}</el-descriptions-item>
        <el-descriptions-item label="就诊号">{{ settlement.visitId }}</el-descriptions-item>
        <el-descriptions-item label="结算金额（元）">
          {{ fenToYuanDisplay(settlement.totalAmount ?? '0') }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ settlement.status }}</el-descriptions-item>
      </el-descriptions>
      <el-table
        v-if="settlement"
        :data="refundRows"
        size="small"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column prop="fee.feeNo" label="费用号" min-width="170" />
        <el-table-column prop="fee.itemNameSnapshot" label="项目" min-width="130" />
        <el-table-column label="单价（元）" width="100">
          <template #default="{ row }">
            {{ fenToYuanDisplay(row.fee.unitPriceSnapshot ?? '0') }}
          </template>
        </el-table-column>
        <el-table-column label="原数量" width="80" prop="fee.quantity" />
        <el-table-column label="金额（元）" width="100">
          <template #default="{ row }">{{ fenToYuanDisplay(row.fee.amount ?? '0') }}</template>
        </el-table-column>
        <el-table-column label="退数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="1" :max="row.fee.quantity ?? 1" />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="settlement" class="refund-approval-apply">
        <el-input
          v-model="reason"
          placeholder="退费理由（必填留痕）"
          class="refund-approval-reason"
        />
        <el-button type="primary" :loading="applying" @click="handleApply">申请退费</el-button>
      </div>
    </el-card>

    <el-card class="refund-approval-main">
      <template #header>审批队列</template>
      <div class="refund-approval-bar">
        <el-select v-model="statusFilter" class="refund-approval-filter" @change="loadQueue">
          <el-option value="" label="全部" />
          <el-option value="PENDING_APPROVAL" label="待审批" />
          <el-option value="APPROVED" label="已批准" />
          <el-option value="EXECUTED" label="已执行" />
        </el-select>
        <el-button @click="loadQueue">刷新</el-button>
      </div>
      <el-table v-loading="queueLoading" :data="queue" size="small">
        <el-table-column prop="refundNo" label="退费单号" min-width="170" />
        <el-table-column label="金额（元）" width="110">
          <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="理由" min-width="140" />
        <el-table-column prop="applicant" label="申请人" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'EXECUTED' ? 'success' : 'info'">
              {{ refundStatusText[row.status ?? ''] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button size="small" :disabled="!canApprove(row)" @click="handleApprove(row)">
              批准
            </el-button>
            <el-button size="small" :disabled="!canReject(row)" @click="handleReject(row)">
              驳回
            </el-button>
            <el-button size="small" :disabled="!canExecute(row)" @click="handleExecute(row)">
              执行
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.refund-approval {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 1080px;
}

.refund-approval-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.refund-approval-input {
  max-width: 280px;
}

.refund-approval-filter {
  width: 160px;
}

.refund-approval-summary {
  margin-bottom: 12px;
}

.refund-approval-apply {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

.refund-approval-reason {
  max-width: 420px;
}
</style>
