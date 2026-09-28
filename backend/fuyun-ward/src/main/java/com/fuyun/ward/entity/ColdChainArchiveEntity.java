package com.fuyun.ward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.enums.TempRangeType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 冷链档案实体（ward.cold_chain_archive，V1101 迁移）：四类用途冷链监测档案，温度曲线经
 * IotTelemetryQueryPort 查询（ward 不落温度读数）。
 *
 * <p>雪花代理主键（@TableId(ASSIGN_ID)）；对外标识为 archive_no 业务号（WardSeqGate.nextArchiveNo，
 * ARCH{yyyyMMdd}{%05d}）；updated_at 由数据库触发器统一维护。
 */
@Getter
@Setter
@TableName("ward.cold_chain_archive")
public class ColdChainArchiveEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 档案业务号（ARCH{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一） */
    private String archiveNo;

    /** 用途：VACCINE/BLOOD/REAGENT/PHARMA */
    private ColdChainPurpose purpose;

    /** 监测设备号（温度曲线经 Port 查询） */
    private String deviceId;

    /** 温度区间类型：FREEZE/COOL/SHELDED/NORMAL */
    private TempRangeType tempRangeType;

    /** 校验/验证到期时刻（未约定验证计划为空），可空 */
    private OffsetDateTime verifyDueAt;

    /** 存量清单摘要（盘点留痕文本），可空 */
    private String inventoryDigest;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：种子/系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;

    /** 逻辑删除标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
