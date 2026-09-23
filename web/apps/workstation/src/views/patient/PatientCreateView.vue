<script setup lang="ts">
// 患者建档页（FU-M02-01）：建档表单（实名制必填口径，11 字段按身份基础/证件介质/建档属性
// 三分节降低扫描成本）+ 介质选择与读卡器占位按钮 + 建档前匹配预检（AUTO_MATCH 归一提示 /
// SUSPECT 待审提示）；建档成功 ElMessage 反馈后跳详情（消除静默跳转）；弹错归响应拦截器（web A.3-2）。
import { computed, reactive, ref, useTemplateRef } from 'vue';
import { useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径，F-1 缺口补引）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { createPatient, matchCheck } from '@/api/patient';
import type { MatchCheckVO, PatientCreateRequest } from '@/api/patient';
import { patientArchiveSourceOptions, patientRegisterChannelOptions } from '@/utils/patientDisplay';

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
  void ElMessageBox.alert('读卡器接口位尚未接入，请手工录入证件信息', '提示', {
    type: 'info',
    // W-26：提示弹窗确认按钮补中文字案（EP 默认英文 OK）
    confirmButtonText: '知道了',
  });
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

/** 提交建档：成功反馈后跳转详情页（candidatePatientId 即档案 id） */
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
    // 成功时刻即时反馈（消除静默跳转）：提示先于路由跳转，详情页挂载后提示仍驻留至自动关闭
    void ElMessage.success('建档完成');
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
  <div class="fuy-page fuy-stagger">
    <el-card class="patient-create">
      <template #header>患者建档</template>
      <!-- 预检结论显隐走内容过渡（§6.7）：结论出现 200ms 淡入，离场瞬切不做交叉溶解 -->
      <Transition name="fuy-content-fade">
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
      </Transition>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="140px">
        <!-- 三分节（§9.4-②）：身份基础 → 证件介质 → 建档属性，降低 11 字段长表单扫描成本 -->
        <div class="fuy-section-title">身份基础</div>
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
        <div class="fuy-section-title">证件介质</div>
        <el-form-item label="证件介质" prop="identifierType">
          <el-select v-model="form.identifierType">
            <el-option
              v-for="m in mediumOptions"
              :key="m.value"
              :value="m.value"
              :label="m.label"
            />
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
        <div class="fuy-section-title">建档属性</div>
        <el-form-item label="建档渠道" prop="registerChannel">
          <!-- 选项经 patientDisplay 词表单源派生（批次 2 移交打磨项：与详情页文案同源零双份） -->
          <el-select v-model="form.registerChannel">
            <el-option
              v-for="channel in patientRegisterChannelOptions"
              :key="channel.value"
              :value="channel.value"
              :label="channel.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="档案来源" prop="archiveSource">
          <el-select v-model="form.archiveSource">
            <el-option
              v-for="source in patientArchiveSourceOptions"
              :key="source.value"
              :value="source.value"
              :label="source.label"
            />
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
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：表单/详情卡宽统一 880 档（建档 720 并入，§9.2.2）；
   分节题已收编 .fuy-section-title，本块只留卡宽与页内元素私有间距 */
.patient-create {
  max-width: 880px;
}

.patient-create-check {
  margin-bottom: var(--fuy-space-3);
}

.patient-create-reader {
  margin-left: var(--fuy-space-3);
}

.patient-create-tip {
  margin-left: var(--fuy-space-3);
  color: var(--el-text-color-secondary);
  font-size: var(--fuy-font-size-xs);
}
</style>
