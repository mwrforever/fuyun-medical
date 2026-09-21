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
  <div class="fuy-page">
    <!-- fuy-dense 挂外层卡容器（§9.4 通用落点）：表格 size="small" 移除，fuy-dense 唯一
         密度通道（§9.9-2） -->
    <el-card class="fuy-dense">
      <template #header>一日清单</template>
      <div class="fuy-toolbar">
        <el-input v-model="visitId" placeholder="就诊号" class="daily-list-input" clearable />
        <el-date-picker
          v-model="date"
          type="date"
          placeholder="清单日期"
          value-format="YYYY-MM-DD"
        />
        <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
      </div>

      <!-- 结果区显隐 200ms 淡入（§6.7，appear 供首次挂载即播——批次 2 R1 同款） -->
      <Transition name="fuy-content-fade" appear>
        <div v-if="result">
          <h4 class="fuy-section-title">费用明细</h4>
          <el-table v-loading="loading" :data="result.items ?? []" class="daily-list-items">
            <el-table-column prop="itemNameSnapshot" label="项目" min-width="160" />
            <el-table-column label="单价（元）" width="110" align="right" class-name="fuy-num">
              <template #default="{ row }">
                {{ fenToYuanDisplay(row.unitPriceSnapshot ?? '0') }}
              </template>
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
          </el-table>

          <h4 class="fuy-section-title">大类汇总</h4>
          <el-table :data="result.categories ?? []" class="daily-list-categories">
            <el-table-column prop="feeCategory" label="大类" min-width="140" />
            <el-table-column label="金额（元）" width="120" align="right" class-name="fuy-num">
              <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
            </el-table-column>
          </el-table>

          <!-- 三分区合计：Σ明细 / Σ大类 / 合计 并列展示，勾稽一致才露绿标（第三层校验 UI 佐证）；
               合计条 §6.1 rise 入场（fuy-stagger 单子容器 + inline index 1=40ms delay，
               压轴于结果区 200ms 淡入完成——delay 经自定义属性继承至子动画元素） -->
          <div class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
            <div class="fuy-total-strip">
              <span class="daily-list-subtotal">
                Σ明细 {{ fenToYuanDisplay(itemsSumFen) }} 元
              </span>
              <span class="daily-list-subtotal">
                Σ大类 {{ fenToYuanDisplay(categoriesSumFen) }} 元
              </span>
              <span class="daily-list-grand fuy-num">
                合计 {{ fenToYuanDisplay(result.totalAmount ?? '0') }} 元
              </span>
              <el-tag v-if="reconciled" type="success" class="fuy-tag-aa">已核对</el-tag>
              <el-tag v-else type="danger" class="fuy-tag-aa">合计不一致，请核对</el-tag>
            </div>
          </div>
        </div>
      </Transition>
      <!-- 首查加载走骨架、未查询给引导空态（§4.4 二分：首屏骨架/结果区刷新 v-loading；
           批次 3 移交打磨项——原首查窗口 el-empty 与按钮转圈并存） -->
      <el-skeleton v-if="loading && result === null" :rows="4" animated />
      <!-- 未查询引导空态（F-6）：业务口径指路，非「暂无数据」（§4.4）；
           显式 v-if——前驱 v-if 在 Transition 内，v-else 链被组件隔断不合法 -->
      <el-empty
        v-else-if="!result"
        :image-size="72"
        description="输入就诊号与清单日期查询费用明细"
      />
    </el-card>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：工具条/小节题/合计条容器已收编 .fuy-toolbar/.fuy-section-title/
   .fuy-total-strip（§9.2.2），本块只留 input 宽度/明细表 CLS 高度/大类表宽/合计字级 */
.daily-list-input {
  max-width: 240px;
}

/* 明细表容器 min-height 锁定加载/空态切换零塌陷（§7.1 CLS） */
.daily-list-items {
  min-height: 240px;
}

.daily-list-categories {
  max-width: 420px;
}

/* Σ明细/Σ大类：次要 14px；合计：emphasis 20px 等宽数字（§9.4-⑥ 三分区强调口径） */
.daily-list-subtotal {
  font-size: var(--fuy-font-size-md);
  color: var(--fuy-color-text-secondary);
}

.daily-list-grand {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}
</style>
