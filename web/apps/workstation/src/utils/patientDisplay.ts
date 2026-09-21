/**
 * 患者域展示文案纯函数（web B.2-4 同 app 多处复用下沉 utils）：性别 / 档案状态 /
 * 建档渠道 / 档案来源编码 → 中文文案与 Element Plus tag 颜色语义映射，
 * 检索页表格与详情页共用（F-8 枚举直出修复：操作员可读性，后端枚举原文不直出）。
 */

/** 状态词表 → 文案与 tag 颜色（与后端 PatientStatus 枚举一致：NORMAL/FROZEN/MERGED） */
const STATUS_META: Record<
  string,
  { label: string; tag: 'success' | 'warning' | 'danger' | 'info' }
> = {
  NORMAL: { label: '正常', tag: 'success' },
  FROZEN: { label: '已冻结', tag: 'danger' },
  MERGED: { label: '已合并', tag: 'warning' },
};

/**
 * 性别编码 → 展示文案。
 *
 * @param sex 性别编码（'1' 男 / '2' 女，字典口径；允许为空=档案未登记）
 * @return 中文文案；未知编码原样回显（暴露脏数据便于人工核对），空值返回空串
 */
export function patientSexText(sex?: string): string {
  if (sex === '1') return '男';
  if (sex === '2') return '女';
  return sex ?? '';
}

/**
 * 档案状态 → 中文文案。
 *
 * @param status PatientStatus 枚举值（允许为空=状态未知）
 * @return 中文文案；未知状态原样回显
 */
export function patientStatusText(status?: string): string {
  return STATUS_META[status ?? '']?.label ?? status ?? '';
}

/**
 * 档案状态 → el-tag 颜色语义。
 *
 * @param status PatientStatus 枚举值
 * @return tag type（正常 success / 冻结 danger / 合并 warning）；未知状态回 info 灰
 */
export function patientStatusTagType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  return STATUS_META[status ?? '']?.tag ?? 'info';
}

/** 建档渠道词表 → 中文文案（与后端 RegisterChannel 枚举一致，与建档页表单选项同源） */
const REGISTER_CHANNEL_TEXT: Record<string, string> = {
  WINDOW: '窗口',
  SELF_SERVICE: '自助机',
  ONLINE: '线上',
  INPATIENT_REGISTER: '住院登记',
  EMERGENCY: '急诊',
};

/** 档案来源词表 → 中文文案（与后端 ArchiveSource 枚举一致，与建档页表单选项同源） */
const ARCHIVE_SOURCE_TEXT: Record<string, string> = {
  STANDARD: '正式档案',
  TEMP_ANONYMOUS: '急诊无名氏（临时）',
  TEMP_NEWBORN: '新生儿（临时）',
};

/**
 * 建档渠道编码 → 展示文案。
 *
 * @param channel RegisterChannel 枚举值（允许为空=渠道未登记）
 * @return 中文文案；未知编码原样回显（暴露脏数据便于人工核对），空值返回空串
 */
export function patientRegisterChannelText(channel?: string): string {
  return REGISTER_CHANNEL_TEXT[channel ?? ''] ?? channel ?? '';
}

/**
 * 档案来源编码 → 展示文案。
 *
 * @param source ArchiveSource 枚举值（允许为空=来源未登记）
 * @return 中文文案；未知编码原样回显，空值返回空串
 */
export function patientArchiveSourceText(source?: string): string {
  return ARCHIVE_SOURCE_TEXT[source ?? ''] ?? source ?? '';
}

/** 建档渠道 el-option 选项（词表单源派生，键序即渲染序——批次 2 移交打磨项：消除与
 * 患者建档页 el-option 字面量的双份手工维护） */
export const patientRegisterChannelOptions: ReadonlyArray<{ value: string; label: string }> =
  Object.entries(REGISTER_CHANNEL_TEXT).map(([value, label]) => ({ value, label }));

/** 档案来源 el-option 选项（同上单源派生） */
export const patientArchiveSourceOptions: ReadonlyArray<{ value: string; label: string }> =
  Object.entries(ARCHIVE_SOURCE_TEXT).map(([value, label]) => ({ value, label }));
