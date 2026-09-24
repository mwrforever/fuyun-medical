# Flyway 版本占用登记表

> **维护规则（落新迁移前必读）**：
> 1. **查重前置**：任何新迁移文件落盘前，先对照本表查重——目标版本号不得与本表任何已占用版本重复，也不得落入已冻结段（V1–V5 / V100–V105 / V200–V299 首批 / V300–V303 / V400–V403 / V500–V503 / V600–V607 / V700–V703）。撞号在 CI 守卫与真栈 Flyway 校验中均会失败，本表提供落文件前的人工兜底。
> 2. **与守卫互补**：本表与 `scripts/check-migration-governance.py` 的 `_SEGMENTS` 段守卫互补——守卫拦乱序与段越界（机制强制），本表供落文件前人工查重（流程前置）；两者不可互替。
> 3. **活文档**：后续 PR 新增迁移时，同 PR 更新本表（先记再改同款纪律）；数据源为 `backend/*/src/main/resources/db/migration/*/*.sql` 目录实况与 `flyway_schema_history` 实况，**禁凭记忆写版本号**。
> 4. **段位规则**：各模块 schema 基线首批占百位段（如 outpatient V200–V204）；后续迁移一律走 V500+ 通用段（乱序守卫全局规则——schema 非零基线后新迁移必须大于基线全局最大版本，V607/V704/V705 先例）。
>    **V800–V899 为 nursing 专属固定段位（非通用段，其他模块不得占用）**。
> 5. **登记口径**：版本号 / 迁移文件名 / 归属 schema 与模块 / 用途一句话，与本仓库 `docs/superpowers/plans/` 各 PR 计划及 CHANGELOG 交叉可溯。

## 已占用版本一览（V1 起，按版本升序；数据源见文档头第 3 条，2026-09-21 建档实况、2026-09-22 V706 追加、2026-09-24 V808 追加、2026-09-24 V900 追加）

