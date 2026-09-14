-- V500：Spring Modulith 事件发布注册表（D-2 裁决引入，宪法 B.3-3 可靠事件投递载体）。
-- 版本取 500 段顺延 iot 400 段：真库历史已含 system 300 段与 iot 400 段，低版本号会被 Flyway 判 out-of-order 拒绝——真栈探针 2026-09-14 实证。
-- DDL 与 spring-modulith-events-jdbc 1.4.13 官方 V1 schema（schema-postgresql.sql）逐列一致；
-- 建表只经 Flyway（宪法 A.4.1），应用侧 spring.modulith.events.jdbc.schema-initialization.enabled=false。
-- 表放公共 schema：框架按数据源默认 search_path 以非限定名访问，与 A.5-14 锁表同域；
-- 幂等语义：同一事件发布行由框架以 id 主键管理，业务消费幂等仍由 received_event 承担（A.5-6）。
CREATE TABLE IF NOT EXISTS event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
