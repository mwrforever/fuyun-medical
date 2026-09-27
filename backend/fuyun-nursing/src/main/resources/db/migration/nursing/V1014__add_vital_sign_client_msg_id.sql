-- V1014：vital_sign_record 客户端幂等键（D-22 PDA 弱网补传幂等收敛，P2 PR-2 Task 13）。
-- 裁决依据：TASK.md D-22 裁决点①——PDA 重试面归 P2，以客户端幂等键收敛（GC16 方案 B 冻结口径）：
--   PDA 弱网下补传重试会重复提交体征录入，服务端以 client_msg_id 识别同一次点测——请求携带
--   同键重入时按键回查重放返回原记录（HTTP 200，非 409），消除弱网重复落卡与重复观察行；
--   DB 层本迁移的稀疏部分唯一索引为最终兜底（应用层回查 + DB 唯一索引两层幂等，A.5-6 同构）。
-- 号段勘误：计划原文排 nursing V809（nursing 固定段 V800–V899 段内续号），执行期经全局乱序
--   守卫拒止（基线全局最大已应用版本高于该号），依 CHANGELOG 2026-09-26 条目⑤勘误改走 V500+
--   通用段续号为 V1014（billing V1001–V1003 先例）；V803 等既有迁移禁改红线不变（A.4.1-3），
--   本迁移为新文件 ALTER，不触碰任何既有迁移。
-- 构建形态：可空列 + 稀疏部分唯一索引（WHERE client_msg_id IS NOT NULL AND deleted = 0）——
--   未携带键的存量工作站路径与非体温项逻辑删行不参与唯一约束，零行为影响；逻辑删行让出键位，
--   同键可在原行删除后重新落卡。
-- 登记义务：docs/migrations/flyway-version-registry.md V1014 行与 CHANGELOG.md 同步登记
--   （先记再改），本文件落盘即视为台账履行。

ALTER TABLE nursing.vital_sign_record ADD COLUMN client_msg_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_vital_sign_client_msg ON nursing.vital_sign_record (client_msg_id) WHERE client_msg_id IS NOT NULL AND deleted = 0;
