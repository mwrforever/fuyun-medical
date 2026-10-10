<script setup lang="ts">
// 患者建档页（FU-M02-01 · 暖纸卷宗换脸，蓝图 P01）：建档表单（实名制必填口径，11 字段按
// 身份基础/证件介质/建档属性三分节，节间「签」字分隔）+ 右栏常驻预检叙事卡（金虚缝装订：
// 「先预检防重」的因果在版式上常驻可见，未预检给引导空态脸非报错）+ 读卡器占位按钮；
// 建档成功 ElMessage 反馈后跳详情（消除静默跳转）；弹错归响应拦截器（web A.3-2）。
// 录入中离开路由守卫（EX-46/FE-A2-07）：表单偏离建档缺省态即视为草稿，离开前确认防误触
// 导航丢录入；建档成功跳详情为既定流程，放行不确认。
// 数据契约 / 校验规则 / 提交逻辑零变动（换脸不换业务）：布局重排自 el-card 单列升格为
// 门牌页首 + minmax(0,600px)/320px 双栏（预检结论从表单上方一行升格右栏三态区）。
import { computed, reactive, ref, useTemplateRef } from 'vue';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在模板外使用，按需样式需手动引入（与 api/http.ts 同款口径，F-1 缺口补引）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { createPatient, matchCheck } from '@/api/patient';
import type { MatchCheckVO, PatientCreateRequest } from '@/api/patient';
import {
  patientArchiveSourceOptions,
  patientRegisterChannelOptions,
  patientRegisterChannelText,
} from '@/utils/patientDisplay';

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
/** 预检结论（右栏预检卡三态区展示） */
const checkResult = ref<MatchCheckVO | null>(null);

/** 急诊无名氏快捷态：档案来源切换时联动提示 */
const isEmergency = computed(() => form.archiveSource === 'TEMP_ANONYMOUS');

/**
 * 当日时刻批注（YYYY-MM-DD 周X）：文书页眉日期语法，本地时钟零出网（HomeView 同构）。
 * 取「YYYY-MM-DD」ISO 形态 + 中文星期单字（批注行紧凑可读）。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 门牌批注行建档渠道文案：随表单渠道选择联动（patientDisplay 词表单源）；空值 — 占位禁伪数据 */
const channelLabel = computed(() => patientRegisterChannelText(form.registerChannel) || '—');

/** 预检结论三态展示形态（蓝图 P01：结论落右栏三态区）。双通道铁律：胶囊文字恒在场，
 * 语义色仅第二通道；色档沿用既有 el-alert 口径——AUTO_MATCH/SUSPECT 警示琥珀、
 * NO_MATCH 确认绿；候选档案 ID 缺失以 — 占位禁伪数据 */
const verdict = computed(() => {
  const result = checkResult.value;
  if (result === null) {
    return null;
  }
  if (result.outcome === 'AUTO_MATCH') {
    return {
      toneClass: 'fuy-status-pill--amber',
      headline: '匹配到既有档案',
      note: `档案 ID ${result.candidatePatientId ?? '—'} · 提交后将归一至该档案`,
    };
  }
  if (result.outcome === 'SUSPECT') {
    return {
      toneClass: 'fuy-status-pill--amber',
      headline: '疑似重复',
      note: '提交后将转人工核对（生成疑似重复待审）',
    };
  }
  return {
    toneClass: 'fuy-status-pill--success',
    headline: '未匹配到既有档案',
    note: '将建立新档案',
  };
});

/** 打开页面时的表单缺省态快照（离开守卫脏判据：任一字段偏离即视为录入中草稿） */
const pristineFormSnapshot = JSON.stringify(form);
/** 建档成功放行标记：成功跳详情为既定流程，离开守卫不确认直接放行 */
let leavePassGranted = false;

/**
 * 录入中离开路由守卫（EX-46/FE-A2-07）：建档表单有未提交录入时，路由离开（站内导航）
 * 前确认——误触侧栏/返回不静默丢失录入内容；未录入或已建档成功则零打扰放行。
 *
 * @return true=放行离开；false=取消导航留在本页
 */
