<script setup lang="ts">
// 药品字典页（FU-M06-01/02）：检索条（keyword/基药/对照态）→ 启用面药品表格 →
// 建档/变更对话框（DrugSaveRequest）→ 医保对照对话框（nhsaCode/catalogVersion/payType）；
// 未对照药品渲染「不可医保结算」标记，对照成功后 insuredSettleable 翻转标记消失。
// 弹错归响应拦截器（web A.3-2）；失败驻留旧结果。
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
// ElMessage 在模板外使用，按需样式需手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import { createDrug, mapInsurance, searchDrugs, updateDrug } from '@/api/pharmacy';
import type { DrugVO } from '@/api/pharmacy';

/** 检索关键词（名称/拼音/医保码模糊） */
const keyword = ref('');
/** 基药过滤（undefined=不过滤） */
const essential = ref<boolean | undefined>(undefined);
/** 医保对照过滤（undefined=不过滤） */
const insuranceMapped = ref<boolean | undefined>(undefined);

/** 药品行集（检索结果，仅启用面） */
const rows = ref<DrugVO[]>([]);
const loading = ref(false);

/** 加载药品检索（默认启用面，建档/变更/对照成功后重刷）。 */
async function loadDrugs(): Promise<void> {
  loading.value = true;
  try {
    const page = await searchDrugs({
      keyword: keyword.value.trim() === '' ? undefined : keyword.value.trim(),
      essential: essential.value,
      insuranceMapped: insuranceMapped.value,
    });
    rows.value = page.content;
  } catch {
    // 失败弹错归响应拦截器；驻留旧结果
  } finally {
    loading.value = false;
  }
}

/** 检索按钮：同 loadDrugs（空关键词=全启用面检索，后端分页兜底）。 */
async function handleSearch(): Promise<void> {
  await loadDrugs();
}

/** 建档/变更弹窗状态（editId 空=建档，非空=变更该行） */
const saveVisible = ref(false);
const saveSubmitting = ref(false);
/** 建档/变更表单实例（声明式 :rules 校验入口，提交时全量 validate） */
const saveFormRef = ref<FormInstance>();
/** 变更目标行 id（string 承载雪花 ID；空串=建档模式） */
const editId = ref('');

/** 建档/变更表单（DrugSaveRequest 必填四字段+常用档案字段；数量/比率 string 承载） */
const saveForm = reactive<{
  drugCode: string;
  genericName: string;
  tradeName: string;
  specification: string;
  manufacturer: string;
  unit: string;
  essentialFlag: boolean;
  antibioClass: string;
  hazardLevel: string;
  narcoticClass: string;
  skinTestFlag: boolean;
}>({
  drugCode: '',
  genericName: '',
  tradeName: '',
  specification: '',
  manufacturer: '',
  unit: '',
  essentialFlag: false,
  antibioClass: '',
  hazardLevel: '',
  narcoticClass: '',
  skinTestFlag: false,
});

/**
 * 建档/变更表单声明式必填规则（§4.4 表单规范：错误就近字段显示，替代提交函数 if 散写）；
 * required 只查空串不查纯空格，trim 兜底仍保留在提交函数作最终防线。
 */
const saveRules: FormRules = {
  drugCode: [{ required: true, message: '药码必填', trigger: 'blur' }],
  genericName: [{ required: true, message: '通用名必填', trigger: 'blur' }],
  antibioClass: [{ required: true, message: '抗菌药分级必填', trigger: 'blur' }],
  hazardLevel: [{ required: true, message: '危险级必填', trigger: 'blur' }],
  narcoticClass: [{ required: true, message: '麻精分级必填', trigger: 'blur' }],
};

