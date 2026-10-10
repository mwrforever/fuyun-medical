<script setup lang="ts">
// 一日清单页（FU-M13-03 患者费用公开 · 暖纸卷宗换脸重排，逐页蓝图 P09）：就诊号+日期查询 →
// 明细表 + 大类汇总表 + 三分区合计。后端三层勾稽（Σ明细=Σ大类=合计）已强校验，前端仅复算展示
// 作 UI 佐证（sumFen 为 BigInt 展示级校验和，非业务计价——业务金额零运算红线不破，web A.3-6）；
// 金额 string 承载展示。
// 构图（蓝图 P09「换脸不换业务」）：门牌页首（衬线标题 + 签认人·时刻批注行）→ 检索卡
// （.fuy-filter）→ 结果卡 grid 双列（左=费用明细 2fr；右=大类汇总 1fr + 三分区合计条同列
// 纵叠）——大类汇总从纵叠第三段升右列与合计条相邻，勾稽三方视线汇聚（Σ大类紧贴合计）；
// 未查询引导空态换 .fuy-empty 脸、首查骨架分支保留。数据契约零变动：dailyList 出网参数与
// 勾稽计算重排前逐字一致。
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import { dailyList } from '@/api/billing';
import type { DailyListVO } from '@/api/billing';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { fenToYuanDisplay } from '@/utils/money';

// 门牌批注行「谁」：会话身份单源（与顶栏用户区同源消费，空值 — 占位零伪数据）
const auth = useAuthStore();

/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/**
 * 当日批注行时刻标签（YYYY-MM-DD 周X）：与首页门牌同语法的病历页眉日期批注，
 * 纯本地时钟零出网。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 就诊号输入 */
const visitId = ref('');
/** 清单日期（el-date-picker value-format 直出 YYYY-MM-DD 字符串，与后端 LocalDate 契约同源；
 * 初始 null=未选（组件 modelValue 契约），选定后恒为字符串） */
const date = ref<string | null>(null);
/** 清单结果（明细+大类+合计三层） */
const result = ref<DailyListVO | null>(null);

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

/** 查询清单任务体：出网与结果赋值承载。loading 骨架经 useAsyncTask 收拢（EX-42 范式
 * 迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧清单）。 */
const { loading, run: loadDailyList } = useAsyncTask(async (visitNo: string, day: string) => {
  result.value = await dailyList(visitNo, day);
});

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
  await loadDailyList(visitId.value.trim(), day);
}
</script>

