/**
 * IoT 遥测摘要手写后备类型（web A.3-3 手写条款的 STOMP 延伸）。
 *
 * ⚠️ openapi-typescript 生成物可用后由 packages/shared api.d.ts 承接并删除本文件：
 * 生成链路仅覆盖 REST（/v3/api-docs），STOMP 载荷无生成来源，手写为唯一路径（P0 有效期）。
 * 字段名与后端 record 逐字对齐：backend/fuyun-iot
 * service/ITelemetryPushService.TelemetrySummary / TelemetrySummary.Item
 * （count / occurredAtUpperBound / items / deviceId / metricCode）；后端契约变更时
 * vue-tsc 无法自动暴露漂移，依赖后端测试与前端类型同步维护（PR 审核关注点）。
 * occurredAtUpperBound 为后端 Instant 经 Spring Boot 默认序列化的 ISO-8601 字符串，
 * 前端 string 承载原样展示；本载荷无 Long 字段（web A.3-6 不涉）。
 */

/** 遥测摘要明细二元组（后端 TelemetrySummary.Item，订阅方识别本批涉及的设备与指标） */
export interface TelemetrySummaryItem {
  /** IoTDA 设备标识，非空；来源：遥测落库批次 */
  deviceId: string;
  /** 指标编码，非空；来源：遥测落库批次 */
  metricCode: string;
}

/** 遥测批量落库成功后的摘要帧载荷（后端 TelemetrySummary，每批一帧推送） */
export interface TelemetrySummary {
  /** 本批实体条数（真实批大小，不随 items 截断减少——items 超 100 条被后端截断时以本字段为准），正整数 */
  count: number;
  /** 本批最大发生时刻（UTC 语义，摘要时间锚点，ISO-8601 字符串原样承载），非空字符串 */
  occurredAtUpperBound: string;
  /** 明细列表（deviceId+metricCode 二元组，后端上限 100 条；防御口径允许空数组） */
  items: TelemetrySummaryItem[];
}
