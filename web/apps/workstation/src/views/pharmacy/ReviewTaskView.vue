<script setup lang="ts">
// 住院审方台页（/pharmacy/review，M06 住院用药审方薄切片工作台 · 暖纸卷宗 P13 蓝图重排）：
// 待审任务列表（医嘱号/患者摘要[号面脱敏口径]/药品明细/申请科室/频次，先到先审 FIFO 后端
// 承载）+ 通过/驳回操作（通过即出网；驳回弹窗意见必填——零出网显式校验前置，PH-1020 服务端
// 兜底）。通过/驳回均为法定留痕写操作（后端 WRITE 审计），回执事件驱动 M04 医嘱迁移；操作后
// 列表重拉刷新。全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器
// （AxiosError 防双弹）。
// 构图（蓝图 P13.2「换脸不换业务」单列流水，本页为域内唯一 native→EP 控件替换页）：门牌页首
// （衬线标题 + amber 待审计数胶囊=行集本地 reduce 零额外出网 + 签认人·时刻批注行，「先到先审」
// 注记随批注行尾）→ 任务卡（筛选 .fuy-filter：el-select 替换 native select[popper 挂
// fuy-snap-popper] + 刷新钮右挂 → 队列表 .fuy-dense 挂卡容器）；弹层=驳回 dialog（挂
// .fuy-dialog，native textarea → el-input type=textarea + .fuy-form 表单脸）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { REVIEW_TASK_STATUS_OPTIONS, reviewTasks } from '@/api/pharmacy';
import type { ReviewTaskVO } from '@/api/pharmacy';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';

// 会话入口（门牌批注行「谁」签认人；元素权限仍由 v-perm 直挂消费同一会话）
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

/* ==================== 待审任务列表 ==================== */
/** 状态过滤（默认待审态；空串=全部状态） */
const statusFilter = ref('PENDING');

/** 待审任务列表三段式（EX-49 范式迁移，固定首页清单形态——page 恒 0、size 50 直出，
 * 无翻页 UI）：行集/加载态经 usePagedList 收拢，状态过滤归本页持有经快照工厂实时
 * 取值合并出网（先到先审 FIFO 排序由后端承载，前端按返回序直出；行为与迁移前一致
 * ——失败弹错归响应拦截器、驻留旧清单）。 */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({ status: statusFilter.value === '' ? undefined : statusFilter.value }),
  fetcher: (query) => reviewTasks.list(query),
  pageSize: 50,
});

/** 门牌胶囊待审计数：当前页行集本地 reduce 聚合（蓝图 P13.2 待审量前置感知），零额外出网 */
const pendingCount = computed(() => rows.value.filter((row) => row.status === 'PENDING').length);

/** 状态切换：重拉清单 */
function onFilterChange(): void {
  void loadList();
}

/* ==================== 审方通过 ==================== */
const approvingId = ref('');

/** 审方通过（PENDING→APPROVED）：回执 audit-completed 回流 M04 置医嘱可执行。
 * 入口在途早退守卫（逐行互斥）防双击重复决策。 */
