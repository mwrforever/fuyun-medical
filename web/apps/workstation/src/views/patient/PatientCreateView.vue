<script setup lang="ts">
// 患者建档页（FU-M02-01）：建档表单（实名制必填口径）+ 介质选择与读卡器占位按钮 +
// 建档前匹配预检（AUTO_MATCH 归一提示 / SUSPECT 待审提示）；弹错归响应拦截器（web A.3-2）。
import { computed, reactive, ref, useTemplateRef } from 'vue';
import { useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessageBox } from 'element-plus';
import { createPatient, matchCheck } from '@/api/patient';
import type { MatchCheckVO, PatientCreateRequest } from '@/api/patient';

const router = useRouter();

/** 表单模型（与后端 PatientCreateRequest 契约字段一致；敏感明文禁 console/log） */
const form = reactive<PatientCreateRequest>({
  name: '',
  sex: '',
  birthDate: '',
  ethnicity: '',
  maritalStatus: '',
  occupation: '',
  bloodType: '',
  idCardNo: '',
  mobile: '',
  address: '',
  registerChannel: 'WINDOW',
  archiveSource: 'STANDARD',
  identifierType: 'ID_CARD',
  identifierValue: '',
  cardNo: '',
  informedConsentRef: '',
});

/** 校验规则（对齐后端 Bean Validation 口径） */
const rules: FormRules<PatientCreateRequest> = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  sex: [{ required: true, message: '请选择性别', trigger: 'change' }],
  registerChannel: [{ required: true, message: '请选择建档渠道', trigger: 'change' }],
  informedConsentRef: [
    { required: true, message: '知情同意凭证引用必填（个保法单独同意留痕）', trigger: 'blur' },
  ],
  idCardNo: [
    {
      pattern: /^$|^\d{15}$|^\d{17}[0-9Xx]$/,
      message: '身份证号须为 15 位或 18 位',
      trigger: 'blur',
    },
  ],
  mobile: [{ pattern: /^$|^1\d{10}$/, message: '手机号须为 11 位数字', trigger: 'blur' }],
};

/** 介质类型选项（与后端 IdentifierType 词表一致） */
const mediumOptions = [
  { value: 'ID_CARD', label: '身份证（读卡/录入）' },
  { value: 'HEALTH_CARD', label: '电子健康卡' },
  { value: 'VISIT_CARD', label: '就诊卡' },
  { value: 'INSURANCE_ELECTRONIC', label: '医保电子凭证' },
] as const;

/** 读卡器可用性占位（接口位：设备驱动接入后启用——P1 计划「联调以手工录入兜底」） */
const readerAvailable = false;

const formRef = useTemplateRef<FormInstance>('formRef');
const submitting = ref(false);
const prechecking = ref(false);
/** 预检结论（表单内嵌提示条展示） */
const checkResult = ref<MatchCheckVO | null>(null);

/** 急诊无名氏快捷态：档案来源切换时联动提示 */
const isEmergency = computed(() => form.archiveSource === 'TEMP_ANONYMOUS');

/** 读卡器占位按钮：当前禁用并提示（禁伪造成功交互） */
function onReaderClick(): void {
  if (readerAvailable) {
    return;
  }
  void ElMessageBox.alert('读卡器接口位尚未接入，请手工录入证件信息', '提示', { type: 'info' });
}

/** 建档前预检：AUTO_MATCH 提示归一、SUSPECT 提示转人工核对 */
async function handlePrecheck(): Promise<void> {
  const valid = await formRef.value?.validate().then(
    () => true,
    () => false,
  );
  if (valid !== true) {
    return;
  }
  prechecking.value = true;
  try {
    checkResult.value = await matchCheck({
      name: form.name,
      sex: form.sex,
      birthDate: form.birthDate,
      idCardNo: form.idCardNo,
      mobile: form.mobile,
    });
  } finally {
    prechecking.value = false;
  }
}

