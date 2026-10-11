<script setup lang="ts">
// 退药受理页（FU-M06-05 · 暖纸卷宗 P12 蓝图重排）：处方号检索发药单 → 逐行退药数与追溯码录入 →
// 提交 createDispenseReturn；实物退追溯码逐码必填由前端前置拦截（无码不结），
// 数量/批次核验归后端（弹错归响应拦截器）。检索入口为处方号（listDispenses 以 rxNo 检回）。
// 构图（蓝图 P12「换脸不换业务」）：门牌页首（衬线标题 + 签认人·时刻批注行）→ 检索卡
// .fuy-filter（处方号 + 检索墨实底钮｜受理模式 radio 前置同排居末位——模式决定追溯码必填性，
// 先定模式再录入）→ 结果卡（摘要 descriptions + 退药行编辑表 + 提交钮右挂）；空态两态
// （未检索引导 / 检索无单）换 .fuy-empty 脸。受理模式从明细表之后升格到检索区末位（禁照抄点）；
// 检索/出网/追溯码校验逻辑零改动——searched 门控两态、实物退无码不结、string 数量契约、
// 在途防抖（W-22⑥）与 PR-4F 元素码均维持重排前口径。页根不挂 .fuy-stagger（契约 ⑦.4：
// 路由进场过渡归 MainLayout，防双重进场节奏）。
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import { createDispenseReturn, listDispenses } from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { dispenseStatusText } from '@/utils/dispenseDisplay';

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
/** 提交在途标志（防双击二次出网，根除重复退药受理） */
const submitting = ref(false);

/** 受理模式：ISSUED_RETURN 发药后实物退 / DISPENSING_CANCEL 发药中明细退场 */
const mode = ref<'ISSUED_RETURN' | 'DISPENSING_CANCEL'>('ISSUED_RETURN');

/** 检索发药单任务体：检索成功后的 searched 置位、发药单回显与行编辑重建均在此成功
 * 路径承载。loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——
 * 失败弹错归响应拦截器、驻留旧单且 searched 保持原态防误显「无单」空态）。 */
const { loading, run: searchDispense } = useAsyncTask(async (rxNoParam: string) => {
  const list = await listDispenses({ rxNo: rxNoParam });
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
});

