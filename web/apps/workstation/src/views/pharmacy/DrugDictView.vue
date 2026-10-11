<script setup lang="ts">
// 药品字典页（FU-M06-01/02 · 暖纸卷宗换脸重排，逐页蓝图 P10）：检索条（keyword/基药/对照态）
// → 启用面药品表格 → 建档/变更对话框（DrugSaveRequest）→ 医保对照对话框（nhsaCode/
// catalogVersion/payType）；未对照药品渲染「不可医保结算」标记，对照成功后 insuredSettleable
// 翻转标记消失。弹错归响应拦截器（web A.3-2）；失败驻留旧结果。
// 构图（蓝图 P10「换脸不换业务」）：门牌页首（衬线标题 + 签认人·时刻批注行，原 el-card #header
// 页面名升格）→ 筛选卡（.fuy-filter，检索次钮留控件序列、药品建档墨实底主钮右挂 .fuy-filter-
// actions 分主次）→ 主表卡（.fuy-card + .fuy-dense 密排，列序按扫读频率重排：通用名首列、
// 药码次之）；建档/变更与医保对照两弹窗挂 .fuy-dialog + .fuy-form，显式 popper 挂
// fuy-snap-popper；空态走 .fuy-empty 脸（契约 ⑥ 禁纸箱插画）。
// 数据契约零变动：searchDrugs/createDrug/updateDrug/mapInsurance 出网与医保映射逻辑原样；
// 检索经 usePagedList 分页化收口（EX-49 范式迁移，pageSize 50——searchDrugs 契约既有可选参，
// 替换「不传 page/size 后端兜底」的全量渲染隐患，行为与迁移前一致：失败弹错归拦截器、驻留旧结果）。
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
// ElMessage/ElMessageBox 在模板外使用，按需样式手动引入（与 billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { createDrug, mapInsurance, searchDrugs, updateDrug } from '@/api/pharmacy';
import type { DrugVO } from '@/api/pharmacy';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';

/** 检索关键词（名称/拼音/医保码模糊） */
const keyword = ref('');
/** 基药过滤（undefined=不过滤） */
const essential = ref<boolean | undefined>(undefined);
/** 医保对照过滤（undefined=不过滤） */
const insuranceMapped = ref<boolean | undefined>(undefined);

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

/**
 * 药品分页检索（EX-49 范式迁移）：页码/行集/总数/加载态经 usePagedList 收拢（默认启用面，
 * 建档/变更/对照成功后重刷），筛选三参归本页持有经快照工厂实时取值合并出网（keyword trim
 * 归一与迁移前逐字一致；后端 0 基缺省分页兜底不再消费——本页显式 50/页出网）。
 */
const { rows, total, loading, currentPage, pageSize, search, goToPage, fetch } = usePagedList({
  params: () => ({
    keyword: keyword.value.trim() === '' ? undefined : keyword.value.trim(),
    essential: essential.value,
    insuranceMapped: insuranceMapped.value,
  }),
  fetcher: (query) => searchDrugs({ ...query }),
  pageSize: 50,
});

/** 检索按钮/回车：换条件回第一页重查（空关键词=全启用面检索）。 */
async function handleSearch(): Promise<void> {
  await search();
}

/**
 * 翻页：分页组件回传 1 基页码，usePagedList 内部转契约 0 基出网。
 *
 * @param page 目标页码（1 基）
 */
async function handlePageChange(page: number): Promise<void> {
  await goToPage(page);
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
 * 变更路径整单覆盖确认（EX-46/FE-A2-09）：变更=PUT 整单档案面回写，管控属性（抗菌药
 * 分级/危险级/麻精分级/皮试）随表单快照整单覆盖——契约无版本字段（DrugVO 无 updatedAt、
 * PUT 无 If-Match）做不了乐观锁，「仅提交变更面」也被必填校验（管控属性三分级 @NotBlank）
 * 封死；最小防护=出网前二次确认，操作者显式核对后放行（取消零出网弹窗驻留）。
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
      // 变更整单覆盖二次确认（EX-46/FE-A2-09）：确认文案点名管控属性，防读改写窗口内
      // 无感覆盖他人变更；取消则弹窗与录入驻留零出网
      try {
        await ElMessageBox.confirm(
          `即将整单覆盖药品档案「${saveForm.genericName.trim()}」（含抗菌药分级/危险级/麻精分级等管控属性），请确认基于最新档案操作`,
          '变更覆盖确认',
          { type: 'warning', confirmButtonText: '确认覆盖', cancelButtonText: '取消' },
        );
      } catch {
        return;
      }
      await updateDrug(editId.value, { ...saveForm });
      void ElMessage.success('药品档案已变更');
    }
    saveVisible.value = false;
    // 成功重刷当前页（usePagedList fetch：页码态保持，行集/总数收拢同出口）
    await fetch();
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
    await fetch();
  } catch {
    // 失败弹错归响应拦截器；弹窗驻留防录入丢失
  } finally {
    mappingSubmitting.value = false;
  }
}

