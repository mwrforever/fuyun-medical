<script setup lang="ts">
// 患者检索页（FU-M02-02）：证件号/手机号/姓名关键词分页检索，输出统一脱敏（Task 6 后端已脱敏，
// 前端原样渲染不二次处理明文）；分页组件 1 基 ↔ 后端契约 0 基的边界转换经 usePagedList 收口
// （EX-49 范式迁移）；行点击与「详情」link 按钮列双通道跳详情（F-4 键盘可达收口：按钮原生
// 可聚焦，键盘用户有主流程路径）。失败弹错归响应拦截器（web A.3-2）。
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { usePagedList } from '@/composables/usePagedList';
import { patientSexText, patientStatusTagType, patientStatusText } from '@/utils/patientDisplay';

const router = useRouter();

/** 检索词模型（证件号/手机号/姓名；trim 后为空则前置拦截，防全量扫描拖库） */
const keyword = ref('');
/** 是否已执行过检索（区分「初始未查」与「查无结果」两态提示） */
const searched = ref(false);

/** 分页检索三段式（EX-49 范式迁移）：页码/行集/总数/加载态经 usePagedList 收拢，检索词归
 * 本页持有经快照工厂实时取值合并出网（行为与迁移前一致——失败弹错归响应拦截器、驻留旧结果） */
const { rows, total, loading, currentPage, pageSize, search, goToPage } = usePagedList({
  params: () => ({ keyword: keyword.value.trim() }),
  fetcher: ({ keyword: kw, page, size }) => searchPatients({ keyword: kw, page, size }),
  pageSize: 20,
  // 检索成功置位（失败驻留旧结果时保持原 searched 态，防误显「无结果」空态）
  onSuccess: () => {
    searched.value = true;
  },
});

/** 查询按钮：换词检索回第一页；空关键词前置提示不出网 */
async function handleQuery(): Promise<void> {
  if (keyword.value.trim() === '') {
    void ElMessage.warning('请输入检索词（证件号/手机号/姓名）');
    return;
  }
  await search();
}

/**
 * 翻页：分页组件回传 1 基页码。
 *
 * @param page 目标页码（1 基）
 */
async function handlePageChange(page: number): Promise<void> {
  await goToPage(page);
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
  <div class="fuy-page fuy-stagger">
    <!-- fuy-dense 挂外层卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：element-plus.css
         密度规则均为后代选择器 .fuy-dense .el-table，挂在表格自身不构成后代关系、零生效
        （质量门 R1 F-1）；四条规则全带表格前缀，不影响卡内工具条/分页 -->
    <el-card class="fuy-dense">
      <template #header>患者检索</template>
      <div class="fuy-toolbar">
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
            <el-tag :type="patientStatusTagType(row.status)" class="fuy-tag-aa">{{
              patientStatusText(row.status)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="建档时间" min-width="170" />
        <el-table-column label="操作" width="60">
          <template #default="{ row }">
            <!-- 详情按钮：键盘可达的跳详情第二通道（stop 防与行点击双触发） -->
            <el-button link type="primary" @click.stop="handleRowClick(row)">详情</el-button>
          </template>
        </el-table-column>
        <!-- 空态区分两态（§4.4 业务口径 description）：初始未查给操作指引（批次 2 移交打磨项——
             原初始为 240px 空白板），已查无结果给业务结果提示 -->
        <template #empty>
          <el-empty
            v-if="!searched"
            :image-size="72"
            description="输入证件号 / 手机号 / 姓名后查询患者档案"
          />
          <el-empty v-else :image-size="72" description="未检索到匹配患者" />
        </template>
      </el-table>
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
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：工具条已收编 .fuy-toolbar（§9.2.2），卡宽随 .fuy-page
   全宽（列表卡 1080 上限撤销，密度优先全宽利用），本块只留 input 宽度/表格交互态/分页间距 */
.patient-search-input {
  max-width: 360px;
}

/* 行点击跳详情：整行可点指针态提示；min-height 锁定加载/空态切换零塌陷（§7.1 CLS） */
.patient-search-table {
  min-height: 240px;
  cursor: pointer;
}

.patient-search-pagination {
  margin-top: var(--fuy-space-3);
  justify-content: flex-end;
}
</style>
