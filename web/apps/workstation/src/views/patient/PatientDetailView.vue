<script setup lang="ts">
// 患者详情页（FU-M02-03 · 暖纸卷宗 P03 蓝图重排）：按路由 patientId 拉取脱敏档案渲染
// （后端输出已脱敏，前端不二次处理明文）。换脸不换业务——数据契约 / 表单逻辑 / 权限口径
// 零变动：门牌页首（患者姓名升格衬线主标题 + 状态升 .fuy-status-pill 胶囊 + 冻结/解冻
// 动作位挂状态胶囊左侧 + 批注行=建档时间 · 档案来源）→ 档案卡（.fuy-card 全宽 + el-
// descriptions 3 列 border，label 列表头纸底走 ⑤#15 全局映射自动承继）。
// 冻结/解冻成对动作——冻结必须经 prompt 收集原因（审计留痕 + 后端必填校验），解冻直发；
// 动作按钮带 loading + freezing 在途守卫（先于一切 await，防双击二次出网，§5.1）；
// 动作成功=ElMessage 批注条（总则 1：页面级反馈不钤印，文案=动词结果）；
// 动作失败弹错归响应拦截器（web A.3-2），页面驻留供重试。
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/message/style/css';
import { changeFreeze, getPatient } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import {
  patientArchiveSourceText,
  patientRegisterChannelText,
  patientSexText,
  patientStatusTagType,
  patientStatusText,
} from '@/utils/patientDisplay';

const route = useRoute();
// 元素权限判定入口（PR-4F F8）：冻结/解冻按钮行内与运算消费（盘点 §3.5 口径）
const auth = useAuthStore();

/** 患者 id：路由参数以 string 承载雪花 ID（禁 number 处理，web A.3-6） */
const patientId = computed(() => String(route.params.patientId ?? ''));

/** 档案数据（null=未取到；挂载与状态动作成功后刷新复用） */
const patient = ref<PatientVO | null>(null);
/** 冻结/解冻在途标志：true 期间动作按钮 loading 且重复点击直接返回（防双击二次出网） */
const freezing = ref(false);
/** 首轮详情加载是否已落定（成败均计）：未落定前空态脸不入场——否则首帧渲染（loading
 * 翻转前）会闪现一帧「未查询到患者档案」再跳骨架，违背三态机「骨架先行」语义 */
const settled = ref(false);

/** 状态胶囊语义档（.fuy-status-pill 四色变体）：随 patientStatusTagType 单源派生，
 * 对齐契约 §③.2 唯一映射（2026-10-11 主控裁决：冻结=warning 警示族→amber 胶囊、
 * 正常=success、合并=warning；danger 印泥朱只承危急/停用/作废，原 FROZEN danger 档
 * 系域内历史偏离随裁决归位） */
const STATUS_PILL_CLASS: Record<string, string> = {
  success: 'fuy-status-pill--success',
  warning: 'fuy-status-pill--amber',
  danger: 'fuy-status-pill--danger',
  info: 'fuy-status-pill--info',
};

/** 状态胶囊语义档类名：未知状态回 info 灰（词表未知态不猜色） */
const statusPillClass = computed(
  () => STATUS_PILL_CLASS[patientStatusTagType(patient.value?.status)] ?? 'fuy-status-pill--info',
);

/** 门牌批注行（蓝图：建档时间 · 档案来源）：数据未达或字段缺失一律 — 占位，禁伪数据 */
const archiveNote = computed(() => {
  const createdAt = patient.value?.createdAt ?? '—';
  // 档案来源文案经域级词表单源映射；未知/缺失以 — 占位
  const source = patient.value ? patientArchiveSourceText(patient.value.archiveSource) : '';
  return `建档 ${createdAt} · 档案来源 ${source || '—'}`;
});

/** 拉取详情：loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移）。原样板无 catch，
 * 异常上抛语义保持——经 onError 重抛，失败仍向调用方传播（弹错归响应拦截器，详情区
 * 保持空态）。 */
const { loading, run: load } = useAsyncTask(
  async () => {
    patient.value = await getPatient(patientId.value);
  },
  {
    onError: (error) => {
      // 原样板无 catch，异常上抛语义保持（onMounted 路径失败即 unhandled rejection）
      throw error;
    },
    // 成败均计落定（finally 语义）：失败路径同样放行空态脸（弹错归拦截器 + 空态驻留，
    // 与既有口径一致），仅首轮未落定窗口被骨架承载
    onFinally: () => {
      settled.value = true;
    },
  },
);

