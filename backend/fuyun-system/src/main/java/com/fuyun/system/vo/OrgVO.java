package com.fuyun.system.vo;

import com.fuyun.system.enums.OrgAttr;
import com.fuyun.system.enums.OrgStatus;
import com.fuyun.system.enums.OrgType;

/**
 * 组织机构出参（GET /api/v1/system/orgs，M01 演示链路真数据源切片）：前端病区/科室下拉的
 * 真数据契约——替代 workstation 各 api 模块 WARD_OPTIONS 与门诊视图 DEPT-INT 假常量。
 * 字段对照 sys_org 实列（禁 Entity 直接出 API 层，宪法 A.7-3）；id/parentId 为雪花 Long，
 * 经全局 Jackson Long→String 以字符串出网（宪法 A.3-8 精度防线，前端以 string 承载）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。
 *
 * @param id       机构主键（雪花 ID，字符串化出网），非空
 * @param orgCode  机构编码，业务唯一（deleted=0 范围内），非空；病区/科室演示链路锚
 *                 （W01 对齐 nursing.nursing_ward_config V801 种子，1001 对齐 ward/iot 数字病区链路）
 * @param orgName  机构名称，非空
 * @param orgType  机构类型 code（CAMPUS/DEPT/WARD/TEAM，@JsonValue 出 code），非空
 * @param orgAttr  机构属性 code（CLINICAL/MEDTECH/ADMIN），非空
 * @param parentId 父机构 ID（字符串化出网），可空（null=P0 邻接表根节点）
 * @param sort     同级排序号（清单按 sort 升序返回，小者在前），非空
 * @param status   状态 code（ACTIVE/DISABLED；清单仅出启用态），非空
 */
public record OrgVO(
        Long id,
        String orgCode,
        String orgName,
        OrgType orgType,
        OrgAttr orgAttr,
        Long parentId,
        Integer sort,
        OrgStatus status) {}
