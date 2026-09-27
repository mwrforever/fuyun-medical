/**
 * IoT STOMP 帧载荷手写后备类型（web A.3-3 手写条款的 STOMP 延伸，bigscreen types/iot.ts
 * 同构移植）：openapi-typescript 生成链路仅覆盖 REST（/v3/api-docs），STOMP 载荷无生成来源，
 * 手写为唯一路径（P0 有效期）。字段名与后端逐字对齐：backend/fuyun-iot
 * service/ITelemetryPushService.TelemetrySummary 与 api/payload.AlarmTriggeredPayload
 * （V1004 id 74 冻结契约）；后端契约变更时 vue-tsc 无法自动暴露漂移，依赖后端测试与前端类型
 * 同步维护（PR 审核关注点）。Instant 字段经 Spring Boot 默认序列化为 ISO-8601 字符串，
 * Long 字段经 Jackson 全局字符串化（backend A.3-8），前端一律 string 承载。
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
  /** 本批实体条数（真实批大小，不随 items 截断减少），正整数 */
  count: number;
  /** 本批最大发生时刻（UTC 语义摘要时间锚点，ISO-8601 字符串原样承载），非空字符串 */
  occurredAtUpperBound: string;
  /** 明细列表（deviceId+metricCode 二元组，后端上限 100 条；防御口径允许空数组） */
  items: TelemetrySummaryItem[];
}

/** 告警触发帧载荷（后端 AlarmTriggeredPayload，iot.alarm.triggered 推送面，Task 7 冻结契约） */
export interface AlarmTriggeredPayload {
  /** 告警业务号，非空；来源：告警引擎告警生成域签发 */
  alarmNo: string;
  /** IoTDA 设备标识，非空；来源：触发规则的遥测/状态帧来源设备 */
  deviceId: string;
  /** 患者主索引，可空（无患者关联设备的公共区域告警为 null）；Long 经 Jackson 字符串化 */
  patientId: string | null;
  /** 住院就诊号（CF-3 I 型 visit_id），可空（设备未绑定在院患者为 null） */
  visitId: string | null;
  /** 病区 ID，非空；来源：设备绑定档案（告警按病区路由推送）；Long 经 Jackson 字符串化 */
  wardId: string;
  /** 告警级别，非空；值域 INFO/WARNING/CRITICAL */
  alarmLevel: string;
  /** 指标编码，非空；来源：触发判定的遥测指标（输液告急为 INFUSION_SHORTAGE 占位词） */
  metricCode: string;
  /** 触发值，非空；采集值原文（保留原始形态，数值语义由消费方按 metricCode 解释） */
  triggerValue: string;
  /** 命中规则 ID，非空；Long 经 Jackson 字符串化 */
  ruleId: string;
  /** 业务发生时刻（UTC），非空；来源：命中判定时刻 */
  occurredAt: string;
}