/** 提交建档：成功后跳转详情页（candidatePatientId 即档案 id） */
async function handleSubmit(): Promise<void> {
  if (submitting.value) {
    return;
  }
  const valid = await formRef.value?.validate().then(
    () => true,
    () => false,
  );
  if (valid !== true) {
    return;
  }
  submitting.value = true;
  try {
    const result = await createPatient({ ...form });
    const patientId = String(result.candidatePatientId ?? '');
    await router.push(`/patients/${patientId}`);
  } catch {
    // 失败弹错归响应拦截器；表单驻留防数据丢失
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <el-card class="patient-create">
    <template #header>患者建档</template>
    <el-alert
      v-if="checkResult"
      :title="
        checkResult.outcome === 'AUTO_MATCH'
          ? `匹配到既有档案（ID ${checkResult.candidatePatientId}），提交后将归一至该档案`
          : checkResult.outcome === 'SUSPECT'
            ? '疑似重复：提交后将转人工核对（生成疑似重复待审）'
            : '未匹配到既有档案，将建立新档案'
      "
      :type="
        checkResult.outcome === 'AUTO_MATCH'
          ? 'warning'
          : checkResult.outcome === 'SUSPECT'
            ? 'warning'
            : 'success'
      "
      show-icon
      class="patient-create-check"
      :closable="false"
    />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="140px">
      <el-form-item label="姓名" prop="name">
        <el-input v-model="form.name" placeholder="急诊无名氏录「无名氏」" />
      </el-form-item>
      <el-form-item label="性别" prop="sex">
        <el-select v-model="form.sex" placeholder="请选择">
          <el-option value="1" label="男" />
          <el-option value="2" label="女" />
        </el-select>
      </el-form-item>
      <el-form-item label="出生日期" prop="birthDate">
        <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="证件介质" prop="identifierType">
        <el-select v-model="form.identifierType">
          <el-option v-for="m in mediumOptions" :key="m.value" :value="m.value" :label="m.label" />
        </el-select>
        <el-button
          class="patient-create-reader"
          :disabled="!readerAvailable"
          @click="onReaderClick"
        >
          {{ readerAvailable ? '读卡' : '读卡器未接入（手工录入）' }}
        </el-button>
      </el-form-item>
      <el-form-item label="证件号" prop="idCardNo">
        <el-input v-model="form.idCardNo" autocomplete="off" />
      </el-form-item>
      <el-form-item label="手机号" prop="mobile">
        <el-input v-model="form.mobile" autocomplete="off" />
      </el-form-item>
      <el-form-item label="住址" prop="address">
        <el-input v-model="form.address" />
      </el-form-item>
      <el-form-item label="建档渠道" prop="registerChannel">
        <el-select v-model="form.registerChannel">
          <el-option value="WINDOW" label="窗口" />
          <el-option value="SELF_SERVICE" label="自助机" />
          <el-option value="ONLINE" label="线上" />
          <el-option value="INPATIENT_REGISTER" label="住院登记" />
          <el-option value="EMERGENCY" label="急诊" />
        </el-select>
      </el-form-item>
      <el-form-item label="档案来源" prop="archiveSource">
        <el-select v-model="form.archiveSource">
          <el-option value="STANDARD" label="正式档案" />
          <el-option value="TEMP_ANONYMOUS" label="急诊无名氏（临时）" />
          <el-option value="TEMP_NEWBORN" label="新生儿（临时）" />
        </el-select>
        <span v-if="isEmergency" class="patient-create-tip"
          >临时档案标记未实名，取得身份后转正式</span
        >
      </el-form-item>
      <el-form-item label="知情同意凭证" prop="informedConsentRef">
        <el-input v-model="form.informedConsentRef" placeholder="纸质凭证编号 / 电子签名引用" />
      </el-form-item>
      <el-form-item>
        <el-button :loading="prechecking" @click="handlePrecheck">匹配预检</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">建档</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2） */
.patient-create {
  max-width: 720px;
}

.patient-create-check {
  margin-bottom: 12px;
}

.patient-create-reader {
  margin-left: 12px;
}

.patient-create-tip {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