onMounted(load);

/**
 * 冻结/解冻成对动作。
 *
 * @param freeze true=冻结（prompt 收集原因，取消即放弃）；false=解冻（直发）
 */
async function handleChangeFreeze(freeze: boolean): Promise<void> {
  // 在途守卫先于一切 await：动作互斥串行，双击/连点不产生第二次出网（§5.1 按钮三态）
  if (freezing.value) {
    return;
  }
  let reason: string | undefined;
  if (freeze) {
    try {
      const { value } = await ElMessageBox.prompt('请输入冻结原因（留痕审计）', '冻结患者', {
        confirmButtonText: '确认冻结',
        cancelButtonText: '取消',
        inputValidator: (input: string) => (input.trim() === '' ? '冻结原因不能为空' : true),
      });
      reason = value;
    } catch {
      // prompt 取消/关闭 = 放弃操作，不发请求
      return;
    }
  }
  freezing.value = true;
  try {
    // 成对动作透传：解冻不带原因参数（冻结原因仅冻结分支收集）
    if (freeze) {
      await changeFreeze(patientId.value, true, reason);
    } else {
      await changeFreeze(patientId.value, false);
    }
    // 页面级成功反馈=ElMessage 批注条（总则 1：页面级不钤印），文案=动词结果
    ElMessage.success(freeze ? '已冻结' : '已解冻');
    await load();
  } catch {
    // 状态并发漂移等失败弹错归响应拦截器；页面驻留供用户重试
  } finally {
    // 无论成败复位在途标志，按钮恢复可点（终态一致性以回刷后的服务端状态为准）
    freezing.value = false;
  }
}
</script>

