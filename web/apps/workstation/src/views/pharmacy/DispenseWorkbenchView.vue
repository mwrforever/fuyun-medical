<script setup lang="ts">
// 药房发药工作台（FU-M06-04）：待发队列（PENDING_DISPENSE）→ 选处方回显发药单 →
// 配药（追溯码逐码录入）→ 核对（第二药师）→ 发药签名；双签分权由后端硬守卫（PH-1011），
// 前端以「当前用户 ID=调配人时禁用核对/发药」为辅助启停面。弹错归响应拦截器。
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import {
  issueDispense,
  listDispenses,
  listPrescriptions,
  pickDispense,
  verifyDispense,
} from '@/api/pharmacy';
import type { DispenseVO, PrescriptionVO } from '@/api/pharmacy';
import { useAuthStore } from '@/stores/auth';

/** 待发处方队列（status=PENDING_DISPENSE） */
const queue = ref<PrescriptionVO[]>([]);
/** 当前处方对应的发药单（null=未放行未选单） */
const dispense = ref<DispenseVO | null>(null);
/** 逐行追溯码录入模型（键=发药明细 id，与 PickLine.traceCodes 同构） */
const traceInputs = reactive<Record<string, string>>({});
const loading = ref(false);
const auth = useAuthStore();

/**
 * 加载工作台队列（发药签名成功后重刷）：待发（PENDING_DISPENSE）与调剂中（DISPENSING）
 * 两段合并——后端 pick 即 CAS 处方至 DISPENSING（PH 状态机），单查待发段会使第二药师
 * 无法从队列拉起在途单完成核对/发药签名（双签闭环必需）。
 */
async function loadQueue(): Promise<void> {
  loading.value = true;
  try {
    const [pending, dispensing] = await Promise.all([
      listPrescriptions({ status: 'PENDING_DISPENSE' }),
      listPrescriptions({ status: 'DISPENSING' }),
    ]);
    queue.value = [...pending.content, ...dispensing.content];
  } catch {
    // 失败弹错归响应拦截器；驻留旧队列
  } finally {
    loading.value = false;
  }
}

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

/** 配药：逐码非空前置（无码不结），出网后回显刷新。 */
async function onPick(): Promise<void> {
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
  try {
    await pickDispense(sheet.dispenseNo ?? '', { items });
    void ElMessage.success('配药锁定完成');
    await onSelect({ rxNo: sheet.rxNo });
  } catch {
    // 失败弹错归响应拦截器；录入驻留供补码重试
  }
}

/** 核对：按钮启停仅辅助，同人双签由后端拒。 */
async function onVerify(): Promise<void> {
  const sheet = dispense.value;
  if (!sheet) return;
  try {
    await verifyDispense(sheet.dispenseNo ?? '');
    void ElMessage.success('核对通过');
    await onSelect({ rxNo: sheet.rxNo });
  } catch {
    // 失败弹错归响应拦截器
  }
}

/** 发药签名：确认弹框后出网（终笔，费用占用生效）；取消驻留不重刷。 */
async function onIssue(): Promise<void> {
  const sheet = dispense.value;
  if (!sheet) return;
  try {
    await ElMessageBox.confirm('发药签名后药品出库且不可逆，确认发药？', '发药签名');
  } catch {
    // 用户取消：发药单驻留，可再次点击发药
    return;
  }
  try {
    await issueDispense(sheet.dispenseNo ?? '');
    void ElMessage.success('发药完成');
    dispense.value = null;
    await loadQueue();
  } catch {
    // 失败弹错归响应拦截器；单据驻留供重试
  }
}

onMounted(loadQueue);
</script>

<template>
  <div class="dispense-workbench">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card>
          <template #header>工作台队列（待发/调剂中）</template>
          <el-table :data="queue" v-loading="loading" highlight-current-row @row-click="onSelect">
            <el-table-column prop="rxNo" label="处方号" min-width="180" />
            <el-table-column prop="patientId" label="患者" min-width="150" />
            <el-table-column prop="visitId" label="就诊号" min-width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="14" v-if="dispense">
        <el-card>
          <template #header>发药单 {{ dispense.dispenseNo }}</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="调配人">{{ dispense.picker || '—' }}</el-descriptions-item>
            <el-descriptions-item label="核对人">{{
              dispense.verifier || '—'
            }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="dispense.items ?? []" class="dispense-workbench-items">
            <el-table-column prop="itemCode" label="项目" min-width="110" />
            <el-table-column prop="requestedQuantity" label="应发" width="90" />
            <el-table-column prop="batchNo" label="批次" min-width="130" />
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
            <el-button type="primary" :disabled="dispense.status !== 'CREATED'" @click="onPick"
              >配药</el-button
            >
            <el-button
              type="warning"
              :disabled="dispense.status !== 'PICKING' || isPicker()"
              @click="onVerify"
              >核对</el-button
            >
            <el-button
              type="success"
              :disabled="dispense.status !== 'PICKED' || isPicker()"
              @click="onIssue"
              >发药签名</el-button
            >
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.dispense-workbench-items {
  margin-top: 12px;
}

.dispense-workbench-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