onBeforeRouteLeave(async () => {
  if (leavePassGranted || JSON.stringify(form) === pristineFormSnapshot) {
    return true;
  }
  try {
    await ElMessageBox.confirm('建档表单尚未提交，离开将丢失已录入内容', '未保存提醒', {
      type: 'warning',
      confirmButtonText: '确认离开',
      cancelButtonText: '留在此页',
    });
    return true;
  } catch {
    // 用户选择留页：中断导航，草稿驻留表单
    return false;
  }
});

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
    // 建档成功跳详情为既定流程：先放行离开守卫再导航，成功跳转不触发未保存确认
    leavePassGranted = true;
    const failure = await router.push(`/patients/${patientId}`);
    // 导航被其他守卫/重定向中止时 push 以 NavigationFailure 结算（不抛错、未真正离开）：
    // 回置放行标记，防标志残留令后续手动离开静默绕过未保存确认（守卫旁路失效）
    if (failure) {
      leavePassGranted = false;
    }
  } catch {
    // push 异常结算同样回置放行标记（防守卫被旁路）；失败弹错归响应拦截器，表单驻留防数据丢失
    leavePassGranted = false;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="fuy-page">
    <!-- 页内级联容器（非页面根：路由进场过渡归 MainLayout，页根不挂 stagger 防双重节奏）：
         门牌页首 0 / 表单卡 1 / 预检卡 2 三档 40ms 级联 -->
    <div class="create-layout fuy-stagger">
      <!-- 门牌页首：衬线标题 +「建档渠道 · 当日时刻」批注行（渠道=表单业务真值联动、
           时刻=本地时钟，空值 — 占位禁伪数据），2px 墨规收底走 .fuy-page-head 全局脸；
           时刻批注以 <time> 语义元素承载（与检索页/首页门牌同律，患者域三页横向一致）；
           stagger 首档显式声明 0（三页同律可审计，级联序在模板自证） -->
      <header class="fuy-page-head create-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">患者建档</h1>
        </div>
        <p class="fuy-page-note">
          建档渠道 {{ channelLabel }} · <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- 主工作区=建档表单卡：三分节（身份基础/证件介质/建档属性）既有口径，节间「签」字分隔；
           整表单挂 .fuy-form 承全局脸（label 疏排/聚焦墨环+朱砂下划/错误印泥朱显影） -->
      <section
        class="fuy-card create-form-card"
        style="--fuy-stagger-index: 1"
        aria-label="建档表单"
      >
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">建档表单</h2>
        </header>
        <div class="fuy-card-body">
          <el-form ref="formRef" class="fuy-form" :model="form" :rules="rules" label-width="140px">
            <div class="fuy-section-title create-section-first">身份基础</div>
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="急诊无名氏录「无名氏」" />
            </el-form-item>
            <el-form-item label="性别" prop="sex">
              <el-select v-model="form.sex" placeholder="请选择" popper-class="fuy-snap-popper">
                <el-option value="1" label="男" />
                <el-option value="2" label="女" />
              </el-select>
            </el-form-item>
            <el-form-item label="出生日期" prop="birthDate">
              <el-date-picker
                v-model="form.birthDate"
                type="date"
                value-format="YYYY-MM-DD"
                popper-class="fuy-snap-popper"
              />
            </el-form-item>

            <div class="fuy-sign-divider" aria-hidden="true"></div>
            <div class="fuy-section-title">证件介质</div>
            <el-form-item label="证件介质" prop="identifierType">
              <el-select v-model="form.identifierType" popper-class="fuy-snap-popper">
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

            <div class="fuy-sign-divider" aria-hidden="true"></div>
            <div class="fuy-section-title">建档属性</div>
            <el-form-item label="建档渠道" prop="registerChannel">
              <!-- 选项经 patientDisplay 词表单源派生（批次 2 移交打磨项：与详情页文案同源零双份） -->
              <el-select v-model="form.registerChannel" popper-class="fuy-snap-popper">
                <el-option
                  v-for="channel in patientRegisterChannelOptions"
                  :key="channel.value"
                  :value="channel.value"
                  :label="channel.label"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="档案来源" prop="archiveSource">
              <el-select v-model="form.archiveSource" popper-class="fuy-snap-popper">
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
              <el-input
                v-model="form.informedConsentRef"
                placeholder="纸质凭证编号 / 电子签名引用"
              />
            </el-form-item>
            <el-form-item class="create-actions">
              <!-- 建档双入口（PR-4F #1）：主操作钮墨实底右挂、预检钮迁右栏预检卡（先预检
                   防重因果可见）——权限决定在不在 DOM，loading/校验等数据态决定可不可点，
                   两者正交叠加互不覆盖 -->
              <el-button
                v-perm="'patient:archive:btn:create'"
                type="primary"
                :loading="submitting"
                @click="handleSubmit"
                >建档</el-button
              >
            </el-form-item>
          </el-form>
        </div>
      </section>

      <!-- 右辅栏=预检叙事卡（金虚缝装订 sticky 常驻）：预检钮 + 结论三态区——防重复建档
           的因果随滚动不失焦；结论区 min-height 200 锁 CLS（空态/三态切换零塌陷） -->
      <aside
        class="fuy-card fuy-card--stitch create-check-card"
        style="--fuy-stagger-index: 2"
        aria-label="建档预检"
      >
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">建档预检</h2>
        </header>
        <div class="fuy-card-body">
          <p class="create-check-intro">提交建档前先比对全院既有档案，防止重复建档。</p>
          <el-button
            v-perm="'patient:archive:btn:create'"
            class="create-check-btn"
            :loading="prechecking"
            @click="handlePrecheck"
            >匹配预检</el-button
          >
          <!-- 预检结论显隐走内容过渡（§6.7）：结论出现 200ms 淡入，离场瞬切不做交叉溶解 -->
          <div class="create-check-stage">
            <Transition name="fuy-content-fade">
              <div
                v-if="verdict !== null"
                key="check-verdict"
                class="create-check-verdict"
                role="status"
              >
                <span class="fuy-status-pill" :class="verdict.toneClass">{{
                  verdict.headline
                }}</span>
                <p class="create-check-note">{{ verdict.note }}</p>
              </div>
              <div v-else key="check-guide" class="fuy-empty create-check-guide" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无预检结论</p>
                <p class="fuy-empty-hint">录入姓名、证件号等身份要素后先执行预检，再提交建档。</p>
              </div>
            </Transition>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：暖纸卷宗换脸（蓝图 P01）——门牌页首/双栏骨架/预检叙事卡。
   页面骨架与组件脸由 .fuy-page/.fuy-page-head/.fuy-card/.fuy-form/.fuy-empty/.fuy-status-pill/
   .fuy-sign-divider 全局类承载（element-plus.css 基础册），本块只留双栏布局与卡内私有间距；
   字号间距颜色全部 token 化，零新色值、零新增 @keyframes */

/* 双栏骨架（蓝图 P01-3）：minmax(0,600px) 表单主列 + 320px 预检辅列；列间 gap 16 为蓝图
   规定档，行间 gap 归 12（--fuy-space-3 工作面纵向节奏阶，与检索/详情两页同律——
   .fuy-page 全局纵向 gap 即 12，页内块间距循同一阶）；
   align-items start 使右卡按内容收缩，sticky 滚动行程才成立 */
.create-layout {
  display: grid;
  grid-template-columns: minmax(0, 600px) 320px;
  gap: var(--fuy-space-3) var(--fuy-space-4);
  align-items: start;
}

/* 门牌页首横跨双栏（页首属整页，不属于任何一栏） */
.create-head {
  grid-column: 1 / -1;
}

/* 表单主列卡：minmax(0,…) 防长值撑破网格 */
.create-form-card {
  min-width: 0;
}

/* 首节题贴卡头：全局 .fuy-section-title 预留 16px 上距，卡头发丝与首节题间不双倍留白 */
.create-section-first {
  margin-top: 0;
}

/* 「签」字分隔与上下节的呼吸间距（卡内文书分界，禁石规下沉） */
.fuy-sign-divider {
  margin: var(--fuy-space-4) 0;
}

/* 建档主操作钮：表单尾部右挂（墨实底走 EP primary 映射自动承继，「墨即操作」） */
.create-actions :deep(.el-form-item__content) {
  justify-content: flex-end;
}

/* 预检叙事卡（右辅列）：sticky top 16 常驻视口——先预检防重的因果随滚动不失焦 */
.create-check-card {
  position: sticky;
  top: var(--fuy-space-4);
  min-width: 0;
}

/* 预检卡引言：一句业务动机（为什么先预检），灰墨不抢结论 */
.create-check-intro {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  line-height: 1.5;
  color: var(--fuy-color-text-secondary);
}

/* 预检钮：卡内通栏（320px 窄列的稳定点击目标） */
.create-check-btn {
  width: 100%;
}

/* 结论三态区（蓝图 P01-3/6）：min-height 200 锁 CLS——引导空态与三态结论切换零塌陷；
   纵向 flex 令空态脸撑满预留区 */
.create-check-stage {
  display: flex;
  flex-direction: column;
  min-height: 200px;
  margin-top: var(--fuy-space-3);
}

/* 结论块：胶囊 + 说明双通道（状态色永不单独承义），发丝围合、版面内零投影 */
.create-check-verdict {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
}

.create-check-note {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  line-height: 1.5;
  color: var(--fuy-color-text-emphasis);
  overflow-wrap: anywhere;
}

/* 引导空态撑满结论预留区（.fuy-empty 全局脸：石规虚缝 + 书法「空」装饰字 + 下一步指引） */
.create-check-guide {
  flex: 1;
  justify-content: center;
}

/* 既有私有位（业务零变动随新骨架保留）：读卡器占位按钮与急诊无名氏提示 */
.patient-create-reader {
  margin-left: var(--fuy-space-3);
}

.patient-create-tip {
  margin-left: var(--fuy-space-3);
  color: var(--el-text-color-secondary);
  font-size: var(--fuy-font-size-xs);
}
</style>
