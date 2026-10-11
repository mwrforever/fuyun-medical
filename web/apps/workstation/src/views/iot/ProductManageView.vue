<script setup lang="ts">
// 产品与物模型管理页（/iot/products，M14 FU-M14-02 前端面，暖纸卷宗重排=丙-1 蓝图）：
// 门牌页首（衬线标题 + IoTDA 产品镜像批注行）+ 左产品列表卷宗卡（同步状态三态徽标）+
// 右产品上架卡（卡头「上架五步」静态步序注记——文书页眉语法非交互 stepper）+ 术语映射
// 编辑弹窗（物模型属性→MDC 指标编码跨品牌归一，指标字典选项预载）+ 命令安全等级登记
// 弹窗（安全级/治疗级标注——FU-M14-09 白名单数据源，治疗级默认禁用）。上架五步流程
// （建产品→定义物模型→映射术语→标注命令等级→设备注册）经步序注记承载。
// 换脸不换业务：全部写操作自带在途守卫（入口早退先于一切 await）+ 结构必填显式校验零出网
// （禁裸 parse）；失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError
// 兜底展示 detail 原文；成功反馈=钤印（已上架/已触发同步/已保存，G2 基线——钤印永不承失败）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { metrics, PRODUCT_SYNC_STATUS_LABELS, products, SAFETY_LEVEL_LABELS } from '@/api/iot';
import type { CommandItem, MappingItem, MetricDictVO, ProductVO } from '@/api/iot';
import { useAuthStore } from '@/stores/auth';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ==================== 门牌页首批注行（「谁 · 时刻」） ==================== */

/** 管理员批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const operatorName = computed(() => auth.user?.displayName ?? '—');

/** 批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 产品列表 ==================== */

/** 页脚数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——G8 只叙事实） */
const loadedAt = ref<string | null>(null);

/** 加载产品列表（IoTDA 本地镜像，按返回序直出）：页码/行集/加载态经 usePagedList 收拢
 * （EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错归响应拦截器；
 * 驻留旧清单；成功分支回填页脚数据时点） */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({}),
  fetcher: ({ page, size }) => products.list({ page, size }),
  pageSize: 50,
  onSuccess: () => {
    loadedAt.value = new Date().toISOString();
  },
});

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const loadedAtLabel = computed(() => (loadedAt.value === null ? '—' : formatTime(loadedAt.value)));

