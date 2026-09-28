<script setup lang="ts">
// 产品与物模型管理页（/iot/products，M14 FU-M14-02 前端面）：产品列表（物模型同步状态
// 三态徽标/同步物模型行操作）+ 产品上架表单 + 术语映射编辑弹窗（物模型属性→MDC 指标编码
// 跨品牌归一，指标字典选项预载）+ 命令安全等级登记弹窗（安全级/治疗级标注——FU-M14-09
// 白名单数据源，治疗级默认禁用）。上架五步流程（建产品→定义物模型→映射术语→标注命令
// 等级→设备注册）的前端承载面。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 结构必填显式校验零出网（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底展示 detail 原文。
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import { metrics, PRODUCT_SYNC_STATUS_LABELS, products, SAFETY_LEVEL_LABELS } from '@/api/iot';
import type { CommandItem, MappingItem, MetricDictVO, ProductVO } from '@/api/iot';

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/** 时点展示串（MM-dd HH:mm，上架时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 产品列表 ==================== */
const rows = ref<ProductVO[]>([]);
const listLoading = ref(false);

/** 加载产品列表（IoTDA 本地镜像，按返回序直出） */
async function loadList(): Promise<void> {
  listLoading.value = true;
  try {
    const page = await products.list({ page: 0, size: 50 });
    rows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    listLoading.value = false;
  }
}

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

