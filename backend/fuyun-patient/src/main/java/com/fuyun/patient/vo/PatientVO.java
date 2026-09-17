package com.fuyun.patient.vo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 患者档案出参（GET /patients/{id} 与 /patients/search 行载体）：敏感字段为脱敏后文本
 * （PrivacyMaskService 就地脱敏），明文只经明文查阅 API 且双留痕（M02 红线 3）。
 */
@Getter
@Setter
public class PatientVO {

    /** 患者主索引（全局 Jackson Long→String，前端 string 承载） */
    private Long patientId;

    /** 姓名（脱敏后：张*） */
    private String name;

    /** 性别字典 code */
    private String sex;

    /** 出生日期（脱敏规则启用时退化为年份首日 yyyy-01-01） */
    private LocalDate birthDate;

    /** 证件号（脱敏后：110101********7890） */
    private String idCardNo;

    /** 手机号（脱敏后：138****1234） */
    private String mobile;

    /** 住址（保留省市前缀） */
    private String address;

    /** 状态：NORMAL/FROZEN/MERGED */
    private String status;

    /** 合并指针（MERGED 时非空） */
    private Long mergedIntoPatientId;

    /** 实名标记 */
    private Boolean realNameFlag;

    /** 建档渠道 */
    private String registerChannel;

    /** 档案来源 */
    private String archiveSource;

    /** 建档时刻 */
    private OffsetDateTime createdAt;
}
