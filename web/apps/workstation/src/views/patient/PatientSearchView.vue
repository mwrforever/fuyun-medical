<script setup lang="ts">
// 患者检索页（FU-M02-02）：证件号/手机号/姓名关键词分页检索，输出统一脱敏（Task 6 后端已脱敏，
// 前端原样渲染不二次处理明文）；分页组件 1 基 ↔ 后端契约 0 基在本页边界转换；行点击跳详情。
// 体量小不设 composable（简单优先），数据获取以组件内 ref 承载；失败弹错归响应拦截器（web A.3-2）。
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { patientSexText, patientStatusTagType, patientStatusText } from '@/utils/patientDisplay';

const router = useRouter();

/** 检索词模型（证件号/手机号/姓名；trim 后为空则前置拦截，防全量扫描拖库） */
const keyword = ref('');
/** 当页数据（服务端分页返回，脱敏行） */
const rows = ref<PatientVO[]>([]);
/** 总条数（分页条渲染用） */
const total = ref(0);
/** 当前页码（1 基绑定分页组件，请求时转 0 基） */
const currentPage = ref(1);
/** 单页条数 */
const pageSize = ref(20);
/** 表格加载态 */
const loading = ref(false);
/** 是否已执行过检索（区分「初始未查」与「查无结果」两态提示） */
const searched = ref(false);

/** 执行检索：以当前 keyword/页码请求；校验失败由拦截器弹错，本页驻留旧结果 */
async function fetchPage(): Promise<void> {
  loading.value = true;
  try {
    const resp = await searchPatients({
      keyword: keyword.value.trim(),
      // 边界转换：组件 current-page 1 基 → 契约 page 0 基（api 层保持纯透传）
      page: currentPage.value - 1,
      size: pageSize.value,
    });
    rows.value = resp.content;
    total.value = resp.total;
    searched.value = true;
  } catch {
    // 失败弹错归响应拦截器；终止本次翻页
  } finally {
    loading.value = false;
  }
}

/** 查询按钮：换词检索回第一页；空关键词前置提示不出网 */
async function handleQuery(): Promise<void> {
  if (keyword.value.trim() === '') {
    void ElMessage.warning('请输入检索词（证件号/手机号/姓名）');
    return;
  }
  currentPage.value = 1;
  await fetchPage();
}

/**
 * 翻页：分页组件回传 1 基页码。
 *
 * @param page 目标页码（1 基）
 */
async function handlePageChange(page: number): Promise<void> {
  currentPage.value = page;
  await fetchPage();
}

/**
 * 行点击跳详情：雪花 ID 经 String 归一后入路径（后端 Long→String 输出，禁 number 处理）。
 *
 * @param row 被点击的脱敏行
 */
function handleRowClick(row: PatientVO): void {
  if (row.patientId === undefined) {
    return;
  }
  void router.push(`/patients/${String(row.patientId)}`);
}
</script>

<template>
  <el-card class="patient-search">
    <template #header>患者检索</template>
    <div class="patient-search-bar">
      <el-input
        v-model="keyword"
        class="patient-search-input"
        placeholder="证件号 / 手机号 / 姓名"
        clearable
        @keyup.enter="handleQuery"
      />
      <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rows"
      class="patient-search-table"
      @row-click="handleRowClick"
    >
      <el-table-column prop="name" label="姓名" min-width="100" />
      <el-table-column label="性别" width="70">
        <template #default="{ row }">{{ patientSexText(row.sex) }}</template>
      </el-table-column>
      <el-table-column prop="idCardNo" label="证件号（脱敏）" min-width="170" />
      <el-table-column prop="mobile" label="手机号（脱敏）" min-width="130" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="patientStatusTagType(row.status)">{{
            patientStatusText(row.status)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="建档时间" min-width="170" />
    </el-table>
    <p v-if="searched && rows.length === 0" class="patient-search-empty">未检索到匹配患者</p>
    <el-pagination
      v-if="total > 0"
      background
      layout="total, prev, pager, next"
      :current-page="currentPage"
      :page-size="pageSize"
      :total="total"
      class="patient-search-pagination"
      @current-change="handlePageChange"
    />
  </el-card>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.patient-search {
  max-width: 1080px;
}

.patient-search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.patient-search-input {
  max-width: 360px;
}

/* 行点击跳详情：整行可点，指针态提示 */
.patient-search-table {
  cursor: pointer;
}

.patient-search-pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.patient-search-empty {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
