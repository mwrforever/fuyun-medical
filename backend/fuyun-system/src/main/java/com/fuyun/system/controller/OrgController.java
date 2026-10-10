package com.fuyun.system.controller;

import com.fuyun.system.service.IOrgQueryService;
import com.fuyun.system.vo.OrgVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织机构清单读端点（GET /api/v1/system/orgs?type=，M01 演示链路真数据源切片）：前端
 * workstation 病区/科室下拉的真数据契约——替代各 api 模块 WARD_OPTIONS 与门诊视图
 * DEPT-INT 假常量。受 401 认证拦截（权限点 GET /api/v1/system/orgs 随 V1124 种子登记，
 * 六业务角色共享读面 + ADMIN 运行期全放，V1117 字典读共享面同款口径）。
 *
 * <p>controller 禁业务逻辑与事务（宪法 A.1-8），仅参数透传与响应编排（DictController 薄层同款）。
 */
@Tag(name = "M01 系统与权限管理", description = "组织机构清单读端点（病区/科室，启用态，sort 升序）")
@RestController
@RequestMapping("/api/v1/system/orgs")
public class OrgController {

    private final IOrgQueryService orgQueryService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param orgQueryService 组织机构清单读服务，非空；注入接口类型（B.2-2）
     */
    public OrgController(IOrgQueryService orgQueryService) {
        this.orgQueryService = orgQueryService;
    }

    /**
     * 按类型查询启用状态机构清单（契约型读豁免分页，理由见 IOrgQueryService javadoc）。
     *
     * @param type 机构类型 code（查询参数，必填）：WARD=病区 / DEPT=科室；缺参 400、
     *             非法值 SYS-1031/400（OrgType.fromCode 收口）
     * @return 200 + 机构清单（sort 升序，Long 字段字符串化出网）；无匹配返回空数组非 null
     */
    @Operation(
            summary = "组织机构清单（按类型，启用态，sort 升序）",
            operationId = "listOrgs",
            description = "前端病区/科室下拉真数据源；type=WARD|DEPT，非法值 SYS-1031/400")
    @GetMapping
    public List<OrgVO> listOrgs(@RequestParam("type") String type) {
        return orgQueryService.listByType(type);
    }
}
