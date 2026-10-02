-- V1111：event_registry id 28 载荷契约 UPDATE（P2 PR-3 Task 3；台账已先记再改，V1106–V1111 排定）。
-- 契约演进双向评审声明（CF-5）：本 UPDATE 仅向 id 28（pharmacy.dispense.completed）payload_desc
--   追加住院摆药四可空字段声明——只增不删，旧文本逐字保留；既有消费方 billing（占用标记 DISPENSED）
--   与 outpatient（M03 状态聚合，PR-5 订阅）按原子集取用不受影响（新增字段全部可空，反序列化按
--   组件名取值，未知/缺省字段零影响）。新增消费子集归 M05 签收衔接（m04OrderNo/visitId/wardId/
--   dispensePlanNo——visitId 为既有组件的住院 I 型双语义承载，非新增）。
-- 守卫口径：id + event_type 双条件（V702 id 24 UPDATE 同款先例）；本迁移为纯 UPDATE 无 INSERT
--   守卫面。api 侧组件名锚点：DispenseCompletedPayload.COMPONENT_NAMES（V702 原七组件 + 本迁移
--   追加三组件，契约测试 PharmacyEventContractTest 以 V702+V1111 合并面同源断言）。

UPDATE integration.event_registry
SET payload_desc = '门诊发药完成（PR-4 实装）：dispenseNo/prescriptionId/rxNo/patientId/visitId/dispenseType/lines[]{itemCode,batchNo,quantity,traceCodes[]}（与 api DispenseCompletedPayload record 组件逐字同源）；M03 状态聚合（PR-5 订阅）、M13(本仓 billing) 执行占用标记 DISPENSED（PR-4 已接线）；住院摆药行增可空字段 m04OrderNo/visitId/wardId/dispensePlanNo（M05 签收衔接消费子集，V1111 契约演进）'
WHERE id = 28 AND event_type = 'pharmacy.dispense.completed';