<template>
  <div class="fuy-page">
    <!-- 暖纸卷宗 P03 重排：页面纵向序=门牌页首 → 卷宗卡主工作区（契约 ⑧）；业务行零变动。
         页级注释置于根元素内（不落模板顶层）——顶层注释会使组件成多根 fragment，测试挂载
         wrapper.element 落在容器节点上（VTU 单根前提被破） -->
    <!-- 级联容器挂页内区块不挂页根（契约 ⑦.4：路由进场过渡归 MainLayout 占根，页根再挂
         .fuy-stagger 会叠出双重进场节奏；与建档/检索两页页内级联通律）：
         门牌页首 0 / 档案卡 1 两档 40ms 级联，首档显式声明（三页同律可审计） -->
    <div class="fuy-stagger patient-detail-flow">
      <!-- 门牌页首（⑧.1，stagger 第 0 档）：主标题=患者姓名动态承接（衬线 22/700），数据
           未达前以「患者档案」占位（既有占位词零变动）；状态胶囊右挂语义色；冻结/解冻
           动作位挂状态胶囊左侧 baseline 对齐（P03 蓝图 2）；2px 墨规收底走全局脸 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">{{ patient?.name ?? '患者档案' }}</h1>
        </div>
        <!-- 页首动作位与状态胶囊：动作按钮仅正常档可冻结、仅冻结档可解冻（已合并档只读）；
             loading 即在途态。冻结权限（PR-4F #2）：患者状态数据态与元素权限行内与运算——
             权限在外数据在内，两层独立判定（无码全隐藏 D-34，状态不符仅不渲染） -->
        <div class="fuy-page-status patient-detail-side">
          <el-button
            v-if="patient?.status === 'NORMAL' && auth.hasPerm('patient:archive:btn:freeze')"
            :loading="freezing"
            @click="handleChangeFreeze(true)"
            >冻结</el-button
          >
          <el-button
            v-if="patient?.status === 'FROZEN' && auth.hasPerm('patient:archive:btn:freeze')"
            :loading="freezing"
            @click="handleChangeFreeze(false)"
            >解冻</el-button
          >
          <!-- 状态胶囊（双轨制：门牌页首状态位用 .fuy-status-pill；档案区 descriptions 内
               状态行仍走 el-tag 家族，同一状态语义不跨轨混用） -->
          <span v-if="patient" class="fuy-status-pill" :class="statusPillClass">{{
            patientStatusText(patient.status)
          }}</span>
        </div>
        <!-- 「谁·何时」批注行（P03 蓝图：建档时间 · 档案来源），空值 — 占位禁伪数据 -->
        <p class="fuy-page-note">{{ archiveNote }}</p>
      </header>

      <!-- 主工作区=档案卡（stagger 第 1 档）：.fuy-card 全宽（880 上限撤销，descriptions
           拉伸）；v-loading 仅罩档案区（首屏走骨架，刷新已有数据才显示遮罩），动作位不随
           加载闪烁（既有口径零回归） -->
      <section class="fuy-card" :style="{ '--fuy-stagger-index': 1 }">
        <div v-loading="loading && patient !== null" class="fuy-card-body patient-detail-body">
          <!-- 首屏骨架（§4.4 骨架屏条款）：首轮加载未落定（含在途）占位，min-height 与
               档案区对齐防 CLS；以 settled 门控（非 loading）——首帧渲染先于 loading 翻转，
               直接挂 loading 会让空态脸在首帧抢跑一拍（防闪现） -->
          <el-skeleton v-if="!patient && !settled" :rows="4" animated />
          <!-- 诚实空态脸（契约 ⑥）：主句沿既有文案，说明句给恢复指引；不再渲染默认纸箱插画。
               入场与内容分支同走 content-fade（骨架→空态不做 0ms 硬切，settled 门控三态
               时序顺滑）；appear 必补——Transition 随本分支首次挂载，缺 appear 初次插入
               不播 enter（§9.6 同 R1 F-2 教训） -->
          <Transition v-else-if="!patient" name="fuy-content-fade" appear>
            <div class="fuy-empty" role="status">
              <span class="fuy-empty-mark" aria-hidden="true">空</span>
              <p class="fuy-empty-title">未查询到患者档案</p>
              <p class="fuy-empty-hint">请确认档案编号是否正确，或返回患者检索重新查询。</p>
            </div>
          </Transition>
          <!-- 骨架 → 内容切换（§6.7）：内容进场 200ms 淡入，骨架离场瞬切不做交叉溶解；
               appear 必补——Transition 随内容分支首次挂载，缺 appear 时 Vue 初次插入不播 enter，
               §9.6「骨架→内容」过渡将静默落空（质量门 R1 F-2） -->
          <Transition v-else name="fuy-content-fade" appear>
            <el-descriptions :column="3" border>
              <el-descriptions-item label="患者ID">{{
                String(patient.patientId ?? '')
              }}</el-descriptions-item>
              <el-descriptions-item label="姓名">{{ patient.name }}</el-descriptions-item>
              <el-descriptions-item label="性别">{{
                patientSexText(patient.sex)
              }}</el-descriptions-item>
              <el-descriptions-item label="出生日期">{{ patient.birthDate }}</el-descriptions-item>
              <el-descriptions-item label="证件号（脱敏）">{{
                patient.idCardNo
              }}</el-descriptions-item>
              <el-descriptions-item label="手机号（脱敏）">{{
                patient.mobile
              }}</el-descriptions-item>
              <el-descriptions-item label="住址（脱敏）" :span="2">{{
                patient.address
              }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="patientStatusTagType(patient.status)" class="fuy-tag-aa">{{
                  patientStatusText(patient.status)
                }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="实名标志">{{
                patient.realNameFlag ? '已实名' : '未实名'
              }}</el-descriptions-item>
              <el-descriptions-item label="建档渠道">{{
                patientRegisterChannelText(patient.registerChannel)
              }}</el-descriptions-item>
              <el-descriptions-item label="档案来源">{{
                patientArchiveSourceText(patient.archiveSource)
              }}</el-descriptions-item>
              <el-descriptions-item label="建档时间">{{ patient.createdAt }}</el-descriptions-item>
            </el-descriptions>
          </Transition>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：门牌/卡片/胶囊/空态全局脸由 element-plus.css 承载，
   本块只留页内级联容器、页首动作位与档案区布局 */

/* 页内级联容器（契约 ⑦.4 stagger 挂页内区块不挂根）：与 .fuy-page 同构纵列，
   纵向 gap 归 12 阶（工作面节奏，三页同律） */
.patient-detail-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 页首动作位与状态胶囊同排（P03 蓝图 2）：动作位挂胶囊左侧，随 .fuy-page-head 的
   baseline 对齐上屏 */
.patient-detail-side {
  display: inline-flex;
  align-items: baseline;
  gap: var(--fuy-space-3);
}

/* 档案区：min-height 锁定骨架/空态/内容三态切换零塌陷（§7.1 CLS，既有） */
.patient-detail-body {
  min-height: 200px;
}
</style>