<template>
  <section class="fuy-page">
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 签认人·时刻批注行 + 2px 墨规收底（脸样式归全局
         .fuy-page-head 族，本页零私有标题样式）；原 el-card #header「一日清单」升格于此 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">一日清单</h1>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <!-- 检索卡（契约 ⑧.3）：就诊号 + 日期 + 查询墨实底钮右挂（.fuy-filter-actions）；原
         .fuy-toolbar 工具条升独立筛选卡语义位；日期弹层显式挂 fuy-snap-popper（总则 4） -->
    <section class="fuy-card">
      <div class="fuy-card-body fuy-filter">
        <label for="daily-list-visit">就诊号</label>
        <el-input
          id="daily-list-visit"
          v-model="visitId"
          placeholder="就诊号"
          class="daily-list-input"
          clearable
        />
        <label for="daily-list-date">清单日期</label>
        <el-date-picker
          id="daily-list-date"
          v-model="date"
          type="date"
          placeholder="清单日期"
          value-format="YYYY-MM-DD"
          popper-class="fuy-snap-popper"
        />
        <div class="fuy-filter-actions">
          <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
        </div>
      </div>
    </section>

    <!-- 结果卡（契约 ⑧.2 卷宗卡 + ⑧.4 密排表）：fuy-dense 挂卡容器——密度规则均为后代
         选择器 .fuy-dense .el-table，表格数字列等宽经 class-name 全局类承载 -->
    <section class="fuy-card fuy-dense">
      <div class="fuy-card-body">
        <!-- 结果区显隐 200ms 淡入（§6.7，appear 供首次挂载即播，既有零变动） -->
        <Transition name="fuy-content-fade" appear>
          <div v-if="result" class="daily-list-result">
            <!-- 左列=费用明细表（蓝图 P09.3 grid 2fr）：列序/数字列 class-name 零变动 -->
            <div class="daily-list-detail">
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
            </div>

            <!-- 右列=大类汇总表（1fr）+ 三分区合计条同列纵叠：勾稽三方（Σ明细/Σ大类/合计）
                 视线汇聚，Σ大类紧贴合计（蓝图 P09 禁照抄点——禁回落纵叠第三段） -->
            <div class="daily-list-summary">
              <h4 class="fuy-section-title">大类汇总</h4>
              <el-table :data="result.categories ?? []" class="daily-list-categories">
                <el-table-column prop="feeCategory" label="大类" min-width="140" />
                <el-table-column label="金额（元）" width="120" align="right" class-name="fuy-num">
                  <template #default="{ row }">{{ fenToYuanDisplay(row.amount ?? '0') }}</template>
                </el-table-column>
              </el-table>

              <!-- 三分区合计：Σ明细 / Σ大类 / 合计 并列展示，勾稽一致才露绿标（第三层校验
                   UI 佐证）；合计条 §6.1 rise 入场（fuy-stagger 单子容器 + inline index
                   1=40ms delay，压轴于结果区 200ms 淡入完成——delay 经自定义属性继承至子
                   动画元素）；slot margin-top:auto 收右列底（蓝图 P09.3） -->
              <div class="fuy-stagger daily-list-strip-slot" :style="{ '--fuy-stagger-index': 1 }">
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
          </div>
        </Transition>
        <!-- 首查加载走骨架、未查询给引导空态（§4.4 二分：首屏骨架/结果区刷新 v-loading）；
             与下方引导空态构成同层 v-if/v-else-if 分支链（非加载且无结果时呈现），result
             就绪后由 Transition 内结果区承接呈现 -->
        <el-skeleton v-if="loading && result === null" :rows="4" animated />
        <!-- 未查询引导空态（契约 ⑥ .fuy-empty 脸禁纸箱插画）：主句合「尚无」语法，说明句
             保留既有指路文案给下一步 -->
        <div v-else-if="!result" class="fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">尚无清单结果</p>
          <p class="fuy-empty-hint">输入就诊号与清单日期后点击查询，出具当日费用明细。</p>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：门牌页首/筛选脸/卷宗卡/空态脸/合计条样式全部由
   element-plus.css 全局挂类承载（.fuy-page-head/.fuy-filter/.fuy-card/.fuy-empty/
   .fuy-total-strip 既有类零改动），本块只留筛选输入宽/双列勾稽布局/两表 CLS 锁/合计字级 */

.daily-list-input {
  max-width: 240px;
}

/* 结果区双列（蓝图 P09.3 grid 2fr/1fr gap 16）：左明细右汇总，勾稽视线汇聚；
   列 min-width:0 防表格内容撑破 1fr 轨道 */
.daily-list-result {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--fuy-space-4);
}

.daily-list-detail {
  min-width: 0;
}

/* 右列纵叠：汇总表上、合计条下；合计条 margin-top:auto 收右列底（与左列底边对齐收口） */
.daily-list-summary {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.daily-list-strip-slot {
  margin-top: auto;
}

/* 合计条右列窄轨收口（页内 scoped，全局 .fuy-total-strip 类零改动）：wrap 以整项为单位
   换行 + 各分区文字禁项内折行——真机 1440 实测 1fr 轨道（约 370px）容不下三分区+勾稽
   标签单行排布，无此守卫时「230.00 元」在 span 内折行出现「元」孤行，勾稽佐证条可读性受损 */
.daily-list-strip-slot .fuy-total-strip {
  flex-wrap: wrap;
  row-gap: var(--fuy-space-2);
}

.daily-list-subtotal,
.daily-list-grand {
  white-space: nowrap;
}

/* 双列列首小节题贴卡顶（.fuy-section-title 全局上边距为纵叠流设计，双列首题冗余） */
.daily-list-detail .fuy-section-title,
.daily-list-summary .fuy-section-title {
  margin-top: 0;
}

/* 明细表容器 min-height 锁定加载/空态切换零塌陷（§7.1 CLS，240 口径既有） */
.daily-list-items {
  min-height: 240px;
}

/* 大类汇总表同口径 min-height 锁（蓝图 P09.6 双列表格各自锁）；原纵叠期 max-width:420px
   随升右列撤销，宽度归 1fr 轨道 */
.daily-list-categories {
  min-height: 240px;
}

/* Σ明细/Σ大类：次要 14px；合计：emphasis 20px 等宽数字（§9.4-⑥ 三分区强调口径，既有零变动） */
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
