<script setup lang="ts">
// 转抄工作台页（/inpatient/transfer，M04 FU-M04-06 上前端面）：待转抄列表（病区维度/
// 高危药红色标识）+ 批量核对操作（勾选 → 转抄人/第二核对人[高危红*视觉必填指示]/
// 核对结论 → 提交批量；成功逐条数反馈，业务失败 IP-1016 detail 原文透出）。
// 双人核对的「高危/输血类缺第二核对人拒」业务规则单点归后端 IP-1016 把守（前端不复制
// 拦截，避免双源规则漂移）；结构必填（勾选/转抄人）显式校验零出网。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹）。
// 患者摘要按脱敏口径仅展示患者编号（后端 VO 契约即不含姓名）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import {
  ORDER_CLASS_OPTIONS,
  ORDER_TYPE_OPTIONS,
  transferWorklist,
  WARD_OPTIONS,
} from '@/api/inpatient';
import type { TransferWorklistVO } from '@/api/inpatient';

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/** 时点展示串（MM-dd HH:mm，开立时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 待转抄列表 ==================== */
const wardId = ref(WARD_OPTIONS[0].code);
const rows = ref<TransferWorklistVO[]>([]);
const listLoading = ref(false);
/** 勾选集合（批量提交 orderNos 顺序=列表序，保证出网稳定） */
const selectedNos = ref<Set<string>>(new Set());

/** 加载待转抄列表（病区维度 AUDITED 医嘱聚合，开立时间倒序） */
async function loadList(): Promise<void> {
  listLoading.value = true;
  try {
    const page = await transferWorklist.list({ wardId: wardId.value, page: 0, size: 50 });
    rows.value = page.content ?? [];
    // 重载后勾选集清空（旧勾选行可能已不在列表）
    selectedNos.value = new Set();
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    listLoading.value = false;
  }
}

/** 行勾选切换（native checkbox，jsdom 可测） */
function toggleSelect(row: TransferWorklistVO): void {
  const orderNo = row.orderNo ?? '';
  const next = new Set(selectedNos.value);
  if (next.has(orderNo)) {
    next.delete(orderNo);
  } else {
    next.add(orderNo);
  }
  selectedNos.value = next;
}