async function onApprove(row: ReviewTaskVO): Promise<void> {
  if (approvingId.value !== '') {
    return;
  }
  approvingId.value = row.id ?? '';
  try {
    await reviewTasks.approve(row.id ?? '');
    void ElMessage.success(`已通过：医嘱 ${row.m04OrderNo ?? ''}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    approvingId.value = '';
  }
}

/* ==================== 审方驳回（意见必填弹窗） ==================== */
const rejectVisible = ref(false);
const rejecting = ref(false);
const rejectTarget = ref<ReviewTaskVO | null>(null);
const rejectOpinion = ref('');

/** 打开驳回弹窗（复位意见） */
function openReject(row: ReviewTaskVO): void {
  rejectTarget.value = row;
  rejectOpinion.value = '';
  rejectVisible.value = true;
}

/**
 * 确认驳回：意见必填显式校验（零出网；服务端 PH-1020 兜底同语义）→ 出网（回执
 * audit-rejected 驱动医生站修改重提）→ 关窗刷新列表。入口在途早退守卫防双击重复决策。
 */
async function onReject(): Promise<void> {
  if (rejecting.value) {
    return;
  }
  if (rejectOpinion.value.trim() === '') {
    void ElMessage.warning('请填写驳回意见（必填）');
    return;
  }
  rejecting.value = true;
  try {
    await reviewTasks.reject(rejectTarget.value?.id ?? '', {
      opinion: rejectOpinion.value.trim(),
    });
    void ElMessage.success(`已驳回：医嘱 ${rejectTarget.value?.m04OrderNo ?? ''}`);
    rejectVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    rejecting.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <!-- 暖纸卷宗 P13 重排：页面纵向序=门牌页首 → 任务卡（筛选 → 队列表）→ 驳回弹层。页根
       不挂 .fuy-stagger（契约 ⑦.4：路由进场过渡归 MainLayout，防双重进场节奏）；stagger
       两档（门牌页首 0 / 任务卡 1，P01 先例门牌入级联），一次进场 40ms 级联零常驻开销 -->
  <div class="fuy-page review-task">
    <div class="review-flow fuy-stagger">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 右挂 amber 待审计数胶囊（行集本地 reduce，零额外
           出网）+ 签认人·时刻批注行；「先到先审」业务注记随批注行尾（蓝图 P13.2 注记归属）；
           2px 墨规收底走全局 .fuy-page-head 脸，本页零私有标题样式 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">住院审方台</h1>
        </div>
        <div class="fuy-page-status">
          <!-- 状态位=待审计数胶囊（蓝图 P13.2）：amber 洗底=待办语义；静态计数不挂呼吸点
               （实时/进行语义才挂，总则 2 状态胶囊双轨制） -->
          <span class="fuy-status-pill fuy-status-pill--amber">
            待审 <span class="fuy-num">{{ pendingCount }}</span>
          </span>
        </div>
        <p class="fuy-page-note">
          签认人 {{ signerName }} · <time>{{ todayLabel }}</time> ·
          住院用药医嘱须经药师审方通过方可摆药（先到先审）
        </p>
      </header>

      <!-- 任务卡=主工作区（蓝图 P13.2）：筛选 .fuy-filter（状态 el-select + 刷新钮右挂）→
           队列表；fuy-dense 挂卡容器（§9.4 通用落点）：表格 size="small" 移除，fuy-dense
           唯一密度通道；操作列按钮 size=small 为既有 link 钮尺寸，与表格密度无关保留 -->
      <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 1 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">审方任务</h2>
          <div class="fuy-card-extra">
            共 <span class="fuy-num">{{ rows.length }}</span> 条
          </div>
        </header>
        <div class="fuy-card-body">
          <!-- 筛选区升 .fuy-filter 独立语义位（契约 ⑧.3）：状态过滤 native select → el-select
               （蓝图 P13.1 域内唯一控件替换页，消灭三裂控件脸），popper 统一挂 fuy-snap-popper
               （契约 ⑤#5/⑦.5）；刷新钮右挂 -->
          <div class="fuy-filter">
            <label for="review-status-filter">状态</label>
            <el-select
              id="review-status-filter"
              v-model="statusFilter"
              class="review-status-select"
              popper-class="fuy-snap-popper"
              aria-label="任务状态"
              @change="onFilterChange"
            >
              <el-option value="" label="全部状态" />
              <el-option
                v-for="item in REVIEW_TASK_STATUS_OPTIONS"
                :key="item.code"
                :value="item.code"
                :label="item.label"
              />
            </el-select>
            <div class="fuy-filter-actions">
              <el-button :loading="listLoading" @click="loadList">刷新</el-button>
            </div>
          </div>
          <el-table v-loading="listLoading" :data="rows" class="review-queue-table">
            <el-table-column label="医嘱号" min-width="140">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.m04OrderNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="患者摘要（号面）" min-width="200">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.visitId }} / {{ row.patientId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="药品明细" min-width="200">
              <template #default="{ row }">
                <span class="review-items" :title="row.items">{{ row.items ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="频次" width="60">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.freqCode ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="申请科室" width="90">
              <template #default="{ row }">
                {{ row.applyDept ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <!-- 行内状态维持 el-tag 家族既有双轨（总则 2：表格行内状态不走页首胶囊轨） -->
                <el-tag size="small" class="fuy-tag-aa">{{
                  REVIEW_TASK_STATUS_OPTIONS.find((item) => item.code === row.status)?.label ??
                  row.status
                }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" class-name="fuy-ops-8">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <!-- 审方通过/驳回（PR-4F #24，PHARMACIST 绑定）同码 v-perm 直挂——
                       无码 DOM 移除（D-34）；驳回弹窗「确认驳回」随入口免挂接 -->
                  <el-button
                    v-perm="'pharmacy:review:btn:audit'"
                    link
                    type="primary"
                    size="small"
                    :loading="approvingId === row.id"
                    :disabled="approvingId !== ''"
                    @click="onApprove(row)"
                    >通过</el-button
                  >
                  <el-button
                    v-perm="'pharmacy:review:btn:audit'"
                    link
                    type="danger"
                    size="small"
                    :disabled="approvingId !== '' || rejecting"
                    @click="openReject(row)"
                    >驳回</el-button
                  >
                </template>
                <span v-else class="review-decided">已处置</span>
              </template>
            </el-table-column>
            <!-- 诚实空态脸（契约 ⑥ 禁纸箱插画）：主句「暂无」语法 + 说明给下一步（切筛选/刷新） -->
            <template #empty>
              <div class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无审方任务</p>
                <p class="fuy-empty-hint">当前筛选条件下暂无任务，可切换状态或点击刷新。</p>
              </div>
            </template>
          </el-table>
        </div>
      </section>
    </div>

    <!-- 驳回弹窗（意见必填）：挂 .fuy-dialog 弹层脸（卡面底+radius 14+shadow-lg+衬线标题，
         契约 ⑤#9）+ .fuy-form 表单脸；意见录入 native textarea → el-input type=textarea
         （蓝图 P13.1 控件替换收口）——驳回为危险动作，成功反馈维持 ElMessage 批注条既有口径，
         危险/失败永不钤印（总则 1） -->
    <el-dialog v-model="rejectVisible" title="审方驳回" width="420px" class="fuy-dialog">
      <p class="review-reject-target fuy-num">医嘱 {{ rejectTarget?.m04OrderNo ?? '' }}</p>
      <el-form class="fuy-form" label-position="top">
        <el-form-item label="驳回意见（必填，医生站按意见修改重提）">
          <el-input
            v-model="rejectOpinion"
            type="textarea"
            :rows="3"
            maxlength="512"
            placeholder="填写驳回意见"
            aria-label="驳回意见"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- footer 双钮默认尺寸档（弹窗脸六族横向律，与 P07/P10 弹窗 footer 同律）；
             确认驳回挂 danger 实底（契约 ③.1 状态朱通道：驳回=状态语义危险动作，与行内
             驳回 link danger 同语义位；墨实底仅承常规主操作——墨即操作/双红分工不破） -->
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" :disabled="rejecting" @click="onReject"
          >确认驳回</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 审方台布局私项（web A.1-2）：门牌页首/卷宗卡/筛选脸/表单脸/空态脸/状态胶囊样式全部由
   element-plus.css 全局挂类承载，本块只留布局私项（stagger 容器纵列节奏、筛选下拉宽度、
   队列表 CLS 锁、明细列省略、已处置弱化、驳回目标行）；.fuy-page 根承载纵向 gap 与全宽，
   stagger 级联容器与 .fuy-page 同构纵列（契约 ⑦.4 stagger 挂页内区块不挂根） */
.review-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 状态过滤下拉（域内唯一 EP 化控件，蓝图 P13.2）：160px 档与域内筛选下拉同宽 */
.review-status-select {
  width: 160px;
}

/* 药品明细列（超长省略，title 悬停全览） */
.review-items {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

/* 已处置行弱化提示 */
.review-decided {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 队列表 min-height 240 锁加载/空态切换零塌陷（蓝图 P13.3/⑥ 性能要点 CLS 锁口径） */
.review-queue-table {
  margin-top: var(--fuy-space-3);
  min-height: 240px;
}

/* 驳回弹窗目标行 */
.review-reject-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}
</style>