/** 打开建档弹窗（表单清零）。 */
function openCreate(): void {
  editId.value = '';
  saveForm.drugCode = '';
  saveForm.genericName = '';
  saveForm.tradeName = '';
  saveForm.specification = '';
  saveForm.manufacturer = '';
  saveForm.unit = '';
  saveForm.essentialFlag = false;
  saveForm.antibioClass = '';
  saveForm.hazardLevel = '';
  saveForm.narcoticClass = '';
  saveForm.skinTestFlag = false;
  saveVisible.value = true;
}

/**
 * 打开变更弹窗（以行数据预填）。
 *
 * @param row 目标药品行
 */
function openEdit(row: DrugVO): void {
  editId.value = String(row.id ?? '');
  saveForm.drugCode = row.drugCode ?? '';
  saveForm.genericName = row.genericName ?? '';
  saveForm.tradeName = row.tradeName ?? '';
  saveForm.specification = row.specification ?? '';
  saveForm.manufacturer = row.manufacturer ?? '';
  saveForm.unit = row.unit ?? '';
  saveForm.essentialFlag = row.essentialFlag ?? false;
  saveForm.antibioClass = row.antibioClass ?? '';
  saveForm.hazardLevel = row.hazardLevel ?? '';
  saveForm.narcoticClass = row.narcoticClass ?? '';
  saveForm.skinTestFlag = row.skinTestFlag ?? false;
  saveVisible.value = true;
}

/**
 * 提交建档/变更：:rules 声明式校验（错误就近字段显示）→ trim 最终防线兜纯空格 →
 * 成功后关窗重刷。入口在途早退守卫：saveSubmitting 置位到 Vue 重渲染存在间隙，
 * 重渲染前到达的第二击在入口即被拦截。
 */
async function submitSave(): Promise<void> {
  if (saveSubmitting.value) {
    return;
  }
  // 声明式必填校验未过：字段级错误就近显示，不出网不弹全局提示
  try {
    await saveFormRef.value?.validate();
  } catch {
    return;
  }
  // trim 最终防线：required 对纯空格放行，此处兜底防全空格录入入网
  if (
    saveForm.drugCode.trim() === '' ||
    saveForm.genericName.trim() === '' ||
    saveForm.antibioClass.trim() === '' ||
    saveForm.hazardLevel.trim() === '' ||
    saveForm.narcoticClass.trim() === ''
  ) {
    void ElMessage.warning('药码/通用名/抗菌药分级/危险级/麻精分级必填');
    return;
  }
  saveSubmitting.value = true;
  try {
    if (editId.value === '') {
      await createDrug({ ...saveForm });
      void ElMessage.success('药品建档完成');
    } else {
      await updateDrug(editId.value, { ...saveForm });
      void ElMessage.success('药品档案已变更');
    }
    saveVisible.value = false;
    await loadDrugs();
  } catch {
    // 失败弹错归响应拦截器；弹窗驻留防录入丢失
  } finally {
    saveSubmitting.value = false;
  }
}

/** 医保对照弹窗状态与目标行 */
const mappingVisible = ref(false);
const mappingSubmitting = ref(false);
/** 对照表单实例（声明式 :rules 校验入口） */
const mappingFormRef = ref<FormInstance>();
/** 对照目标行 id（string 承载雪花 ID） */
const mappingDrugId = ref('');
/** 对照表单（InsuranceMappingRequest 三字段） */
const mappingForm = reactive<{ nhsaCode: string; catalogVersion: string; payType: string }>({
  nhsaCode: '',
  catalogVersion: '',
  payType: '',
});

/** 对照表单声明式必填规则（错误就近字段显示；trim 兜底保留在提交函数） */
const mappingRules: FormRules = {
  nhsaCode: [{ required: true, message: '医保编码必填', trigger: 'blur' }],
  catalogVersion: [{ required: true, message: '目录版本必填', trigger: 'blur' }],
  payType: [{ required: true, message: '支付类别必填', trigger: 'blur' }],
};

/**
 * 打开对照弹窗（已对照行可重对照维护）。
 *
 * @param row 目标药品行
 */
