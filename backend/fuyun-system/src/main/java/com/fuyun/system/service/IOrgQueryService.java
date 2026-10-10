package com.fuyun.system.service;

import com.fuyun.system.vo.OrgVO;
import java.util.List;

/**
 * 组织机构清单读服务（GET /api/v1/system/orgs，M01 演示链路真数据源切片）：按机构类型返回
 * 启用状态机构清单，供前端病区/科室下拉消费（替代 WARD_OPTIONS / DEPT-INT 假常量）。
 *
 * <p>聚合读接口不继承 IService（宪法 A.4.3-20：聚合/报表型接口注入所需 mapper）。
 */
public interface IOrgQueryService {

    /**
     * 按类型查询启用状态机构清单：org_type 精确过滤 + status 仅 ACTIVE（停用机构不出网），
     * 按 sort 升序 + orgCode 唯一次序键返回。
     *
     * <p>分页豁免声明（宪法 A.4.3-17 例外，IDictQueryService 同款口径）：契约型读接口——
     * 前端下拉需全量启用机构做本地装载，机构量级由组织管理治理约束（院级几十量级），
     * 无分页语义，豁免理由随本接口 javadoc 固化。
     *
     * @param type 机构类型 code（WARD 病区/DEPT 科室等 OrgType 值域），非空；来源：GET 查询参数
     * @return 启用机构清单（sort 升序），非空；无匹配返回空数组非 null
     * @throws com.fuyun.common.exception.BizException SYS-1031（type 非 OrgType 值域，HTTP 400）
     */
    List<OrgVO> listByType(String type);
}
