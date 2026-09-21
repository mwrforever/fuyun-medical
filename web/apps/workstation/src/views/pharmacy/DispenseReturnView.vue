<script setup lang="ts">
// 退药受理页（FU-M06-05）：处方号检索发药单 → 逐行退药数与追溯码录入 →
// 模式单选（ISSUED_RETURN 发药后实物退 / DISPENSING_CANCEL 发药中明细退场）→
// 提交 createDispenseReturn；实物退追溯码逐码必填由前端前置拦截（无码不结），
// 数量/批次核验归后端（弹错归响应拦截器）。检索入口为处方号（listDispenses 以 rxNo 检回）。
import { reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import { createDispenseReturn, listDispenses } from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';

/** 退药编辑行（与 DispenseItemVO 展示字段 + ReturnLine 录入字段合并，行编辑同构） */
interface ReturnLineRow {
  /** 发药明细 id（录入模型键，string 承载雪花 ID） */
  itemId: string;
  /** 处方明细 id（ReturnLine.prescriptionItemId 出网值） */
  prescriptionItemId: string;
  /** 项目编码（回显） */
  itemCode: string;
  /** 应发数量（回显，string 原样） */
  requestedQuantity: string;
  /** 批次号（回显） */
  batchNo: string;
  /** 退药数量（DECIMAL string 承载，默认 1，操作者按实物调整） */
  returnQuantity: string;
  /** 追溯码录入（逗号/空格分隔，实物退逐码必填） */
  traceCodes: string;
}

/** 检索处方号（发药单经处方号检回，一处方一张活动单） */
const rxNo = ref('');
/** 检回的发药单（null=未检索或无单） */
const dispense = ref<DispenseVO | null>(null);
/** 是否已完成过检索（区分「未检索」与「检索无单」两态：空态提示仅对后者呈现） */
const searched = ref(false);
/** 退药行编辑模型（检索回显时重建，防上一单残留） */
const rows = reactive<ReturnLineRow[]>([]);
const loading = ref(false);
/** 提交在途标志（防双击二次出网，根除重复退药受理） */
const submitting = ref(false);

/** 受理模式：ISSUED_RETURN 发药后实物退 / DISPENSING_CANCEL 发药中明细退场 */
const mode = ref<'ISSUED_RETURN' | 'DISPENSING_CANCEL'>('ISSUED_RETURN');

/** 发药单状态词表（DispenseVO.status 契约为 string 无生成物枚举；词表与发药工作台同源，
 * 未知态原样回显防后端扩态白屏——F-8 枚举直出修复口径） */
const DISPENSE_STATUS_TEXT: Record<string, string> = {
  CREATED: '待配药',
  PICKING: '配药中',
  PICKED: '待发药签名',
  ISSUED: '已发药',
};

/** 检索发药单：处方号空前置拦截不出网；检回后重建逐行录入（退药数默认 1）。 */
async function handleSearch(): Promise<void> {
  if (rxNo.value.trim() === '') {
    void ElMessage.warning('请输入处方号');
    return;
  }
  loading.value = true;
  try {
    const list = await listDispenses({ rxNo: rxNo.value.trim() });
    // 检索已成功返回（失败驻留旧单时保持原 searched 态，防误显「无单」空态）
    searched.value = true;
    dispense.value = list.length > 0 ? list[0] : null;
    rows.splice(
      0,
      rows.length,
      ...(dispense.value?.items ?? []).map((i) => ({
        itemId: i.id ?? '',
        prescriptionItemId: String(i.prescriptionItemId),
        itemCode: i.itemCode ?? '',
        requestedQuantity: i.requestedQuantity ?? '',
        batchNo: i.batchNo ?? '',
        returnQuantity: '1',
        traceCodes: '',
      })),
    );
    if (!dispense.value) {
      void ElMessage.warning('该处方无发药单');
    }
  } catch {
    // 失败弹错归响应拦截器；驻留旧单
  } finally {
    loading.value = false;
  }
}

/**
 * 提交退药受理：退药数逐行必填；实物退逐码非空前置（无码不结，不出网）。
 * 入口在途早退守卫：重渲染前到达的第二击直接拦截，根除退药受理重复出网。
 */
async function submitReturn(): Promise<void> {
  if (submitting.value) {
    return;
  }
  const sheet = dispense.value;
  if (!sheet) {
    void ElMessage.warning('请先检索发药单');
    return;
  }
  if (rows.some((r) => r.returnQuantity.trim() === '')) {
    void ElMessage.warning('逐行退药数量必填');
    return;
  }
  // 实物退=实物核验语义：追溯码逐码必填，前端前置拦截不出网（发药中退场可空）
  if (mode.value === 'ISSUED_RETURN' && rows.some((r) => r.traceCodes.trim() === '')) {
    void ElMessage.warning('实物退须逐盒录入追溯码（无码不结）');
    return;
  }
  // 置位在途（finally 必复位）：锁定提交出网窗口，窗口内重复触发零出网
  submitting.value = true;
  try {
    await createDispenseReturn({
      dispenseNo: sheet.dispenseNo ?? '',
      mode: mode.value,
      items: rows.map((r) => ({
        prescriptionItemId: r.prescriptionItemId,
        returnQuantity: r.returnQuantity.trim(),
        traceCodes: r.traceCodes.split(/[，,\s]+/).filter(Boolean),
      })),
    });
    void ElMessage.success('退药受理完成');
  } catch {
    // 失败弹错归响应拦截器；录入驻留供修正重试
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <!-- fuy-dense 挂外层卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为后代选择器
       .fuy-dense .el-table，挂表格自身不构成后代关系、零生效（批次 2 R1 教训）；
       表格 size="small" 移除——fuy-dense 唯一密度通道（§9.9-2） -->
  <div class="fuy-page">
    <el-card class="fuy-dense">
      <template #header>退药受理</template>
      <div class="fuy-toolbar">
        <el-input
          v-model="rxNo"
          placeholder="处方号"
          class="dispense-return-input"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button :loading="loading" @click="handleSearch">检索发药单</el-button>
      </div>

      <!-- 检索无单空态（§9.4-⑨）：warning 弹错保留，空态面以业务口径补齐；
           searched 门控防「未检索即显无单」误导（批次 2 检索页同款两态区分） -->
      <el-empty v-if="!dispense && searched" :image-size="72" description="该处方无发药单" />

      <!-- 结果区单块显隐 §6.7（appear 供首次挂载即播——批次 2 R1 教训）：检索回显 200ms 淡入；
           内容收拢单 div 承载 Transition 单子元素要求（同条件同显隐，语义等价） -->
      <Transition name="fuy-content-fade" appear>
        <div v-if="dispense">
          <!-- 单据摘要显式小节题（:title 非常规用法收编 .fuy-section-title，§9.4-⑨）；
               单号回显下沉 descriptions 项，去 title 后业务锚点零丢失 -->
          <h4 class="fuy-section-title">发药单信息</h4>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="发药单号">{{
              dispense.dispenseNo ?? '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="单状态">{{
              DISPENSE_STATUS_TEXT[dispense.status ?? ''] ?? dispense.status ?? '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="处方号">{{ dispense.rxNo ?? '—' }}</el-descriptions-item>
          </el-descriptions>

          <el-table :data="rows" class="dispense-return-items">
            <el-table-column prop="itemCode" label="项目" min-width="110" />
            <el-table-column
              prop="requestedQuantity"
              label="应发"
              width="90"
              align="right"
              class-name="fuy-num"
            />
            <el-table-column prop="batchNo" label="批次" min-width="130" />
            <el-table-column label="退药数量" width="130">
              <template #default="{ row }">
                <!-- string 契约承载 DECIMAL 数量（禁 number 转换，W-22⑦ 校验形态归 fix PR）；
                     inputmode 引导数字键盘，不改变 v-model string 形态 -->
                <el-input v-model="row.returnQuantity" inputmode="numeric" placeholder="退药数" />
              </template>
            </el-table-column>
            <el-table-column label="追溯码录入" min-width="220">
              <template #default="{ row }">
                <el-input
                  v-model="row.traceCodes"
                  :placeholder="mode === 'ISSUED_RETURN' ? '逐盒扫码，逗号分隔' : '实物退才必填'"
                />
              </template>
            </el-table-column>
          </el-table>

          <div class="dispense-return-mode">
            <span class="dispense-return-mode-label">受理模式：</span>
            <el-radio-group v-model="mode">
              <el-radio value="ISSUED_RETURN">发药后实物退</el-radio>
              <el-radio value="DISPENSING_CANCEL">发药中明细退场</el-radio>
            </el-radio-group>
          </div>
          <div class="dispense-return-actions">
            <!-- 在途防抖（W-22⑥）：:disabled 叠加在途标志 + :loading 双保险，根除双击重复出网 -->
            <el-button
              type="primary"
              :disabled="submitting"
              :loading="submitting"
              @click="submitReturn"
              >提交退药</el-button
            >
          </div>
        </div>
      </Transition>
    </el-card>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：工具条已收编 .fuy-toolbar（§9.2.2），卡宽随 .fuy-page
   全宽（列表 1080 上限撤销），本块只留 input 宽度/行编辑表与模式区/按钮组间距 */
.dispense-return-input {
  max-width: 260px;
}

.dispense-return-items {
  margin-top: 12px;
}

.dispense-return-mode {
  display: flex;
  align-items: center;
  margin-top: 12px;
}

.dispense-return-mode-label {
  font-size: var(--fuy-font-size-md);
}

.dispense-return-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
