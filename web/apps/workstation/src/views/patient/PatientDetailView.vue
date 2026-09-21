<script setup lang="ts">
// 患者详情页（FU-M02-03）：按路由 patientId 拉取脱敏档案渲染（后端输出已脱敏，前端不二次处理明文）；
// 冻结/解冻成对动作——冻结必须经 prompt 收集原因（审计留痕 + 后端必填校验），解冻直发；
// 动作按钮带 loading + freezing 在途守卫（先于一切 await，防双击二次出网，§5.1）；
// 动作失败弹错归响应拦截器（web A.3-2），页面驻留供重试。
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessageBox } from 'element-plus';
// ElMessageBox 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message-box/style/css';
import { changeFreeze, getPatient } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import {
  patientArchiveSourceText,
  patientRegisterChannelText,
  patientSexText,
  patientStatusTagType,
  patientStatusText,
} from '@/utils/patientDisplay';

const route = useRoute();

/** 患者 id：路由参数以 string 承载雪花 ID（禁 number 处理，web A.3-6） */
const patientId = computed(() => String(route.params.patientId ?? ''));

/** 档案数据（null=未取到；挂载与状态动作成功后刷新复用） */
const patient = ref<PatientVO | null>(null);
const loading = ref(false);
/** 冻结/解冻在途标志：true 期间动作按钮 loading 且重复点击直接返回（防双击二次出网） */
const freezing = ref(false);

/** 拉取详情：失败弹错归响应拦截器，详情区保持空态 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    patient.value = await getPatient(patientId.value);
  } finally {
    loading.value = false;
  }
}

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
  <div class="fuy-page fuy-stagger">
    <el-card class="patient-detail">
      <template #header>
        <!-- 页头行（§9.4-③）：患者名 emphasis + 状态 tag 左侧标识，冻结/解冻动作右对齐 -->
        <div class="patient-detail-header">
          <span class="patient-detail-title">
            <span class="patient-detail-name">{{ patient?.name ?? '患者档案' }}</span>
            <el-tag
              v-if="patient"
              :type="patientStatusTagType(patient.status)"
              class="fuy-tag-aa"
              >{{ patientStatusText(patient.status) }}</el-tag
            >
          </span>
          <span class="patient-detail-actions">
            <!-- 冻结/解冻成对呈现：仅正常档可冻结、仅冻结档可解冻（已合并档只读）；loading 即在途态 -->
            <el-button
              v-if="patient?.status === 'NORMAL'"
              :loading="freezing"
              @click="handleChangeFreeze(true)"
              >冻结</el-button
            >
            <el-button
              v-if="patient?.status === 'FROZEN'"
              :loading="freezing"
              @click="handleChangeFreeze(false)"
              >解冻</el-button
            >
          </span>
        </div>
      </template>
      <!-- v-loading 仅罩档案区（首屏走骨架，刷新已有数据才显示遮罩）：卡头动作不再随加载闪烁 -->
      <div v-loading="loading && patient !== null" class="patient-detail-body">
        <!-- 首屏骨架（§4.4 骨架屏条款）：数据未达时占位，min-height 与档案区对齐防 CLS -->
        <el-skeleton v-if="!patient && loading" :rows="4" animated />
        <el-empty v-else-if="!patient" :image-size="72" description="未查询到患者档案" />
        <!-- 骨架 → 内容切换（§6.7）：内容进场 200ms 淡入，骨架离场瞬切不做交叉溶解 -->
        <Transition v-else name="fuy-content-fade">
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
            <el-descriptions-item label="手机号（脱敏）">{{ patient.mobile }}</el-descriptions-item>
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
    </el-card>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：详情卡宽 880 统一档（§9.2.2），本块只留页头行与档案区布局 */
.patient-detail {
  max-width: 880px;
}

/* 页头行（§9.4 通用落点 48px 行高）：患者名+状态 tag 与动作按钮两端对齐 */
.patient-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
}

.patient-detail-title {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
}

/* 患者名 emphasis：18px/600 关键标识（§9.4-③ 卡头改页头行） */
.patient-detail-name {
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

/* 档案区：min-height 锁定骨架/空态/内容三态切换零塌陷（§7.1 CLS） */
.patient-detail-body {
  min-height: 200px;
}
</style>
