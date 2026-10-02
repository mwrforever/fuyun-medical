-- V1108：W-34 表改造 + D-23 部分唯一索引（P2 PR-3 Task 2；台账已先记再改）。
-- 一、W-34 退役第②项（TASK.md W-34 原文五项之二）——nursing_ward_patient 本地视图表列退役，
--   表保留为纯事件投影（admitted upsert / transferred 更新归属 / discharged 逻辑删 / bed.changed
--   更新床号，四 listener 单一写入面，Task 7 实装）：
--   ① 端点与 DTO（POST /ward-patients、/{visitId}/remove 及请求对象）——Task 7 Java 侧退役；
--   ② 行状态值 IN_WARD/REMOVED 与 status/source 两列退役（本迁移 DDL 承担：先清 REMOVED 存量行保投影
--      干净，再 DROP 两列）；
--   ③ @AuditLog 留痕语义——随端点退役，事件消费经事件溯源留痕（Task 7）；
--   ④ WardPatientVO 字段面事件推导形状核验（GC39）——Task 7 逐字段比对；
--   ⑤ 一览相关 IT 换源重跑（NursingPatientContextIT 改事件驱动夹具）——Task 7。
--   列退役连带：uk_ward_patient_visit/uk_ward_patient_bed/idx_ward_patient_ward_status 三索引谓词含
--   status，随列删除自动消亡——投影唯一性（一就诊一行在册、一床一位在册）由下方重建索引承接，
--   谓词改由 deleted=0 单独承载（discharged=逻辑删，Task 7 口径）。
-- 二、D-23 根治（TASK.md D-23 裁决：部分唯一索引 + 冲突回查合并，D-22 PDA 弱网补传幂等键同款
--   两层兜底范式——DB 唯一约束为底、应用层捕获 DuplicateKeyException 回查重试一次）：
--   nursing_record 上 (visit_id, record_date) 部分唯一索引限定正常合并行形态
--   （auto_generated=true AND abnormal_flag=false AND deleted=0），appendObservation 合并分支
--   捕获冲突后按当日窗回查合并（catch 分支归 Task 7）。
--   实测补落：V802 建表无 record_date 列（本索引维度的前提列），此处补列并按北京钟面回填存量行
--   （当日窗口径与 appendObservation 的 NursingTimeConstants.HEALTHCARE_TZ 同源语义）；
--   新行写入义务归 Task 7 插入路径（setRecordDate(北京当日)），未落值的新行 record_date 为 NULL
--   在唯一索引下互异不误伤，但也不受约束——Task 7 派发时必须携带该写入义务。
-- 通用约定（V801/V905 同款）：TIMESTAMPTZ 服务器时间、逻辑删；本迁移无新表不涉触发器新增。

-- ===================== 一、W-34：nursing_ward_patient status/source 列退役 =====================
-- 先清 REMOVED 存量行（列退役后其语义唯一载体消失；在区行全量保留为事件投影基线）
DELETE FROM nursing.nursing_ward_patient WHERE status = 'REMOVED';
ALTER TABLE nursing.nursing_ward_patient DROP COLUMN status;
ALTER TABLE nursing.nursing_ward_patient DROP COLUMN source;

-- 投影唯一性重建（原谓词 status='IN_WARD' 随列消亡，改由 deleted=0 单独承载）
CREATE UNIQUE INDEX uk_ward_patient_visit ON nursing.nursing_ward_patient (visit_id) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_ward_patient_bed ON nursing.nursing_ward_patient (ward_id, bed_no) WHERE deleted = 0;

-- ===================== 二、D-23：nursing_record 正常合并行日维度唯一约束 =====================
-- 补落索引维度前提列（V802 无此列）：记录归属业务日，北京钟面日界（当日窗回查与唯一约束同口径）
ALTER TABLE nursing.nursing_record ADD COLUMN record_date DATE;
COMMENT ON COLUMN nursing.nursing_record.record_date IS '记录归属业务日（北京钟面日界；D-23 正常合并行唯一索引维度——写入方按 record_time 北京当日落值，存量由 V1108 回填）';
UPDATE nursing.nursing_record
   SET record_date = (record_time AT TIME ZONE 'Asia/Shanghai')::date
 WHERE record_date IS NULL;
CREATE UNIQUE INDEX uk_nursing_record_auto_normal_daily ON nursing.nursing_record (visit_id, record_date)
 WHERE auto_generated = true AND abnormal_flag = false AND deleted = 0;
