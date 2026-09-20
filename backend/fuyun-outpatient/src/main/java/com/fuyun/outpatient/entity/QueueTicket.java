package com.fuyun.outpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 候诊票据实体（outpatient.queue_ticket，M03 候诊队列权威行）：queue_id=dept_code 诊区队列口径
 * （偏差⑧）；priority_score 冻结公式（类别分取最高单项+老幼残跨类叠加+封顶 999，偏差⑨）；Redis
 * ZSET（fy:outpatient:queue:{deptCode}）仅为加速视图，本表 WAITING 行为重启恢复权威（Spec :210）。
 * 同分排序以 queue_time 库端时间戳为权威、禁应用服务器时钟（防多实例漂移）。线程安全：可变实体
 * 仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("outpatient.queue_ticket")
public class QueueTicket {

    /** 雪花主键（ZSET member 与过号/重呼端点定位锚） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊号（uk_visit_id 同源） */
    private String visitId;

    /** 队列标识（=dept_code 诊区队列，P1 口径偏差⑧） */
    private String queueId;

    /** 票号（队列内当日序号，A+%03d 如 A007；uk_ticket_queue 队列内唯一） */
    private String ticketNo;

    /** 票别（TicketType：FIRST/VISIT/RETURN/EXTRA；P1 报到按 is_revisit 派生 FIRST/RETURN） */
    private TicketType ticketType;

    /** 指派医生（二次分诊定医生；null=未指派任一医生可叫），可空 */
    private String doctorId;

    /** 优先级分（冻结公式，0~999 CHECK 兜底） */
    private Integer priorityScore;

    /** 队列当日序（建行事务内 Redis INCR，ZSET score 编码低位） */
    private Integer queueSeq;

    /** 建行时间（库端 now()，同分排序权威） */
    private OffsetDateTime queueTime;

    /** 叫号次数（casCall 每叫累加） */
    private Integer calledCount;

    /** 最近叫号时间（casCall 库端 now() 回填），可空 */
    private OffsetDateTime callTime;

    /** 接诊时间（Task 8 admit 联动回填），可空 */
    private OffsetDateTime serveTime;

    /** 票据状态（TicketStatus：WAITING/CALLED/SERVING/SERVED/PASSED/CANCELLED） */
    private TicketStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列：操作人应用层注入 */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
