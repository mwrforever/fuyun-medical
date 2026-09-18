<script setup lang="ts">
// 一日清单页（FU-M13-03 患者费用公开）：就诊号+日期查询 → 明细表 + 大类汇总表 + 三分区合计。
// 后端三层勾稽（Σ明细=Σ大类=合计）已强校验，前端仅复算展示作 UI 佐证（sumFen 为 BigInt
// 展示级校验和，非业务计价——业务金额零运算红线不破，web A.3-6）；金额 string 承载展示。
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import { dailyList } from '@/api/billing';
import type { DailyListVO } from '@/api/billing';
import { fenToYuanDisplay } from '@/utils/money';

/** 就诊号输入 */
const visitId = ref('');
/** 清单日期（el-date-picker value-format 直出 YYYY-MM-DD 字符串，与后端 LocalDate 契约同源；
 * 初始 null=未选（组件 modelValue 契约），选定后恒为字符串） */
const date = ref<string | null>(null);
/** 清单结果（明细+大类+合计三层） */
const result = ref<DailyListVO | null>(null);
const loading = ref(false);

/**
 * 分值守列求和（仅用于勾稽佐证展示：后端已强校验三层一致，此处复算比对，
 * BigInt 字符串运算零浮点；非法值按 0 参与比对，真实异常由后端勾稽先行拒绝）。
 *
 * @param values 分（string）值集合，可空
 * @return 合计分（string）
 */
function sumFen(values: Array<string | undefined> | undefined): string {
  const total = (values ?? []).reduce<bigint>(
    (acc, v) => (v !== undefined && /^-?\d+$/.test(v) ? acc + BigInt(v) : acc),
    0n,
  );
  return total.toString();
}

/** Σ明细（分）：逐费用行金额复算 */
const itemsSumFen = computed(() => sumFen(result.value?.items?.map((item) => item.amount)));
/** Σ大类（分）：大类汇总行金额复算 */
const categoriesSumFen = computed(() => sumFen(result.value?.categories?.map((c) => c.amount)));
/** 三层一致判定（勾稽绿标依据；后端强校验下的前端 UI 佐证） */
const reconciled = computed(
  () =>
    result.value !== null &&
    itemsSumFen.value === categoriesSumFen.value &&
    categoriesSumFen.value === (result.value.totalAmount ?? ''),
);

/** 查询清单：就诊号与日期任一为空前置拦截不出网 */
async function handleQuery(): Promise<void> {
  if (visitId.value.trim() === '') {
    void ElMessage.warning('请输入就诊号');
    return;
  }
  const day = date.value;
  if (day === null || day === '') {
    void ElMessage.warning('请选择清单日期');
    return;
  }
  loading.value = true;
  try {
    result.value = await dailyList(visitId.value.trim(), day);
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <el-card class="daily-list">
    <template #header>一日清单</template>
    <div class="daily-list-bar">
      <el-input v-model="visitId" placeholder="就诊号" class="daily-list-input" clearable />
      <el-date-picker v-model="date" type="date" placeholder="清单日期" value-format="YYYY-MM-DD" />
      <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
    </div>

    <template v-if="result">
      <h4 class="daily-list-section">费用明细</h4>
      <el-table v-loading="loading" :data="result.items ?? []" size="small">
        <el-table-column prop="itemNameSnapshot" label="项目" min-width="160" />
        <el-table-column label="单价（元）" width="110">
          <template #default="{ row }">
            {{ fenToYuanDisplay(row.unitPriceSnapshot ?? '0') }}
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column label="金额（元）" width="110">
          <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
        </el-table-column>
      </el-table>

      <h4 class="daily-list-section">大类汇总</h4>
      <el-table :data="result.categories ?? []" size="small" class="daily-list-categories">
        <el-table-column prop="feeCategory" label="大类" min-width="140" />
        <el-table-column label="金额（元）" width="120">
          <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
        </el-table-column>
      </el-table>

      <!-- 三分区合计：Σ明细 / Σ大类 / 合计 并列展示，勾稽一致才露绿标（第三层校验 UI 佐证） -->
      <div class="daily-list-total">
        <span>Σ明细 {{ fenToYuanDisplay(itemsSumFen) }} 元</span>
        <span>Σ大类 {{ fenToYuanDisplay(categoriesSumFen) }} 元</span>
        <span>合计 {{ fenToYuanDisplay(result.totalAmount ?? '0') }} 元</span>
        <el-tag v-if="reconciled" type="success">已核对</el-tag>
        <el-tag v-else type="danger">合计不一致，请核对</el-tag>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.daily-list {
  max-width: 1080px;
}

.daily-list-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.daily-list-input {
  max-width: 240px;
}

.daily-list-section {
  margin: 16px 0 8px;
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.daily-list-categories {
  max-width: 420px;
}

.daily-list-total {
  display: flex;
  gap: 24px;
  align-items: center;
  margin-top: 12px;
  font-size: 14px;
}
</style>