| 版本 | 迁移文件名 | 归属 schema / 模块 | 用途 |
| --- | --- | --- | --- |
| V1 | V1__create_common_audit_trigger_function.sql | integration / fuyun-integration | 通用审计触发器函数（全库审计基座） |
| V2 | V2__create_event_registry.sql | integration / fuyun-integration | 事件登记表（领域事件注册中心） |
| V3 | V3__create_received_event.sql | integration / fuyun-integration | 接收事件表（AMQP 入站留痕） |
| V4 | V4__create_dead_letter.sql | integration / fuyun-integration | 死信表（消费失败留痕与重放） |
| V5 | V5__seed_event_registry_cf2_cf7.sql | integration / fuyun-integration | 事件登记种子（CF-2~CF-7 冻结载体） |
| V100 | V100__create_patient_and_identifier.sql | patient / fuyun-patient | 患者主索引与标识表（EMPI 基座） |
| V101 | V101__create_possible_duplicate_and_merge_record.sql | patient / fuyun-patient | 疑似重复与合并记录表 |
| V102 | V102__create_health_summary_and_item.sql | patient / fuyun-patient | 健康摘要与条目表 |
| V103 | V103__create_privacy_tables_with_mask_rule_seed.sql | patient / fuyun-patient | 隐私脱敏表与掩码规则种子 |
| V104 | V104__create_card_account_and_txn.sql | patient / fuyun-patient | 就诊卡账户与交易表 |
| V105 | V105__seed_patient_event_registry.sql | patient / fuyun-patient | 患者域事件登记种子（id 9–16） |
| V200 | V200__create_schedule_and_pool.sql | outpatient / fuyun-outpatient | 排班模板/排班/号源池表（PR-5 首批，含 V202 三列唯一索引订正） |
| V201 | V201__create_appointment_and_visit.sql | outpatient / fuyun-outpatient | 预约与就诊表（含爽约信用、visit_id 回填） |
| V202 | V202__create_triage_and_queue.sql | outpatient / fuyun-outpatient | 分诊与排队票表（唯一键含 queue_id 三列） |
| V203 | V203__create_clinic_order.sql | outpatient / fuyun-outpatient | 诊疗单表（检查检验/处置开单） |
| V204 | V204__seed_outpatient_event_registry.sql | outpatient / fuyun-outpatient | 门诊域事件登记种子（id 32–40，含 V605/V702 占位行 payload_desc 订正） |
| V300 | V300__create_system_rbac_tables.sql | system / fuyun-system | RBAC 表（用户/角色/权限） |
| V301 | V301__create_system_dict_tables.sql | system / fuyun-system | 数据字典表 |
| V302 | V302__create_system_audit_log.sql | system / fuyun-system | 审计日志表（等保 ≥6 个月留存） |
| V303 | V303__seed_system_rbac.sql | system / fuyun-system | RBAC 种子（演示账号与权限位） |
| V400 | V400__create_iot_device_and_binding.sql | iot / fuyun-iot | IoT 设备与绑定表 |
| V401 | V401__create_iot_consume_error_log.sql | iot / fuyun-iot | IoT 消费错误日志表 |
| V402 | V402__create_iot_telemetry_hypertable.sql | iot / fuyun-iot | 遥测超表（TimescaleDB hypertable） |
| V403 | V403__seed_iot_event_registry.sql | iot / fuyun-iot | IoT 域事件登记种子 |
| V500 | V500__create_event_publication.sql | integration / fuyun-integration | 事件发布表（Spring Modulith 出站） |
| V501 | V501__create_shedlock.sql | integration / fuyun-integration | ShedLock 分布式任务锁表 |
| V502 | V502__create_mdm_subscription.sql | integration / fuyun-integration | MDM 订阅表 |
| V503 | V503__create_mdm_dispatch_log.sql | integration / fuyun-integration | MDM 分发日志表 |
| V600 | V600__create_charge_item_and_price.sql | billing / fuyun-billing | 收费项目与价表 |
| V601 | V601__create_insurance_mapping_and_pricing_rule.sql | billing / fuyun-billing | 医保对照与计价规则表 |
| V602 | V602__create_fee_record.sql | billing / fuyun-billing | 费用记录表（visit_id NOT NULL 冻结锚） |
| V603 | V603__create_settlement_and_refund.sql | billing / fuyun-billing | 结算与退费表 |
| V604 | V604__create_deposit_and_insurance_log.sql | billing / fuyun-billing | 押金与医保日志表 |
| V605 | V605__seed_billing_event_registry.sql | billing / fuyun-billing | 收费域事件登记种子（id 17–24，id 23 冻结） |
| V606 | V606__alter_refund_request_second_approval.sql | billing / fuyun-billing | 退费申请增二级审批列 |
| V607 | V607__seed_medication_route_frequency_dict.sql | system / fuyun-system | 给药途径/频次字典种子（system 通用段先例） |
| V700 | V700__create_drug.sql | pharmacy / fuyun-pharmacy | 药品字典表 |
| V701 | V701__create_prescription.sql | pharmacy / fuyun-pharmacy | 处方与处方行表 |
| V702 | V702__seed_pharmacy_event_registry.sql | pharmacy / fuyun-pharmacy | 药房域事件登记种子（id 25–31，id 25/31 冻结） |
| V703 | V703__create_dispense_and_batch.sql | pharmacy / fuyun-pharmacy | 发药单与批次表 |
| V704 | V704__create_practice_grant.sql | system / fuyun-system | 执业授权表（抗菌药分级授权，PR-5） |
| V705 | V705__seed_outpatient_dict.sql | system / fuyun-system | 门诊三类字典种子（appt-type/visit-type/disposition 19 条，PR-5） |
| V706 | V706__comment_prescription_item_status.sql | pharmacy / fuyun-pharmacy | prescription_item.status 列注释订正（W-23：主代码零写入点，权威口径经 COMMENT ON 更新；V500+ 通用段续号——全局最大 V705 的下一号，满足乱序守卫） |
| V800 | V800__seed_nursing_event_registry.sql | integration（种子落 nursing 段） | CF-6 冻结载体与 M05 发布事件登记 24 行（id 41–64） |
| V801 | V801__create_nursing_ward_meta.sql | nursing / fuyun-nursing | 病区护理配置/责任护士分配/病区患者本地视图 + 三班种子 |
| V802 | V802__create_nursing_document.sql | nursing / fuyun-nursing | 护理记录 + 体温单页 + 体温单条目 |
| V803 | V803__create_vital_sign.sql | nursing / fuyun-nursing | 生命体征记录 |
| V804 | V804__create_io.sql | nursing / fuyun-nursing | 出入量明细 + 出入量小结 |
| V805 | V805__create_nursing_task.sql | nursing / fuyun-nursing | 护理任务（最小载体） |
| V806 | V806__create_nursing_assessment.sql | nursing / fuyun-nursing | 护理评估单 |
| V807 | V807__create_shift_handover.sql | nursing / fuyun-nursing | 交接班 |
| V808 | V808__add_vital_sign_patient_time_index.sql | nursing / fuyun-nursing | 体征表患者维度前导索引（PERF-02：(patient_id, measured_at)，患者维度查询顺序扫描→索引范围扫描；nursing 段续号——全局最大 V807 的下一号，满足乱序守卫） |
| V900 | V900__add_patient_name_trgm_gin_index.sql | patient / fuyun-patient | 患者姓名 trigram GIN 索引（PERF-03：pg_trgm 扩展幂等启用 + gin (name gin_trgm_ops)，姓名 LIKE '%kw%' 前导通配顺序扫描→trigram 索引扫描；V500+ 通用段——V800–V899 为 nursing 专属段不得占用，故取全局最大 V808 之后的首个合法号） |

## 冻结段速查（禁落新文件）

| 段 | 归属 | 说明 |
| --- | --- | --- |
| V1–V5 | integration 基线 | PR-1a 落地，禁改已应用迁移 |
| V100–V105 | patient 首批 | patient 后续迁移走 V500+ 通用段 |
| V200–V299 | outpatient 首批 | V200–V204 已占用；outpatient 后续迁移走 V500+ 通用段（号段初始化豁免已随首批耗尽） |
| V300–V303 | system 基线 | system 后续迁移必须 > 基线全局最大版本 |
| V400–V403 | iot 首批 | iot 后续迁移走 V500+ 通用段 |
| V500–V503 | integration 通用段 | 通用段按版本升序追加，禁改已应用 |
| V600–V607 | billing 首批 + V607 字典 | 禁改已应用；V605 id 23 / V702 id 25/31 仅允许 V204 内数据行 UPDATE |
| V700–V703 | pharmacy 首批 | pharmacy 后续迁移走 V500+ 通用段 |
| V704、V705、V706 | V500+ 通用段（V704/V705=system·PR-5；V706=pharmacy·W-23） | 当前全局最大已应用版本=V900（PR-6 nursing 首批 V800–V807 + PERF-02 V808 患者维度索引 + PERF-03 V900 姓名检索 trigram GIN 索引），新迁移必须 > V900 |
