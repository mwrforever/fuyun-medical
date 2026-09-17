<script setup lang="ts">
// 患者详情页（FU-M02-03）：按路由 patientId 拉取脱敏档案渲染（后端输出已脱敏，前端不二次处理明文）；
// 冻结/解冻成对动作——冻结必须经 prompt 收集原因（审计留痕 + 后端必填校验），解冻直发；
// 动作失败弹错归响应拦截器（web A.3-2），页面驻留供重试。
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessageBox } from 'element-plus';
// ElMessageBox 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径）
import 'element-plus/es/components/message-box/style/css';
import { changeFreeze, getPatient } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { patientSexText, patientStatusTagType, patientStatusText } from '@/utils/patientDisplay';

const route = useRoute();

/** 患者 id：路由参数以 string 承载雪花 ID（禁 number 处理，web A.3-6） */
const patientId = computed(() => String(route.params.patientId ?? ''));

/** 档案数据（null=未取到；挂载与状态动作成功后刷新复用） */
const patient = ref<PatientVO | null>(null);
const loading = ref(false);

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
  }
}
</script>

<template>
  <el-card v-loading="loading" class="patient-detail">
    <template #header>
      <div class="patient-detail-header">
        <span>患者档案</span>
        <span class="patient-detail-actions">
          <!-- 冻结/解冻成对呈现：仅正常档可冻结、仅冻结档可解冻（已合并档只读） -->
          <el-button v-if="patient?.status === 'NORMAL'" @click="handleChangeFreeze(true)"
            >冻结</el-button
          >
          <el-button v-if="patient?.status === 'FROZEN'" @click="handleChangeFreeze(false)"
            >解冻</el-button
          >
        </span>
      </div>
    </template>
    <el-descriptions v-if="patient" :column="2" border>
      <el-descriptions-item label="患者ID">{{
        String(patient.patientId ?? '')
      }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ patient.name }}</el-descriptions-item>
      <el-descriptions-item label="性别">{{ patientSexText(patient.sex) }}</el-descriptions-item>
      <el-descriptions-item label="出生日期">{{ patient.birthDate }}</el-descriptions-item>
      <el-descriptions-item label="证件号（脱敏）">{{ patient.idCardNo }}</el-descriptions-item>
      <el-descriptions-item label="手机号（脱敏）">{{ patient.mobile }}</el-descriptions-item>
      <el-descriptions-item label="住址（脱敏）" :span="2">{{
        patient.address
      }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="patientStatusTagType(patient.status)">{{
          patientStatusText(patient.status)
        }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="实名标志">{{
        patient.realNameFlag ? '已实名' : '未实名'
      }}</el-descriptions-item>
      <el-descriptions-item label="建档渠道">{{ patient.registerChannel }}</el-descriptions-item>
      <el-descriptions-item label="档案来源">{{ patient.archiveSource }}</el-descriptions-item>
      <el-descriptions-item label="建档时间">{{ patient.createdAt }}</el-descriptions-item>
    </el-descriptions>
    <p v-else-if="!loading" class="patient-detail-empty">未查询到患者档案</p>
  </el-card>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.patient-detail {
  max-width: 880px;
}

.patient-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.patient-detail-empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