/** 医嘱类型中文词表反查（列表类型列） */
function orderTypeLabel(code: string | undefined): string {
  return ORDER_TYPE_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/** 医嘱分类中文词表反查（列表分类列） */
function orderClassLabel(code: string | undefined): string {
  return ORDER_CLASS_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/* ==================== 批量核对 ==================== */
const submitting = ref(false);
const checkForm = ref({ transferNurseId: '', secondCheckerId: '', conclusion: 'PASSED' });

/** 勾选中含高危行（第二核对人红*指示源） */
const hasHighRisk = computed(() =>
  rows.value.some((row) => row.highRisk === true && selectedNos.value.has(row.orderNo ?? '')),
);

/** 行勾选态（checkbox 回显） */
function isChecked(row: TransferWorklistVO): boolean {
  return selectedNos.value.has(row.orderNo ?? '');
}

/**
 * 提交批量核对：结构必填显式校验（勾选/转抄人，零出网）→ 出网（业务规则归后端把守：
 * 高危缺第二核对人 IP-1016，detail 原文透出）→ 成功逐条数反馈并刷新列表。
 * 入口在途早退守卫防双击重复提交。
 */
async function onSubmitCheck(): Promise<void> {
  if (submitting.value) {
    return;
  }
  if (selectedNos.value.size === 0) {
    void ElMessage.warning('请先勾选待转抄医嘱');
    return;
  }
  if (checkForm.value.transferNurseId.trim() === '') {
    void ElMessage.warning('请填写转抄人工号');
    return;
  }
  submitting.value = true;
  try {
    const orderNos = rows.value
      .map((row) => row.orderNo ?? '')
      .filter((no) => selectedNos.value.has(no));
    await transferWorklist.check({
      orderNos,
      transferNurseId: checkForm.value.transferNurseId.trim(),
      conclusion: checkForm.value.conclusion === 'REJECTED' ? 'REJECTED' : 'PASSED',
      secondCheckerId:
        checkForm.value.secondCheckerId.trim() === ''
          ? undefined
          : checkForm.value.secondCheckerId.trim(),
    });
    void ElMessage.success(`批量核对完成：${orderNos.length} 条已转抄`);
    checkForm.value.secondCheckerId = '';
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page transfer-worklist fuy-stagger">
    <!-- 页头：标题 + 病区切换 + 刷新 -->
    <header class="transfer-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="transfer-title">转抄工作台</h2>
      <span class="transfer-hint">病区 AUDITED 医嘱聚合 · 高危/输血类须双人核对</span>
      <select v-model="wardId" class="transfer-ward-select" aria-label="病区" @change="loadList">
        <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
          {{ ward.label }}
        </option>
      </select>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <el-col :md="24" :lg="16">
        <el-card>
          <template #header>
            <span>待转抄列表（共 {{ rows.length }} 条）</span>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="勾选" width="48">
                <template #default="{ row }">
                  <input
                    type="checkbox"
                    :checked="isChecked(row)"
                    :aria-label="`勾选 ${row.orderNo}`"
                    @change="toggleSelect(row)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="医嘱号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.orderNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="就诊号" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.visitId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="患者编号（脱敏）" min-width="140">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.patientId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="70">
                <template #default="{ row }">
                  {{ orderTypeLabel(row.orderType) }}
                </template>
              </el-table-column>
              <el-table-column label="分类" width="60">
                <template #default="{ row }">
                  {{ orderClassLabel(row.orderClass) }}
                </template>
              </el-table-column>
              <el-table-column label="频次" width="60">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.freqCode ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="开立" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.orderedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="高危" width="80">
                <template #default="{ row }">
                  <!-- 高危药红色标识（双人核对强制面指示；机器判据类） -->
                  <span v-if="row.highRisk === true" class="transfer-highrisk-flag">高危</span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="当前病区暂无待转抄医嘱" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：批量核对操作 -->
      <el-col :md="24" :lg="8">
        <el-card>
          <template #header>
            <span>批量核对（已选 {{ selectedNos.size }} 条）</span>
          </template>
          <label class="transfer-field-label">转抄人工号</label>
          <input
            v-model="checkForm.transferNurseId"
            class="transfer-input"
            placeholder="转抄护士工号"
            aria-label="转抄人工号"
          />
          <label class="transfer-field-label">
            第二核对人
            <!-- 高危红*：双人核对强制面视觉指示（规则后端 IP-1016 把守） -->
            <span v-if="hasHighRisk" class="transfer-second-required" aria-label="高危必填">*</span>
          </label>
          <input
            v-model="checkForm.secondCheckerId"
            class="transfer-input"
            :placeholder="hasHighRisk ? '高危医嘱必填第二核对人' : '第二核对人（高危必填）'"
            aria-label="第二核对人"
          />
          <label class="transfer-field-label">核对结论</label>
          <div class="transfer-radio-row">
            <label class="transfer-radio-item">
              <input v-model="checkForm.conclusion" type="radio" value="PASSED" />
              核对通过
            </label>
            <label class="transfer-radio-item">
              <input v-model="checkForm.conclusion" type="radio" value="REJECTED" />
              核对不通过
            </label>
          </div>
          <el-button
            type="primary"
            class="transfer-submit"
            :loading="submitting"
            :disabled="submitting"
            @click="onSubmitCheck"
            >提交批量核对</el-button
          >
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 转抄工作台布局：左列表右操作栏，token 取色禁自创色值 */
.transfer-toolbar {
  align-items: center;
}

.transfer-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.transfer-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 病区切换（native select 与 EP 密度口径对齐） */
.transfer-ward-select {
  margin-left: auto;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 高危红色标识（红字描边弱底，双人核对强制面指示） */
.transfer-highrisk-flag {
  padding: 0 var(--fuy-space-1);
  border: 1px solid var(--fuy-color-danger-text);
  border-radius: var(--fuy-radius-sm);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 操作栏表单基元（token 取色） */
.transfer-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.transfer-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 高危红*（视觉必填指示） */
.transfer-second-required {
  color: var(--fuy-color-danger-text);
  font-weight: 700;
}

.transfer-radio-row {
  display: flex;
  gap: var(--fuy-space-4);
  margin-bottom: var(--fuy-space-3);
}

.transfer-radio-item {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.transfer-submit {
  width: 100%;
}
</style>