onMounted(fetch);
</script>

<template>
  <!-- 门牌页首不入级联（批次内 P08 同款，契约 ⑦.4 stagger 挂页内区块不挂根）；蓝图 P10.4
       「stagger 单卡」=本页唯一级联档为主表卡，门牌与筛选卡静态进场 -->
  <section class="fuy-page">
    <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 签认人·时刻批注行 + 2px 墨规收底（脸样式归全局
         .fuy-page-head 族，本页零私有标题样式）；原 el-card #header「药品字典」升格于此 -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">药品字典</h1>
      </div>
      <p class="fuy-page-note">
        签认人 {{ signerName }} · <time>{{ todayLabel }}</time>
      </p>
    </header>

    <!-- 筛选卡（契约 ⑧.3）：检索动作与建档主钮分主次——检索次钮留筛选控件序列，药品建档
         墨实底主钮（EP primary 墨实底经换血自动承继）右挂 .fuy-filter-actions；fuy-filter
         承载 flex 布局、fuy-card-body 承载卡内边距，同类并存各司其职 -->
    <section class="fuy-card">
      <div class="fuy-card-body fuy-filter">
        <label for="drug-dict-keyword">关键词</label>
        <el-input
          id="drug-dict-keyword"
          v-model="keyword"
          placeholder="名称/拼音/医保码"
          class="drug-dict-input"
          clearable
          @keyup.enter="handleSearch"
        />
        <!-- 显式 popper 统一挂 fuy-snap-popper（契约 ⑤#5/⑦.5 硬 snap） -->
        <el-select
          v-model="essential"
          placeholder="基药"
          class="drug-dict-select"
          clearable
          popper-class="fuy-snap-popper"
        >
          <el-option label="基药" :value="true" />
          <el-option label="非基药" :value="false" />
        </el-select>
        <el-select
          v-model="insuranceMapped"
          placeholder="医保对照"
          class="drug-dict-select"
          clearable
          popper-class="fuy-snap-popper"
        >
          <el-option label="已对照" :value="true" />
          <el-option label="未对照" :value="false" />
        </el-select>
        <el-button :loading="loading" @click="handleSearch">检索</el-button>
        <!-- 药品建档（PR-4F #22，PHARMACIST 绑定）v-perm 直挂——无码 DOM 移除（D-34）；
             建档/变更弹窗提交按钮随入口同码免挂接（入口隐藏即弹窗不可达） -->
        <div class="fuy-filter-actions">
          <el-button v-perm="'pharmacy:drug:btn:maintain'" type="primary" @click="openCreate"
            >药品建档</el-button
          >
        </div>
      </div>
    </section>

    <!-- 主表卡（契约 ⑧.2 卷宗卡 + ⑧.4 密排表）：蓝图 P10.4 stagger 单卡——唯一级联档；
         fuy-dense 挂卡容器（§9.4 通用落点「表格容器挂 fuy-dense」）：密度规则为后代选择器
         .fuy-dense .el-table，挂表格自身不构成后代关系、零生效（批次 2 R1 教训）；表格
         size="small" 移除——fuy-dense 唯一密度通道（§9.9-2）；分页右挂经全局
         .fuy-page .el-pagination 规则（契约 ⑤#7）自动承继，激活页墨底纸字随 primary 换血自动 -->
    <div class="fuy-stagger">
      <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-card-body">
          <el-table v-loading="loading" :data="rows" class="drug-dict-table">
            <!-- 列序按扫读频率重排（蓝图 P10.2）：通用名首列、药码次之（重排前药码居首）；
                 药码=药品主号列挂 .fuy-num 等宽（⑧.4/域级一致性，td 经 class-name 落类，
                 scoped 哈希不进入 EP 内部渲染，必须全局类承载） -->
            <el-table-column prop="genericName" label="通用名" min-width="140" />
            <el-table-column prop="drugCode" label="药码" min-width="100" class-name="fuy-num" />
            <el-table-column prop="specification" label="规格" min-width="120" />
            <el-table-column prop="manufacturer" label="厂家" min-width="140" />
            <el-table-column prop="unit" label="单位" width="70" />
            <!-- 医保编码=国家医保药品编码（药码族号码列），与药码列同挂 .fuy-num 等宽
                 （域级一致性「药品/单据主号一律 .fuy-num」）；空值 — 占位既有 -->
            <el-table-column prop="nhsaCode" label="医保编码" min-width="130" class-name="fuy-num">
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
                <!-- 变更（PR-4F #22）与建档同码 v-perm 直挂；医保对照（PR-4F #23）ADMIN 专属码
                     直挂——ADMIN 会话经权限全表导出含码可见，PHARMACIST 等业务角色无码隐藏 -->
                <el-button
                  v-perm="'pharmacy:drug:btn:maintain'"
                  link
                  type="primary"
                  @click="openEdit(row)"
                  >变更</el-button
                >
                <el-button
                  v-perm="'pharmacy:drug:btn:insurance-mapping'"
                  link
                  type="primary"
                  @click="openMapping(row)"
                  >医保对照</el-button
                >
              </template>
            </el-table-column>
            <!-- 检索空结果业务口径提示（挂载即检索，初始与查无结果同语义，无需 searched 门控）：
                 空态走 .fuy-empty 脸（契约 ⑥ 禁纸箱插画，主句合「暂无〈业务客体〉」语法+说明给下一步） -->
            <template #empty>
              <div class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无匹配药品</p>
                <p class="fuy-empty-hint">未检索到匹配药品，可调整关键词或筛选条件后重新检索。</p>
              </div>
            </template>
          </el-table>
          <!-- usePagedList 分页化收口（蓝图 P10.6）：total>0 门控既有口径 -->
          <el-pagination
            v-if="total > 0"
            background
            layout="total, prev, pager, next"
            :current-page="currentPage"
            :page-size="pageSize"
            :total="total"
            class="drug-dict-pagination"
            @current-change="handlePageChange"
          />
        </div>
      </section>
    </div>

    <!-- 建档/变更弹窗（editId 空=建档）；11 字段两分节（§9.4-⑦）：基础档案 + 管控属性；
         label-width 96px 系 §4.4 统一口径；挂 .fuy-dialog 弹层脸（卡面底+radius 14+shadow-lg+
         衬线标题，契约 ⑤#9）+ .fuy-form 表单脸（label 疏排/聚焦墨环/错误显影全局承载） -->
    <el-dialog
      v-model="saveVisible"
      :title="editId === '' ? '药品建档' : '药品变更'"
      width="520px"
      class="fuy-dialog"
    >
      <el-form
        ref="saveFormRef"
        :model="saveForm"
        :rules="saveRules"
        label-width="96px"
        class="fuy-form"
      >
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

    <!-- 医保对照弹窗（三字段；对照后 insuredSettleable 翻转）；同挂 .fuy-dialog + .fuy-form -->
    <el-dialog v-model="mappingVisible" title="医保编码对照" width="420px" class="fuy-dialog">
      <el-form
        ref="mappingFormRef"
        :model="mappingForm"
        :rules="mappingRules"
        label-width="96px"
        class="fuy-form"
      >
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
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：门牌页首/筛选脸/卷宗卡/空态脸/分页右挂样式全部由
   element-plus.css 全局挂类承载，本块只留 input/select 宽度与表格区 CLS 锁定 */
.drug-dict-input {
  max-width: 220px;
}

.drug-dict-select {
  max-width: 130px;
}

/* 主表容器 min-height：加载/空态切换零塌陷（§7.1 CLS 锁定，批次 2 检索页同款） */
.drug-dict-table {
  min-height: 240px;
}

/* 分页与表格的纵向间距；justify-content:flex-end 归全局 .fuy-page .el-pagination 规则
   （契约 ⑤#7），不再重复声明 */
.drug-dict-pagination {
  margin-top: var(--fuy-space-3);
}
</style>