function openMapping(row: DrugVO): void {
  mappingDrugId.value = String(row.id ?? '');
  mappingForm.nhsaCode = row.nhsaCode ?? '';
  mappingForm.catalogVersion = row.nhsaCatalogVersion ?? '';
  mappingForm.payType = row.nhsaPayType ?? '';
  mappingVisible.value = true;
}

/**
 * 提交医保对照：:rules 声明式校验 → trim 最终防线，成功后关窗重刷（对照后 insuredSettleable 翻转）。
 * 入口在途早退守卫：mappingSubmitting 置位到 Vue 重渲染存在间隙，重渲染前到达的第二击在入口即被拦截。
 */
async function submitMapping(): Promise<void> {
  if (mappingSubmitting.value) {
    return;
  }
  // 声明式必填校验未过：字段级错误就近显示，不出网不弹全局提示
  try {
    await mappingFormRef.value?.validate();
  } catch {
    return;
  }
  // trim 最终防线：required 对纯空格放行，此处兜底防全空格录入入网
  if (
    mappingForm.nhsaCode.trim() === '' ||
    mappingForm.catalogVersion.trim() === '' ||
    mappingForm.payType.trim() === ''
  ) {
    void ElMessage.warning('医保编码/目录版本/支付类别必填');
    return;
  }
  mappingSubmitting.value = true;
  try {
    await mapInsurance(mappingDrugId.value, { ...mappingForm });
    void ElMessage.success('医保对照完成');
    mappingVisible.value = false;
    await loadDrugs();
  } catch {
    // 失败弹错归响应拦截器；弹窗驻留防录入丢失
  } finally {
    mappingSubmitting.value = false;
  }
}

onMounted(loadDrugs);
</script>

