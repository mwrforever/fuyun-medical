/**
 * IoT 事件载荷契约包（iot/api 的暴露 NamedInterface 子包）：V403/V1004 冻结的跨模块消费契约
 * record（AlarmTriggeredPayload/TelemetryAnomalyPayload/CallTriggeredPayload 等），载荷组件名与
 * event_registry payload_desc 三方一致（契约锚 IotMessagingContractTest），变更属 CF-7 契约变更
 * 须双向评审。P2 PR-2 Task 12 起 M16（ward）为首个跨模块消费方（输液告急落行/体征质量注记），
 * Modulith NamedInterface 自本子包显式暴露（父包 com.fuyun.iot.api 的 NamedInterface 默认不
 * 覆盖子包，ward 引用载荷 record 需要 payload 包独立声明）。
 */
@org.springframework.modulith.NamedInterface("api")
package com.fuyun.iot.api.payload;
