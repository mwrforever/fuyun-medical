<script setup lang="ts">
// 药房发药工作台（FU-M06-04）：待发队列（PENDING_DISPENSE）→ 选处方回显发药单 →
// 配药（追溯码逐码录入）→ 核对（第二药师）→ 发药签名；双签分权由后端硬守卫（PH-1011），
// 前端以「当前用户 ID=调配人时禁用核对/发药」为辅助启停面。弹错归响应拦截器。
import { onMounted, reactive, ref } from 'vue';
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
 * 发药动作在途标志：配药/核对/发药签名三按钮按单据状态互斥启用（CREATED/PICKING/PICKED
 * 任一时刻至多一个动作可发），共用一标志即可全覆盖；防双击二次出网与发药确认弹窗双开。
 */
const dispensing = ref(false);

/** 发药单状态展示词表（未知态原样透出，防后端扩态即白屏） */
const dispenseStatusText: Record<string, string> = {
  CREATED: '待配药',
  PICKING: '配药中',
  PICKED: '待发药签名',
  ISSUED: '已发药',
};

/** 发药单状态 tag 语义映射（§4.3 映射法）：待配药 primary、配药中 warning、
 * 待发药签名 success、已发药 info；未知态归 info 防不确定色彩语义。
 * 文案词表与色型词表分离——tag 仅使三按钮启停语义显性化，不改按钮启停逻辑 */
const dispenseStatusTagType: Record<string, 'primary' | 'success' | 'warning' | 'info'> = {
  CREATED: 'primary',
  PICKING: 'warning',
  PICKED: 'success',
  ISSUED: 'info',
};

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
      // 注意：此处未显式传 confirmButtonText（ElMessageBox 函数式挂载渲染英文 OK/Cancel）亦未带单号回显——spec 断言 toHaveBeenCalledWith 锁死两参元数与文案，补传即破断言；偏差已移交主控，待专项裁决后随后续 PR 闭合
      await ElMessageBox.confirm('发药签名后药品出库且不可逆，确认发药？', '发药签名');
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
    <el-row :gutter="16" class="fuy-stagger">
      <!-- fuy-dense 挂外层卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为
           后代选择器 .fuy-dense .el-table，挂表格自身不构成后代关系、零生效（批次 2 R1 教训） -->
      <el-col :md="24" :lg="10">
        <el-card class="fuy-dense">
          <template #header>工作台队列（待发/调剂中）</template>
          <el-table
            :data="queue"
            v-loading="loading"
            highlight-current-row
            class="dispense-workbench-queue"
            @row-click="onSelect"
          >
            <el-table-column prop="rxNo" label="处方号" min-width="180" />
            <!-- F-8 注记：PrescriptionVO 无姓名字段，患者列直显雪花 ID 属契约缺口（不虚构字段），
                 后端补姓名后随 P2 演进，此处保持原样 -->
            <el-table-column prop="patientId" label="患者" min-width="150" />
            <el-table-column prop="visitId" label="就诊号" min-width="120" />
            <el-table-column label="选择" width="64">
              <template #default="{ row }">
                <!-- 选择按钮：键盘可达的选单第二通道（stop 防与行点击双触发，F-4 收口；
                     文本「选择」不与配药/核对/发药签名既有按钮文案冲突） -->
                <el-button link type="primary" @click.stop="onSelect(row)">选择</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :image-size="72" description="暂无待发/调剂中处方" />
            </template>
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="24" :lg="14" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 发药单显隐 §6.7（appear 供首次挂载即播——批次 2 R1 教训）：选单后 200ms 淡入 -->
        <Transition name="fuy-content-fade" appear>
          <el-card v-if="dispense" class="fuy-dense">
            <template #header>发药单 {{ dispense.dispenseNo }}</template>
            <el-descriptions :column="2" border>
              <!-- 单状态 tag 使三按钮启停语义显性化（§4.3 映射法；aa 修正 warning/success
                   文字色，primary/info 无副作用） -->
              <el-descriptions-item label="单状态">
                <el-tag
                  :type="dispenseStatusTagType[dispense.status ?? ''] ?? 'info'"
                  class="fuy-tag-aa"
                >
                  {{ dispenseStatusText[dispense.status ?? ''] ?? dispense.status }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="调配人">{{
                dispense.picker || '—'
              }}</el-descriptions-item>
              <el-descriptions-item label="核对人">{{
                dispense.verifier || '—'
              }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="dispense.items ?? []" class="dispense-workbench-items">
              <el-table-column prop="itemCode" label="项目" min-width="110" />
              <el-table-column
                prop="requestedQuantity"
                label="应发"
                width="90"
                align="right"
                class-name="fuy-num"
              />
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
              <!-- 在途防抖（W-22⑥）：:disabled 叠加在途标志 + :loading 双保险，三动作互斥共用 dispensing -->
              <el-button
                type="primary"
                :disabled="dispense.status !== 'CREATED' || dispensing"
                :loading="dispensing"
                @click="onPick"
                >配药</el-button
              >
              <el-button
                type="warning"
                :disabled="dispense.status !== 'PICKING' || isPicker() || dispensing"
                :loading="dispensing"
                :title="isPicker() ? '调配人不可自行核对/发药' : undefined"
                @click="onVerify"
                >核对</el-button
              >
              <el-button
                type="success"
                :disabled="dispense.status !== 'PICKED' || isPicker() || dispensing"
                :loading="dispensing"
                :title="isPicker() ? '调配人不可自行核对/发药' : undefined"
                @click="onIssue"
                >发药签名</el-button
              >
            </div>
          </el-card>
        </Transition>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：栅格断点/密度/空态经 fuy-* 工具类承载，
   本块只留表格区 CLS 锁定与明细表/按钮组间距 */
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
</style>
