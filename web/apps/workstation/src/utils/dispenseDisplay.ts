/**
 * 药房发药域展示词表（web B.2-4 同 app 多处复用下沉 utils，patientDisplay 同形态）：
 * 发药单状态 → 中文文案，发药工作台与退药受理两页共用——单一词表源，禁页内字面量复制。
 */

/** 发药单状态词表（DispenseVO.status 契约为 string 无生成物枚举；与后端 Dispense 状态机四活动态同源） */
const DISPENSE_STATUS_TEXT: Record<string, string> = {
  CREATED: '待配药',
  PICKING: '配药中',
  PICKED: '待发药签名',
  ISSUED: '已发药',
};

/**
 * 发药单状态 → 展示文案。
 *
 * @param status DispenseVO.status 值（允许为空=状态未知）
 * @return 中文文案；未知状态原样回显（防后端扩态白屏），空值返回空串
 */
export function dispenseStatusText(status?: string): string {
  return DISPENSE_STATUS_TEXT[status ?? ''] ?? status ?? '';
}
