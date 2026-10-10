<script setup lang="ts">
// 患者检索页（FU-M02-02 · 暖纸卷宗换脸重排，逐页蓝图 P02）：证件号/手机号/姓名关键词分页
// 检索，输出统一脱敏（Task 6 后端已脱敏，前端原样渲染不二次处理明文）；分页组件 1 基 ↔ 后端
// 契约 0 基的边界转换经 usePagedList 收口（EX-49 范式迁移）；行点击与「详情」link 按钮列双
// 通道跳详情（F-4 键盘可达收口：按钮原生可聚焦，键盘用户有主流程路径）。失败弹错归响应拦截
// 器（web A.3-2）。
// 构图（蓝图 P02「换脸不换业务」三段流水）：门牌页首（衬线标题 + 签认人·时刻批注行）→ 独立
// 筛选卡（.fuy-filter，查询墨实底钮右挂）→ 结果卡（.fuy-card + .fuy-dense 密排表 + 分页右挂）；
// 空态两态门控（未检索 / 查无结果）语义保留仅换 .fuy-empty 脸（契约 ⑥ 禁纸箱插画）。
// 数据契约零变动：searchPatients 出网参数 / usePagedList 分页口径与重排前逐字一致。
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message/style/css';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { patientSexText, patientStatusTagType, patientStatusText } from '@/utils/patientDisplay';

const router = useRouter();
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
  <section class="fuy-page">
    <!-- 三段流水容器：.fuy-page 根不挂 stagger（契约 ⑦.4 路由过渡已占根进场，防双重进场
         节奏），级联挂内层区块；三档 40ms 级联（门牌 0 / 筛选卡 1 / 结果卡 2，≤5 档预算内，
         一次进场零常驻开销） -->
    <div class="fuy-stagger patient-search-flow">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 签认人·时刻批注行 + 2px 墨规收底（脸样式归全局
           .fuy-page-head 族，本页零私有标题样式）；原 el-card #header「患者检索」升格于此 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">患者检索</h1>
        </div>
        <p class="fuy-page-note">
          签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- 独立筛选卡（契约 ⑧.3）：检索动作升独立语义位——label 疏排 + 关键词 360px + 查询
           墨实底钮右挂（.fuy-filter-actions margin-left:auto）；fuy-filter 承载 flex 布局、
           fuy-card-body 承载卡内边距，同类并存各司其职 -->
      <section class="fuy-card" :style="{ '--fuy-stagger-index': 1 }">
        <div class="fuy-card-body fuy-filter">
          <label for="patient-search-keyword">关键词</label>
          <el-input
            id="patient-search-keyword"
            v-model="keyword"
            class="patient-search-input"
            placeholder="证件号 / 手机号 / 姓名"
            clearable
            @keyup.enter="handleQuery"
          />
          <div class="fuy-filter-actions">
            <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
          </div>
        </div>
      </section>

      <!-- 结果卡（契约 ⑧.2 卷宗卡 + ⑧.4 密排表）：fuy-dense 挂卡容器——密度规则均为后代
           选择器 .fuy-dense .el-table，挂在表格自身不构成后代关系、零生效（质量门 R1 F-1）；
           四条规则全带表格前缀，不影响卡内筛选/分页；分页右挂经全局 .fuy-page .el-pagination
           规则（契约 ⑤#7）自动承继，激活页墨底纸字随 primary 换血自动 -->
      <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 2 }">
        <div class="fuy-card-body">
          <el-table
            v-loading="loading"
            :data="rows"
            class="patient-search-table"
            @row-click="handleRowClick"
          >
            <!-- 列序不动（蓝图 P02.3）：姓名/性别/证件号脱敏/手机号脱敏/状态/建档时间/操作 -->
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
            <!-- 建档时间为数字主导列：等宽承 ⑧.4「数字列一律 .fuy-num」（td 经 class-name
                 落类，scoped 哈希不进入 EP 内部渲染，必须全局类承载） -->
            <el-table-column
              prop="createdAt"
              label="建档时间"
              min-width="170"
              class-name="fuy-num"
            />
            <el-table-column label="操作" width="60">
              <template #default="{ row }">
                <!-- 详情按钮：键盘可达的跳详情第二通道（stop 防与行点击双触发） -->
                <el-button link type="primary" @click.stop="handleRowClick(row)">详情</el-button>
              </template>
            </el-table-column>
            <!-- 空态两态门控保留（未检索给操作指引 / 已查给业务结果），仅换 .fuy-empty 脸
                 （契约 ⑥：禁纸箱插画、主句合「尚无/暂无〈业务客体〉」语法 + 说明给下一步） -->
            <template #empty>
              <div v-if="!searched" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">尚无检索结果</p>
                <p class="fuy-empty-hint">输入证件号 / 手机号 / 姓名后点击查询。</p>
              </div>
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无匹配患者</p>
                <p class="fuy-empty-hint">未找到匹配档案，可更换关键词后重试。</p>
              </div>
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
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：三段流水节奏——.fuy-page 根承载纵向 gap 与全宽，页内区块
   级联容器与 .fuy-page 同构纵列（契约 ⑦.4 stagger 挂页内区块不挂根）；门牌页首/筛选脸/
   卷宗卡/空态脸/分页右挂样式全部由 element-plus.css 全局挂类承载，本块只留布局私项 */
.patient-search-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 筛选卡关键词输入宽（蓝图 P02.2：关键词 360px） */
.patient-search-input {
  max-width: 360px;
}

/* 行点击跳详情：指针态只落数据行（表头/空态区不可点，不给说谎的 pointer）。
   行节点是 el-table 子组件内部渲染，不带本组件 scoped data-v 属性，直写后代选择器
   编译后不匹配（死规则），须走 :deep() 穿透（create-actions 同款通道）；
   表格本体 min-height 锁定加载/空态切换零塌陷（蓝图 P02.6：表 240 高口径锁三态 CLS） */
.patient-search-table {
  min-height: 240px;
}

.patient-search-table :deep(.el-table__row) {
  cursor: pointer;
}

/* 分页与表格的纵向间距；justify-content:flex-end 归全局 .fuy-page .el-pagination 规则
   （契约 ⑤#7），不再重复声明 */
.patient-search-pagination {
  margin-top: var(--fuy-space-3);
}
</style>
