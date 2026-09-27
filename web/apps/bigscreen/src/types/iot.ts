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

/**
 * 告警触发帧载荷手写后备类型（web A.3-3 手写条款的 STOMP 延伸，与遥测摘要同先例）：
 * 字段与后端 record 逐字对齐——backend/fuyun-iot api/payload/AlarmTriggeredPayload
 * （iot.alarm.triggered V1004 id 74 冻结契约），经 /topic/iot/alarm/{wardId} 推送；
 * STOMP 载荷无 openapi 生成来源，手写为唯一路径（漂移依赖后端测试与前端类型同步维护）。
 * Long 字段（patientId/wardId/ruleId）后端经 Jackson 全局字符串化（backend A.3-8），
 * 前端一律 string 承载（web A.3-6）；occurredAt 为 Instant 的 ISO-8601 字符串原样展示。
 */
export interface IotAlarmFrame {
  /** 告警业务号，非空；来源：告警引擎告警生成域签发 */
  alarmNo: string;
  /** IoTDA 设备标识，非空；来源：触发规则的遥测/状态帧来源设备 */
  deviceId: string;
  /** 患者主索引，可空（公共区域设备无患者关联）；字符串化 Long */
  patientId: string | null;
  /** 住院就诊号（CF-3 I 型 visit_id），可空（设备未绑定在院患者） */
  visitId: string | null;
  /** 病区 ID（告警按病区路由），字符串化 Long */
  wardId: string;
  /** 告警级别，值域 INFO/WARNING/CRITICAL */
  alarmLevel: string;
  /** 指标编码（MDC 术语，如 MDC_ECG_HEART_RATE），非空 */
  metricCode: string;
  /** 触发值文本（保留原始形态，数值语义由消费方按 metricCode 解释），非空 */
  triggerValue: string;
  /** 命中规则 ID，字符串化 Long */
  ruleId: string;
  /** 业务发生时刻（UTC，ISO-8601 字符串原样承载），非空 */
  occurredAt: string;
}