/** 同步状态中文词表反查（状态列徽标） */
function syncStatusLabel(code: string | undefined): string {
  return PRODUCT_SYNC_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 同步状态徽标状态类（fuy-sync-tag--{status} 契约类，色值经语义 token 承载） */
function syncStatusClass(code: string | undefined): string {
  return `fuy-sync-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 同步物模型 ==================== */
const syncingId = ref('');

/** 同步物模型（触发 IoTDA 拉取物模型对齐镜像；失配恢复后徽标随刷新更新）；
 * 成功反馈=钤印「已触发同步」（丙-1 蓝图第 4 节逐字） */
async function onSyncModel(row: ProductVO): Promise<void> {
  if (syncingId.value !== '') {
    return;
  }
  syncingId.value = row.productId ?? '';
  try {
    await products.syncModel(row.productId ?? '');
    showStamp('已触发同步');
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    syncingId.value = '';
  }
}

/* ==================== 产品上架 ==================== */
const creating = ref(false);
const createForm = ref({
  productName: '',
  deviceType: '',
  protocolType: '',
  dataFormat: '',
  manufacturerName: '',
  industry: '',
  description: '',
});

/** 产品上架：结构必填显式校验（名称/设备类型/协议/数据格式，零出网）→ 出网 → 清表单刷新；
 * 成功反馈=钤印「已上架」（域丙钤印族首项，上架动线第 1 步收束） */
async function onCreate(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (createForm.value.productName.trim() === '') {
    void ElMessage.warning('请填写产品名称');
    return;
  }
  if (createForm.value.deviceType.trim() === '') {
    void ElMessage.warning('请填写设备类型');
    return;
  }
  if (createForm.value.protocolType.trim() === '') {
    void ElMessage.warning('请填写协议类型');
    return;
  }
  if (createForm.value.dataFormat.trim() === '') {
    void ElMessage.warning('请填写数据格式');
    return;
  }
  creating.value = true;
  try {
    await products.create({
      productName: createForm.value.productName.trim(),
      deviceType: createForm.value.deviceType.trim(),
      protocolType: createForm.value.protocolType.trim(),
      dataFormat: createForm.value.dataFormat.trim(),
      manufacturerName:
        createForm.value.manufacturerName.trim() === ''
          ? undefined
          : createForm.value.manufacturerName.trim(),
      industry:
        createForm.value.industry.trim() === '' ? undefined : createForm.value.industry.trim(),
      description:
        createForm.value.description.trim() === ''
          ? undefined
          : createForm.value.description.trim(),
      modelDefinitionJson: undefined,
    });
    showStamp('已上架');
    createForm.value = {
      productName: '',
      deviceType: '',
      protocolType: '',
      dataFormat: '',
      manufacturerName: '',
      industry: '',
      description: '',
    };
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ==================== 术语映射编辑弹窗 ==================== */
const mappingVisible = ref(false);
const mappingSaving = ref(false);
/** 弹窗上下文锚点（目标产品） */
const mappingTarget = ref<ProductVO | null>(null);

/**
 * 弹窗编辑行本地序号（稳定键兜底段）：新增空行无后端 id，以「前缀+序号」组合键兜底，
 * 行创建时一次性分配、生命周期内不变。
 */
let draftRowSeq = 0;

/** 生成编辑行本地组合键（无业务 id 行的稳定键兜底） */
function nextDraftRowKey(): string {
  draftRowSeq += 1;
  return `draft-${draftRowSeq}`;
}

/** 行稳定键：既有行取后端 id 业务键（`id-` 前缀隔离命名空间），缺 id 回落本地组合键 */
function draftRowKey(id: string | undefined): string {
  return id !== undefined && id !== '' ? `id-${id}` : nextDraftRowKey();
}

/**
 * 术语映射编辑行（propertyName→metricCode；mismatchStrategy 空串=默认策略不传）。
 * rowKey=行稳定键：v-for 禁 index 键（EX-45/FE-A1-07）——删中间行后剩余行节点原位
 * 保留，焦点/输入态不窜行；rowKey 仅前端行身份，保存时逐字段映射不入出网面。
 */
interface MappingRowDraft {
  rowKey: string;
  propertyName: string;
  metricCode: string;
  mismatchStrategy: string;
}
const mappingRows = ref<MappingRowDraft[]>([]);
/** 指标字典（弹窗打开预载，MDC 编码选项源） */
const metricOptions = ref<MetricDictVO[]>([]);

/** 新增一条空映射行（本地组合键） */
function blankMappingRow(): MappingRowDraft {
  return { rowKey: nextDraftRowKey(), propertyName: '', metricCode: '', mismatchStrategy: '' };
}

/** 打开术语映射弹窗：拉取既有映射回显（PUT 整组替换语义下缺回显会使保存静默清空既有 N 条）
 * 并预载指标字典；两路加载互不拖垮。 */
async function openMapping(row: ProductVO): Promise<void> {
  mappingTarget.value = row;
  mappingRows.value = [blankMappingRow()];
  mappingVisible.value = true;
  try {
    // 既有映射全集回显（BUG-17 修复面）；空配置回落单空行快速录入
    const existing = await products.listMappings(row.productId ?? '');
    mappingRows.value =
      existing.length > 0
        ? existing.map((vo) => ({
            rowKey: draftRowKey(vo.id),
            propertyName: vo.propertyName ?? '',
            metricCode: vo.metricCode ?? '',
            mismatchStrategy: vo.mismatchStrategy ?? '',
          }))
        : [blankMappingRow()];
  } catch {
    // 回显失败弹错归拦截器；驻留空行（整组替换下保存有清空风险，重开弹窗重试回显）
  }
  try {
    metricOptions.value = await metrics.list({});
  } catch {
    // 字典加载失败弹错归拦截器；选项列表置空——指标编码下拉无选项，映射行无法补全（重开弹窗重试）
    metricOptions.value = [];
  }
}

/** 新增一条映射行（弹窗内草稿纯本地态，零出网） */
function addMappingRow(): void {
  mappingRows.value.push(blankMappingRow());
}

/** 删除指定映射行（弹窗内草稿纯本地态，零出网） */
function removeMappingRow(index: number): void {
  mappingRows.value.splice(index, 1);
}

/** 保存映射（PUT 整组替换）：逐行结构必填显式校验零出网 → 出网 → 关窗；
 * 成功反馈=钤印「已保存」（丙-1 蓝图第 4 节逐字）。 */
async function onSaveMappings(): Promise<void> {
  if (mappingSaving.value) {
    return;
  }
  for (const item of mappingRows.value) {
    if (item.propertyName.trim() === '') {
      void ElMessage.warning('请填写物模型属性名');
      return;
    }
    if (item.metricCode.trim() === '') {
      void ElMessage.warning('请选择 MDC 指标编码');
      return;
    }
  }
  mappingSaving.value = true;
  try {
    const mappings: MappingItem[] = mappingRows.value.map((item) => ({
      propertyName: item.propertyName.trim(),
      metricCode: item.metricCode.trim(),
      mismatchStrategy:
        item.mismatchStrategy === '' ? undefined : (item.mismatchStrategy as 'RAW_PASSTHROUGH'),
    }));
    await products.updateMappings(mappingTarget.value?.productId ?? '', { mappings });
    showStamp('已保存');
    mappingVisible.value = false;
  } catch (error) {
    surfaceBizError(error);
  } finally {
    mappingSaving.value = false;
  }
}

/* ==================== 命令安全等级登记弹窗 ==================== */
const commandVisible = ref(false);
const commandSaving = ref(false);
/** 弹窗上下文锚点（目标产品） */
const commandTarget = ref<ProductVO | null>(null);

/**
 * 命令登记编辑行（CommandItem 扩稳定键；safetyLevel 安全级/治疗级；allowed 白名单
 * 放行——治疗级默认禁用）。rowKey=行稳定键（v-for 禁 index 键，EX-45/FE-A1-08），
 * 保存时逐字段映射剔除，不入出网面。
 */
interface CommandRowDraft {
  rowKey: string;
  commandName: string;
  serviceId?: string;
  safetyLevel: 'SAFETY' | 'TREATMENT';
  allowed?: boolean;
}
const commandRows = ref<CommandRowDraft[]>([]);

/** 新增一条空命令行（本地组合键） */
function blankCommandRow(): CommandRowDraft {
  return { rowKey: nextDraftRowKey(), commandName: '', safetyLevel: 'SAFETY', allowed: false };
}

/** 打开命令登记弹窗：拉取既有命令标注回显（PUT 整组替换语义下缺回显会使保存静默清空
 * FU-M14-09 白名单数据源）；空配置回落单空行快速录入。 */
async function openCommand(row: ProductVO): Promise<void> {
  commandTarget.value = row;
  commandRows.value = [blankCommandRow()];
  commandVisible.value = true;
  try {
    // 既有命令标注全集回显（BUG-18 修复面；serviceId 随行透传防保存静默清空）
    const existing = await products.listCommands(row.productId ?? '');
    commandRows.value =
      existing.length > 0
        ? existing.map((vo) => ({
            rowKey: draftRowKey(vo.id),
            commandName: vo.commandName ?? '',
            serviceId: vo.serviceId,
            safetyLevel: vo.safetyLevel ?? 'SAFETY',
            allowed: vo.allowed ?? false,
          }))
        : [blankCommandRow()];
  } catch {
    // 回显失败弹错归拦截器；驻留空行（整组替换下保存有清空风险，重开弹窗重试回显）
  }
}

/** 新增一条命令行（弹窗内草稿纯本地态，零出网） */
function addCommandRow(): void {
  commandRows.value.push(blankCommandRow());
}

/** 删除指定命令行（弹窗内草稿纯本地态，零出网） */
function removeCommandRow(index: number): void {
  commandRows.value.splice(index, 1);
}

/** 保存命令登记（PUT 整组替换）：逐行命令名显式校验零出网 → 出网 → 关窗；
 * 成功反馈=钤印「已保存」（丙-1 蓝图第 4 节逐字）。 */
async function onSaveCommands(): Promise<void> {
  if (commandSaving.value) {
    return;
  }
  for (const item of commandRows.value) {
    if (item.commandName.trim() === '') {
      void ElMessage.warning('请填写命令名称');
      return;
    }
  }
  commandSaving.value = true;
  try {
    const commands: CommandItem[] = commandRows.value.map((item) => ({
      commandName: item.commandName.trim(),
      serviceId: item.serviceId,
      safetyLevel: item.safetyLevel,
      allowed: item.allowed,
    }));
    await products.updateCommands(commandTarget.value?.productId ?? '', { commands });
    showStamp('已保存');
    commandVisible.value = false;
  } catch (error) {
    surfaceBizError(error);
  } finally {
    commandSaving.value = false;
  }
}

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=入院登记台先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认——已上架/已触发同步/已保存；失败与业务拒绝归 message 批注条/
 * 拦截器，钤印永不承失败） */
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, 1200);
}

onBeforeUnmount(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
});

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page product-manage">
    <!-- 页内级联容器（契约 ⑦.4）：页面根节点禁挂 .fuy-stagger——stagger 挂本层区块
         （admission-flow 同构形态），本层承接 flex+gap 承担纵排与区块间距；
         级联序：门牌 0 / 两栏 1 / 页脚 2 -->
    <div class="fuy-stagger product-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（刷新钮）+
           「IoTDA 产品镜像 · 谁 · 时刻」批注行 + 2px 墨规收底（脸归全局族） -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">产品与物模型</h1>
          <span class="fuy-page-en" aria-hidden="true">PRODUCT &amp; MODEL</span>
        </div>
        <div class="fuy-page-status">
          <el-button :loading="listLoading" @click="loadList">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          IoTDA 产品镜像 · 签认人 {{ operatorName }} · <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 两栏 lg 15/9（既有）：左产品列表卷宗卡 / 右产品上架卡 -->
      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：产品列表卡（.fuy-dense 密排挂卡容器构成后代关系；50/页 usePagedList） -->
        <el-col :md="24" :lg="15">
          <section class="fuy-card fuy-dense product-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">产品列表</h2>
              <span class="product-list-total"
                >共 <span class="fuy-num">{{ rows.length }}</span> 条</span
              >
            </header>
            <div v-loading="listLoading" class="fuy-card-body">
              <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense">
                <el-table-column label="产品 ID" min-width="140">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.productId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="产品名称" min-width="130">
                  <template #default="{ row }">
                    {{ row.productName }}
                  </template>
                </el-table-column>
                <el-table-column label="设备类型" min-width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.deviceType }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="协议" width="80">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.protocolType }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="物模型同步" width="100">
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      class="fuy-tag-aa"
                      :class="syncStatusClass(row.syncStatus)"
                    >
                      {{ syncStatusLabel(row.syncStatus) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="上架" width="100">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.createdAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="200" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <!-- 模型同步（PR-4F #37「模型同步」出网把守位，IOT_ADMIN 绑定）v-perm
                         直挂——无码 DOM 移除（D-34）；「术语映射」「命令登记」为弹窗入口按
                         盘点口径不挂码（弹窗 footer 出网按钮单独把守）；同步在途 :loading
                         数据态与权限判定两层正交 -->
                    <el-button
                      v-perm="'iot:product:btn:manage'"
                      link
                      type="primary"
                      size="small"
                      :loading="syncingId === row.productId"
                      @click="onSyncModel(row)"
                      >同步</el-button
                    >
                    <el-button link type="primary" size="small" @click="openMapping(row)"
                      >术语映射</el-button
                    >
                    <el-button link type="primary" size="small" @click="openCommand(row)"
                      >命令登记</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（G6/契约 ⑥）：主句「暂无产品」+ 说明给下一步；默认纸箱插画在
                   .fuy-page 子树由全局段压制，本页走自绘空态脸零插画 -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无产品</p>
                <p class="fuy-empty-hint">
                  先在右侧上架卡完成第 1 步建产品，上架后产品将出现在本列表。
                </p>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：产品上架卡（卡头加「上架五步」静态步序注记——PM 重排裁决：流程引导用
             文书页眉语法不用控件，新人按步序动线走通上架链） -->
        <el-col :md="24" :lg="9">
          <section class="fuy-card product-create-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">产品上架</h2>
            </header>
            <!-- 上架五步步序注记：12px 灰墨单行（序号 .fuy-num + 箭头字符），置卡头下方
                 发丝上——纯文字序号行非交互 stepper，五步文案逐字冻结 -->
            <p class="product-steps">
              <span class="fuy-num">1</span> 建产品 <span aria-hidden="true">→</span>
              <span class="fuy-num">2</span> 物模型 <span aria-hidden="true">→</span>
              <span class="fuy-num">3</span> 映射术语 <span aria-hidden="true">→</span>
              <span class="fuy-num">4</span> 标注命令 <span aria-hidden="true">→</span>
              <span class="fuy-num">5</span> 注册设备
            </p>
            <div class="fuy-card-body">
              <el-form label-position="top" size="small">
                <label class="product-field-label">产品名称（必填）</label>
                <input
                  v-model="createForm.productName"
                  class="product-input"
                  placeholder="如：多参数监护仪"
                  aria-label="产品名称"
                />
                <div class="product-field-grid">
                  <div class="product-field">
                    <label class="product-field-label">设备类型（必填）</label>
                    <input
                      v-model="createForm.deviceType"
                      class="product-input"
                      placeholder="如：Monitor"
                      aria-label="设备类型"
                    />
                  </div>
                  <div class="product-field">
                    <label class="product-field-label">协议类型（必填）</label>
                    <input
                      v-model="createForm.protocolType"
                      class="product-input"
                      placeholder="如：MQTT"
                      aria-label="协议类型"
                    />
                  </div>
                </div>
                <label class="product-field-label">数据格式（必填）</label>
                <input
                  v-model="createForm.dataFormat"
                  class="product-input"
                  placeholder="如：JSON"
                  aria-label="数据格式"
                />
                <div class="product-field-grid">
                  <div class="product-field">
                    <label class="product-field-label">厂商名称</label>
                    <input
                      v-model="createForm.manufacturerName"
                      class="product-input"
                      aria-label="厂商名称"
                    />
                  </div>
                  <div class="product-field">
                    <label class="product-field-label">所属行业</label>
                    <input
                      v-model="createForm.industry"
                      class="product-input"
                      aria-label="所属行业"
                    />
                  </div>
                </div>
                <label class="product-field-label">产品描述</label>
                <textarea
                  v-model="createForm.description"
                  class="product-input product-textarea"
                  rows="2"
                  aria-label="产品描述"
                ></textarea>
                <!-- 上架产品（PR-4F #37「新建」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
                     DOM 移除（D-34）；右栏上架表单常驻（非弹窗），创建 POST 以本按钮为唯一
                     出口，无码会话表单可填不可出网；:loading/:disabled 在途数据态与权限判定
                     两层正交 -->
                <el-button
                  v-perm="'iot:product:btn:manage'"
                  type="primary"
                  class="product-submit"
                  :loading="creating"
                  :disabled="creating"
                  @click="onCreate"
                  >上架产品</el-button
                >
              </el-form>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ③ 术语映射编辑弹窗（PUT 整组替换语义；挂 .fuy-dialog=衬线标题+头部发丝全局脸，
           行编辑行高与 .fuy-form 密度对齐） -->
      <el-dialog v-model="mappingVisible" title="术语映射编辑" width="640px" class="fuy-dialog">
        <p class="product-dialog-target">
          产品 {{ mappingTarget?.productName ?? '' }}
          <span class="fuy-num">{{ mappingTarget?.productId ?? '' }}</span>
          —— 物模型属性映射 MDC 指标编码（跨品牌归一）
        </p>
        <div v-for="(item, index) in mappingRows" :key="item.rowKey" class="product-mapping-row">
          <input
            v-model="item.propertyName"
            class="product-input product-mapping-prop"
            placeholder="物模型属性名（如 heartRate）"
            :aria-label="index === 0 ? '物模型属性名' : `物模型属性名 ${index + 1}`"
          />
          <select
            v-model="item.metricCode"
            class="product-input product-mapping-code"
            :aria-label="index === 0 ? 'MDC 指标编码' : `MDC 指标编码 ${index + 1}`"
          >
            <option value="">请选择指标编码</option>
            <option v-for="m in metricOptions" :key="m.metricCode" :value="m.metricCode ?? ''">
              {{ m.metricCode }} {{ m.metricName }}
            </option>
          </select>
          <select v-model="item.mismatchStrategy" class="product-input product-mapping-strategy">
            <option value="">默认策略</option>
            <option value="RAW_PASSTHROUGH">失配原样透传</option>
          </select>
          <el-button link type="danger" size="small" @click="removeMappingRow(index)"
            >删除</el-button
          >
        </div>
        <el-button size="small" @click="addMappingRow">增行</el-button>
        <template #footer>
          <el-button size="small" @click="mappingVisible = false">取消</el-button>
          <!-- 保存映射（PR-4F #37「指标映射」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
               DOM 移除（D-34），PUT 整组替换以本按钮为唯一出口：入口「术语映射」按盘点口径
               不挂码，无码会话仅可开窗查看不可出网；行内删除/增行为草稿纯本地态不挂码；
               :loading/:disabled 在途数据态与权限判定两层正交 -->
          <el-button
            v-perm="'iot:product:btn:manage'"
            type="primary"
            size="small"
            :loading="mappingSaving"
            :disabled="mappingSaving"
            @click="onSaveMappings"
            >保存映射</el-button
          >
        </template>
      </el-dialog>

      <!-- 命令安全等级登记弹窗（FU-M14-09 白名单数据源；挂 .fuy-dialog 同上） -->
      <el-dialog v-model="commandVisible" title="命令安全等级登记" width="640px" class="fuy-dialog">
        <p class="product-dialog-target">
          产品 {{ commandTarget?.productName ?? '' }}
          <span class="fuy-num">{{ commandTarget?.productId ?? '' }}</span>
          —— 安全级默认放行，治疗级默认禁用（豁免需系统参数开启并审计）
        </p>
        <div v-for="(item, index) in commandRows" :key="item.rowKey" class="product-mapping-row">
          <input
            v-model="item.commandName"
            class="product-input product-mapping-prop"
            placeholder="命令名称（如 setAlarmLimit）"
            :aria-label="index === 0 ? '命令名称' : `命令名称 ${index + 1}`"
          />
          <select
            v-model="item.safetyLevel"
            class="product-input product-mapping-code"
            :aria-label="index === 0 ? '命令安全等级' : `命令安全等级 ${index + 1}`"
          >
            <option v-for="(label, code) in SAFETY_LEVEL_LABELS" :key="code" :value="code">
              {{ label }}
            </option>
          </select>
          <label class="product-allowed-label">
            <input
              v-model="item.allowed"
              type="checkbox"
              :aria-label="index === 0 ? '白名单放行' : `白名单放行 ${index + 1}`"
            />
            白名单放行
          </label>
          <el-button link type="danger" size="small" @click="removeCommandRow(index)"
            >删除</el-button
          >
        </div>
        <el-button size="small" @click="addCommandRow">增行</el-button>
        <template #footer>
          <el-button size="small" @click="commandVisible = false">取消</el-button>
          <!-- 保存命令（PR-4F #37「命令模板」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
               DOM 移除（D-34），白名单 PUT 以本按钮为唯一出口：入口「命令登记」按盘点口径
               不挂码，无码会话仅可开窗查看不可出网；行内删除/增行为草稿纯本地态不挂码；
               :loading/:disabled 在途数据态与权限判定两层正交 -->
          <el-button
            v-perm="'iot:product:btn:manage'"
            type="primary"
            size="small"
            :loading="commandSaving"
            :disabled="commandSaving"
            @click="onSaveCommands"
            >保存命令</el-button
          >
        </template>
      </el-dialog>

      <!-- ④ 页脚批注（G1 纵向序收尾，契约 ⑧.8）：数据时点=最近一次成功加载时点 +
           手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ loadedAtLabel }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认——已上架/已触发同步/已保存；v-if 挂载制=
           入院登记台先例同构——防 stagger 误伤常驻泄漏，seq 递增重挂重放落章动画，
           ≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——admission-flow
   同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距
   （门牌 0 / 两栏 1 / 页脚 2 三段级联序）由本层同值承接（.fuy-page 同构 flex+gap），
   保证视觉零漂移 */
.product-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 列表卡头计数（卷宗卡卡头右挂工具位，12px 灰墨档——七页卡头副注统一 12px 档） */
.product-list-total {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* 列表卡体 CLS 锁（蓝图 丙-1.6：骨架 min-height 240px——admission-queue-body 同构口径）：
   加载/空态切换零塌陷 */
.product-list-card .fuy-card-body {
  box-sizing: border-box;
  min-height: 240px;
}

/* 上架五步步序注记（文书页眉语法）：黑体默认面（丙-1 收口，2026-10-12——原衬线档违
   契约 §④.1 批注行族归黑体，衬线允许面清单无步序注记类，删声明随全局默认继承）
   12px 灰墨单行置卡头下方发丝上——序号 .fuy-num + 箭头字符，纯文字序号行非交互 stepper；
   窄屏横向滚动不折行（页眉单行语义） */
.product-steps {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  margin: 0;
  padding: var(--fuy-space-2) var(--fuy-space-4);
  border-bottom: var(--fuy-border-hairline);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
  white-space: nowrap;
  overflow-x: auto;
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款）；行高与 .fuy-form
   密度对齐（13px 正文档 + 5px 垂直内距），弹窗行编辑共消费。围合取值直取 --fuy-* 语义
   通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边——住院域琢段统一口径，与卡面拉开一档） */
.product-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

.product-textarea {
  resize: vertical;
}

.product-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.product-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.product-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

.product-dialog-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 弹窗映射/命令行：三列布局（属性名/编码/策略）与删除按钮；行距由行容器承载
   （行内控件底距归零防叠加）——640px 内行距松一档，行高与 .fuy-form 密度对齐 */
.product-mapping-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
}

.product-mapping-row .product-input {
  margin-bottom: 0;
}

.product-mapping-prop {
  flex: 1.2;
}

.product-mapping-code {
  flex: 1.6;
}

.product-mapping-strategy {
  flex: 1;
}

.product-allowed-label {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  white-space: nowrap;
}

/* 物模型同步状态三态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-sync-tag--syncing {
  border-color: var(--fuy-color-product-syncing);
  color: var(--fuy-color-product-syncing);
}

.fuy-sync-tag--synced {
  border-color: var(--fuy-color-product-synced);
  color: var(--fuy-color-product-synced);
}

.fuy-sync-tag--mismatch {
  border-color: var(--fuy-color-product-mismatch);
  color: var(--fuy-color-product-mismatch);
}
</style>
