package com.fuyun.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Modulith 事件发布注册表只读投影（public.event_publication，V500 迁移；D-2 裁决载体）。
 *
 * <p>只读边界：表的写入与清理由 Spring Modulith 框架（JDBC 事件注册表）与 EventOpsJob 承担，
 * 本实体仅供治理查询面投影，禁止经本实体写库（无 setter 使用场景亦不得新增写方法）。
 *
 * <p>刻意不映射 serialized_event 列：该列为框架自用的整事件序列化载荷（含业务数据），
 * 查询面不需要且不应外泄；MP 生成的 SELECT 仅含本类声明列（按需取列，A.4.3-14）。
 *
 * <p>id 用 IdType.INPUT：框架以 UUID 主键写入，本投影不生成 ID。
 */
@Getter
@Setter
@TableName("event_publication")
public class EventPublication {

    /** 发布记录主键（框架生成的 UUID） */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 监听器标识（框架以「类名.方法名」形态记录事件监听目标） */
    private String listenerId;

    /** 事件类型全限定名（Java 类名，非 event_registry 的 &lt;模块&gt;.&lt;实体&gt;.&lt;动作&gt; 命名） */
    private String eventType;

    /** 发布时刻（业务事务内暂存时间） */
    private OffsetDateTime publicationDate;

    /** 完成时刻；为空 = 未完成（监听失败或实例宕机，待 EventOpsJob 按 5 分钟阈值重投） */
    private OffsetDateTime completionDate;
}
