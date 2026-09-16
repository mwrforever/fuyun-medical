package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 患者主索引实体（patient.patient）：全院唯一身份权威源（M02 红线 1），主键即 patient_id（本模块签发）。
 *
 * <p>加密列语义：idCardNoCipher/mobileCipher/addressCipher 为 AES-GCM 密文（PatientFieldCrypto 产物），
 * idCardNoHash/mobileHash 为 HMAC 盲索引（等值检索唯一依据）；应用层任何写路径禁止落明文。
 * updated_at 由数据库触发器维护（V100）；deleted 逻辑删（索引仅在 deleted=0 生效）。
 */
@Getter
@Setter
@TableName("patient.patient")
public class Patient {

    /** 主索引（雪花，MP ASSIGN_ID 插入时生成，红线 1 全院唯一）；主键即业务键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long patientId;

    /** 姓名（明文存，检索需要；展示侧经脱敏规则引擎） */
    private String name;

    /** 姓名拼音（弱标识匹配首轮索引与检索） */
    private String namePinyin;

    /** 性别（M01 国标字典 code） */
    private String sex;

    /** 出生日期（无证件临时档案可空） */
    private LocalDate birthDate;

    /** 民族（M01 国标字典 code） */
    private String ethnicity;

    /** 婚姻状况（字典 code） */
    private String maritalStatus;

    /** 职业 */
    private String occupation;

    /** ABO 血型（字典 code） */
    private String bloodType;

    /** 身份证号密文（AES-GCM，Base64） */
    private String idCardNoCipher;

    /** 身份证号 HMAC 盲索引（EMPI 强标识等值查） */
    private String idCardNoHash;

    /** 手机号密文 */
    private String mobileCipher;

    /** 手机号 HMAC 盲索引 */
    private String mobileHash;

    /** 住址密文（无检索需求不设 hash） */
    private String addressCipher;

    /** 状态机：NORMAL/FROZEN/MERGED（PatientStatus） */
    private String status;

    /** 合并指针（status=MERGED 时非空，读侧归一依据） */
    private Long mergedIntoPatientId;

    /** 实名标记（false=未实名：授权建档/核验降级） */
    private Boolean realNameFlag;

    /** 建档渠道（RegisterChannel） */
    private String registerChannel;

    /** 档案来源（ArchiveSource：STANDARD/TEMP_ANONYMOUS/TEMP_NEWBORN） */
    private String archiveSource;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护，应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：默认 'system'，业务写路径由应用层注入操作人 */
    private String createdBy;

    /** 更新人：同上 */
    private String updatedBy;

    /** 逻辑删除标记（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Integer deleted;
}