/** 检索发药单：处方号空前置拦截不出网；检回后重建逐行录入（退药数默认 1）。 */
async function handleSearch(): Promise<void> {
  if (rxNo.value.trim() === '') {
    void ElMessage.warning('请输入处方号');
    return;
  }
  await searchDispense(rxNo.value.trim());
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
  <!-- 暖纸卷宗 P12 重排：页面纵向序=门牌页首 → 检索卡 → 结果卡（契约 ⑧/总则 6） -->
  <div class="fuy-page">
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 签认人·时刻批注行 + 2px 墨规收底（脸样式归全局
         .fuy-page-head 族，本页零私有标题样式）；原 el-card #header「退药受理」升格于此 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">退药受理</h1>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <!-- 检索卡（蓝图 P12.2 + 药房域「先定模式再录入」）：筛选脸 .fuy-filter——处方号输入 +
         检索墨实底钮｜受理模式 radio 前置同排右挂末位（模式决定追溯码必填性，是录入前置
         条件，从明细表之后升格至此——禁照抄点，禁回落表后旧版式） -->
    <section class="fuy-card">
      <div class="fuy-card-body fuy-filter">
        <label for="dispense-return-rx">处方号</label>
        <el-input
          id="dispense-return-rx"
          v-model="rxNo"
          placeholder="处方号"
          class="dispense-return-input"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :loading="loading" @click="handleSearch">检索发药单</el-button>
        <label class="dispense-return-mode-label">受理模式</label>
        <el-radio-group v-model="mode">
          <el-radio value="ISSUED_RETURN">发药后实物退</el-radio>
          <el-radio value="DISPENSING_CANCEL">发药中明细退场</el-radio>
        </el-radio-group>
      </div>
    </section>

    <!-- 结果卡（契约 ⑧.2 卷宗卡 + ⑧.4 密排表）：fuy-dense 挂卡容器（§9.4 通用落点「表格
         容器挂 fuy-dense」——密度规则为后代选择器 .fuy-dense .el-table，挂表格自身零生效，
         批次 2 R1 教训）；表格 size="small" 移除——fuy-dense 唯一密度通道（§9.9-2） -->
    <section class="fuy-card fuy-dense">
      <div class="fuy-card-body">
        <!-- 检索无单空态（契约 ⑥/总则 8）：warning 弹错保留，el-empty 纸箱插画换 .fuy-empty 脸；
             searched 门控防「未检索即显无单」误导（两态语义保留仅换脸），说明句说问题给恢复 -->
        <div v-if="!dispense && searched" class="fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">该处方无发药单</p>
          <p class="fuy-empty-hint">请核对处方号后重试；仅已发药或发药中的处方可办理退药受理。</p>
        </div>

        <!-- 未检索引导空态（两态之一）：给录入路径指引，门控与检索无单互斥 -->
        <div v-else-if="!dispense" class="fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">尚无受理单据</p>
          <p class="fuy-empty-hint">
            输入处方号后点击「检索发药单」，检回待退明细并录入退药数量与追溯码。
          </p>
        </div>

        <!-- 结果区单块显隐 §6.7（appear 供首次挂载即播——批次 2 R1 教训）：检索回显 200ms 淡入；
             内容收拢单 div 承载 Transition 单子元素要求（同条件同显隐，语义等价）；
             表单脸 .fuy-form 挂结果区（契约 ⑤#1-#4：行编辑录入承聚焦墨环+朱砂下划展开，
             全局后代选择器生效） -->
        <Transition name="fuy-content-fade" appear>
          <div v-if="dispense" class="fuy-form">
            <!-- 单据摘要显式小节题（.fuy-section-title 既有）；
                 单号/处方号回显下沉 descriptions 项，单据主号等宽（药房域级一致性） -->
            <h4 class="fuy-section-title">发药单信息</h4>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="发药单号">
                <span class="fuy-num">{{ dispense.dispenseNo ?? '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="单状态">{{
                dispenseStatusText(dispense.status) || '—'
              }}</el-descriptions-item>
              <el-descriptions-item label="处方号">
                <span class="fuy-num">{{ dispense.rxNo ?? '—' }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <el-table :data="rows" class="dispense-return-items">
              <!-- 项目编码/批次号=号码族列，与应发数字列同挂 .fuy-num 等宽（域级一致性，
                   与发药工作台明细表同律） -->
              <el-table-column prop="itemCode" label="项目" min-width="110" class-name="fuy-num" />
              <el-table-column
                prop="requestedQuantity"
                label="应发"
                width="90"
                align="right"
                class-name="fuy-num"
              />
              <el-table-column prop="batchNo" label="批次" min-width="130" class-name="fuy-num" />
              <el-table-column label="退药数量" width="130">
                <template #default="{ row }">
                  <!-- string 契约承载 DECIMAL 数量（禁 number 转换，W-22⑦ 校验形态归 fix PR）；
                       inputmode 引导数字键盘，不改变 v-model string 形态 -->
                  <el-input v-model="row.returnQuantity" inputmode="numeric" placeholder="退药数" />
                </template>
              </el-table-column>
              <el-table-column
                :label="mode === 'ISSUED_RETURN' ? '追溯码录入（必填）' : '追溯码录入（可空）'"
                min-width="220"
              >
                <template #default="{ row }">
                  <!-- 模式联动双通道（蓝图 P12.2 视觉因果「先定模式再录入」）：列头必填/可空
                       标识 + placeholder 引导同随模式翻转——必填性在录码前可见，不再靠
                       提交拦截事后告知（拦截语义零变动，实物退无码不结仍前置零出网） -->
                  <el-input
                    v-model="row.traceCodes"
                    :placeholder="mode === 'ISSUED_RETURN' ? '逐盒扫码，逗号分隔' : '实物退才必填'"
                  />
                </template>
              </el-table-column>
            </el-table>

            <div class="dispense-return-actions">
              <!-- 在途防抖（W-22⑥）：:disabled 叠加在途标志 + :loading 双保险，根除双击重复出网；
                   退药受理提交（PR-4F #21，PHARMACIST 绑定）v-perm 直挂——无码 DOM 移除（D-34），
                   与住院摆药页退药入口同码跨视图收口 -->
              <el-button
                v-perm="'pharmacy:dispense:btn:return'"
                type="primary"
                :disabled="submitting"
                :loading="submitting"
                @click="submitReturn"
                >提交退药</el-button
              >
            </div>
          </div>
        </Transition>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：门牌页首/筛选脸/卷宗卡/空态脸样式全部由 element-plus.css
   全局挂类承载（.fuy-page-head/.fuy-filter/.fuy-card/.fuy-empty 既有类零改动），本块只留
   筛选输入宽/模式组右挂/行编辑表 CLS 锁与提交区右挂 */
.dispense-return-input {
  max-width: 260px;
}

/* 受理模式组右挂检索区末位（蓝图 P12.2 禁照抄点：先定模式再录入） */
.dispense-return-mode-label {
  margin-left: auto;
}

/* 行编辑表 min-height 200 锁三态零塌陷（蓝图 P12.3 CLS）；与摘要区间距既有 */
.dispense-return-items {
  margin-top: 12px;
  min-height: 200px;
}

/* 提交区 flex 右挂（蓝图 P12.2/12.3） */
.dispense-return-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