<template>
  <!-- 检索卡进场 rise（§6.1）：单卡页 stagger 承载（动画属性仅 opacity+transform） -->
  <div class="fuy-page fuy-stagger">
    <!-- fuy-dense 挂外层卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为
         后代选择器 .fuy-dense .el-table，挂表格自身不构成后代关系、零生效（批次 2 R1 教训）；
         表格 size="small" 移除——fuy-dense 唯一密度通道（§9.9-2） -->
    <el-card class="fuy-dense">
      <template #header>药品字典</template>
      <div class="fuy-toolbar">
        <el-input
          v-model="keyword"
          placeholder="名称/拼音/医保码"
          class="drug-dict-input"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="essential" placeholder="基药" class="drug-dict-select" clearable>
          <el-option label="基药" :value="true" />
          <el-option label="非基药" :value="false" />
        </el-select>
        <el-select
          v-model="insuranceMapped"
          placeholder="医保对照"
          class="drug-dict-select"
          clearable
        >
          <el-option label="已对照" :value="true" />
          <el-option label="未对照" :value="false" />
        </el-select>
        <el-button :loading="loading" @click="handleSearch">检索</el-button>
        <el-button type="primary" @click="openCreate">药品建档</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" class="drug-dict-table">
        <el-table-column prop="drugCode" label="药码" min-width="100" />
        <el-table-column prop="genericName" label="通用名" min-width="140" />
        <el-table-column prop="specification" label="规格" min-width="120" />
        <el-table-column prop="manufacturer" label="厂家" min-width="140" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column prop="nhsaCode" label="医保编码" min-width="130">
          <template #default="{ row }">{{ row.nhsaCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="医保状态" width="120">
          <template #default="{ row }">
            <!-- 医保对照态可视面：warning/success 文字色经 .fuy-tag-aa 修正至 4.5:1（§4.3） -->
            <el-tag v-if="!row.insuredSettleable" type="warning" class="fuy-tag-aa"
              >不可医保结算</el-tag
            >
            <el-tag v-else type="success" class="fuy-tag-aa">可医保结算</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">变更</el-button>
            <el-button link type="primary" @click="openMapping(row)">医保对照</el-button>
          </template>
        </el-table-column>
        <!-- 检索空结果业务口径提示（挂载即检索，初始与查无结果同语义，无需 searched 门控） -->
        <template #empty>
          <el-empty :image-size="72" description="未检索到匹配药品" />
        </template>
      </el-table>
    </el-card>

    <!-- 建档/变更弹窗（editId 空=建档）；11 字段两分节（§9.4-⑦）：基础档案 + 管控属性；
         label-width 96px 系 §4.4 统一口径 -->
    <el-dialog v-model="saveVisible" :title="editId === '' ? '药品建档' : '药品变更'" width="520px">
      <el-form ref="saveFormRef" :model="saveForm" :rules="saveRules" label-width="96px">
        <h4 class="fuy-section-title">基础档案</h4>
        <el-form-item label="药码" prop="drugCode">
          <el-input v-model="saveForm.drugCode" placeholder="院内药品编码" />
        </el-form-item>
        <el-form-item label="通用名" prop="genericName">
          <el-input v-model="saveForm.genericName" placeholder="通用名" />
        </el-form-item>
        <el-form-item label="商品名">
          <el-input v-model="saveForm.tradeName" placeholder="商品名（可空）" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="saveForm.specification" placeholder="如 0.25g*24片（可空）" />
        </el-form-item>
        <el-form-item label="厂家">
          <el-input v-model="saveForm.manufacturer" placeholder="生产厂家（可空）" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="saveForm.unit" placeholder="如 盒（可空）" />
        </el-form-item>
        <el-form-item label="基本药物">
          <el-switch v-model="saveForm.essentialFlag" />
        </el-form-item>
        <h4 class="fuy-section-title">管控属性</h4>
        <el-form-item label="抗菌药分级" prop="antibioClass">
          <el-input v-model="saveForm.antibioClass" placeholder="非抗菌填 NONE" />
        </el-form-item>
        <el-form-item label="危险级" prop="hazardLevel">
          <el-input v-model="saveForm.hazardLevel" placeholder="高危药分级，普通填 NONE" />
        </el-form-item>
        <el-form-item label="麻精分级" prop="narcoticClass">
          <el-input v-model="saveForm.narcoticClass" placeholder="非麻精填 NONE" />
        </el-form-item>
        <el-form-item label="皮试药">
          <el-switch v-model="saveForm.skinTestFlag" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saveSubmitting" @click="submitSave">
          {{ editId === '' ? '确认建档' : '确认变更' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 医保对照弹窗（三字段；对照后 insuredSettleable 翻转） -->
    <el-dialog v-model="mappingVisible" title="医保编码对照" width="420px">
      <el-form ref="mappingFormRef" :model="mappingForm" :rules="mappingRules" label-width="96px">
        <el-form-item label="医保编码" prop="nhsaCode">
          <el-input v-model="mappingForm.nhsaCode" placeholder="国家医保药品编码" />
        </el-form-item>
        <el-form-item label="目录版本" prop="catalogVersion">
          <el-input v-model="mappingForm.catalogVersion" placeholder="如 2024A" />
        </el-form-item>
        <el-form-item label="支付类别" prop="payType">
          <el-input v-model="mappingForm.payType" placeholder="如 甲类/乙类" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mappingVisible = false">取消</el-button>
        <el-button type="primary" :loading="mappingSubmitting" @click="submitMapping"
          >确认对照</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：工具条已收编 .fuy-toolbar（§9.2.2），卡宽随 .fuy-page
   全宽（列表 1080 上限撤销），本块只留 input/select 宽度与表格区 CLS 锁定 */
.drug-dict-input {
  max-width: 220px;
}

.drug-dict-select {
  max-width: 130px;
}

/* 检索表容器 min-height：加载/空态切换零塌陷（§7.1 CLS 锁定，批次 2 检索页同款） */
.drug-dict-table {
  min-height: 240px;
}
</style>