/** 同步物模型（触发 IoTDA 拉取物模型对齐镜像；失配恢复后徽标随刷新更新） */
async function onSyncModel(row: ProductVO): Promise<void> {
  if (syncingId.value !== '') {
    return;
  }
  syncingId.value = row.productId ?? '';
  try {
    await products.syncModel(row.productId ?? '');
    void ElMessage.success(`物模型同步已触发：${row.productName ?? ''}`);
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

/** 产品上架：结构必填显式校验（名称/设备类型/协议/数据格式，零出网）→ 出网 → 清表单刷新。 */
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
    void ElMessage.success('产品已上架，进入产品列表');
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
/** 映射行集合（propertyName→metricCode；mismatchStrategy 空串=默认策略不传） */
const mappingRows = ref<
  Array<{ propertyName: string; metricCode: string; mismatchStrategy: string }>
>([]);
/** 指标字典（弹窗打开预载，MDC 编码选项源） */
const metricOptions = ref<MetricDictVO[]>([]);

/** 打开术语映射弹窗：拉取既有映射回显（PUT 整组替换语义下缺回显会使保存静默清空既有 N 条）
 * 并预载指标字典；两路加载互不拖垮。 */
async function openMapping(row: ProductVO): Promise<void> {
  mappingTarget.value = row;
  mappingRows.value = [{ propertyName: '', metricCode: '', mismatchStrategy: '' }];
  mappingVisible.value = true;
  try {
    // 既有映射全集回显（BUG-17 修复面）；空配置回落单空行快速录入
    const existing = await products.listMappings(row.productId ?? '');
    mappingRows.value =
      existing.length > 0
        ? existing.map((vo) => ({
            propertyName: vo.propertyName ?? '',
            metricCode: vo.metricCode ?? '',
            mismatchStrategy: vo.mismatchStrategy ?? '',
          }))
        : [{ propertyName: '', metricCode: '', mismatchStrategy: '' }];
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

/** 新增一条映射行 */
function addMappingRow(): void {
  mappingRows.value.push({ propertyName: '', metricCode: '', mismatchStrategy: '' });
}

/** 删除指定映射行 */
function removeMappingRow(index: number): void {
  mappingRows.value.splice(index, 1);
}

/** 保存映射（PUT 整组替换）：逐行结构必填显式校验零出网 → 出网 → 关窗。 */
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
    void ElMessage.success(`术语映射已保存：${mappingTarget.value?.productName ?? ''}`);
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
/** 命令行集合（safetyLevel 安全级/治疗级；allowed 白名单放行——治疗级默认禁用） */
const commandRows = ref<CommandItem[]>([]);

/** 打开命令登记弹窗：拉取既有命令标注回显（PUT 整组替换语义下缺回显会使保存静默清空
 * FU-M14-09 白名单数据源）；空配置回落单空行快速录入。 */
async function openCommand(row: ProductVO): Promise<void> {
  commandTarget.value = row;
  commandRows.value = [{ commandName: '', safetyLevel: 'SAFETY', allowed: false }];
  commandVisible.value = true;
  try {
    // 既有命令标注全集回显（BUG-18 修复面；serviceId 随行透传防保存静默清空）
    const existing = await products.listCommands(row.productId ?? '');
    commandRows.value =
      existing.length > 0
        ? existing.map((vo) => ({
            commandName: vo.commandName ?? '',
            serviceId: vo.serviceId,
            safetyLevel: vo.safetyLevel ?? 'SAFETY',
            allowed: vo.allowed ?? false,
          }))
        : [{ commandName: '', safetyLevel: 'SAFETY', allowed: false }];
  } catch {
    // 回显失败弹错归拦截器；驻留空行（整组替换下保存有清空风险，重开弹窗重试回显）
  }
}

/** 新增一条命令行 */
function addCommandRow(): void {
  commandRows.value.push({ commandName: '', safetyLevel: 'SAFETY', allowed: false });
}

/** 删除指定命令行 */
function removeCommandRow(index: number): void {
  commandRows.value.splice(index, 1);
}

/** 保存命令登记（PUT 整组替换）：逐行命令名显式校验零出网 → 出网 → 关窗。 */
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
    void ElMessage.success(`命令登记已保存：${commandTarget.value?.productName ?? ''}`);
    commandVisible.value = false;
  } catch (error) {
    surfaceBizError(error);
  } finally {
    commandSaving.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page product-manage fuy-stagger">
    <!-- 页头：标题 + 流程提示 + 刷新 -->
    <header class="product-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="product-title">产品与物模型</h2>
      <span class="product-hint"
        >IoTDA 产品镜像 · 上架五步：建产品→物模型→映射术语→标注命令→注册</span
      >
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：产品列表 -->
      <el-col :md="24" :lg="15">
        <el-card>
          <template #header>
            <span>产品列表（共 {{ rows.length }} 条）</span>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
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
                  <el-tag size="small" class="fuy-tag-aa" :class="syncStatusClass(row.syncStatus)">
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
                  <el-button
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
            <el-empty v-else :image-size="72" description="暂无产品，先从右侧上架" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：产品上架表单 -->
      <el-col :md="24" :lg="9">
        <el-card>
          <template #header>产品上架</template>
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
                <input v-model="createForm.industry" class="product-input" aria-label="所属行业" />
              </div>
            </div>
            <label class="product-field-label">产品描述</label>
            <textarea
              v-model="createForm.description"
              class="product-input product-textarea"
              rows="2"
              aria-label="产品描述"
            ></textarea>
            <el-button
              type="primary"
              class="product-submit"
              :loading="creating"
              :disabled="creating"
              @click="onCreate"
              >上架产品</el-button
            >
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 术语映射编辑弹窗（PUT 整组替换语义） -->
    <el-dialog v-model="mappingVisible" title="术语映射编辑" width="640px">
      <p class="product-dialog-target">
        产品 {{ mappingTarget?.productName ?? '' }}
        <span class="fuy-num">{{ mappingTarget?.productId ?? '' }}</span>
        —— 物模型属性映射 MDC 指标编码（跨品牌归一）
      </p>
      <div v-for="(item, index) in mappingRows" :key="index" class="product-mapping-row">
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
        <el-button link type="danger" size="small" @click="removeMappingRow(index)">删除</el-button>
      </div>
      <el-button size="small" @click="addMappingRow">增行</el-button>
      <template #footer>
        <el-button size="small" @click="mappingVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="mappingSaving"
          :disabled="mappingSaving"
          @click="onSaveMappings"
          >保存映射</el-button
        >
      </template>
    </el-dialog>

    <!-- 命令安全等级登记弹窗（FU-M14-09 白名单数据源） -->
    <el-dialog v-model="commandVisible" title="命令安全等级登记" width="640px">
      <p class="product-dialog-target">
        产品 {{ commandTarget?.productName ?? '' }}
        <span class="fuy-num">{{ commandTarget?.productId ?? '' }}</span>
        —— 安全级默认放行，治疗级默认禁用（豁免需系统参数开启并审计）
      </p>
      <div v-for="(item, index) in commandRows" :key="index" class="product-mapping-row">
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
        <el-button link type="danger" size="small" @click="removeCommandRow(index)">删除</el-button>
      </div>
      <el-button size="small" @click="addCommandRow">增行</el-button>
      <template #footer>
        <el-button size="small" @click="commandVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="commandSaving"
          :disabled="commandSaving"
          @click="onSaveCommands"
          >保存命令</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 产品管理两栏布局：左列表右上架表单，token 取色禁自创色值 */
.product-toolbar {
  align-items: center;
}

.product-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.product-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款） */
.product-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
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

/* 弹窗映射/命令行：三列布局（属性名/编码/策略）与删除按钮 */
.product-mapping-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
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
  margin-bottom: var(--fuy-space-3);
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
