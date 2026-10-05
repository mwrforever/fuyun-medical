package com.fuyun.system.controller;

import com.fuyun.system.service.IRoleAdminService;
import com.fuyun.system.vo.RoleAdminVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理读端点（GET /api/v1/system/roles，PR-4F Task 4）。
 *
 * <p>权限点与 V1120 种子 perm_code 对齐（GET /api/v1/system/roles 为 ADMIN 专属码、
 * 不种绑定行——F5 运行期全放）；受 401 认证 + 403 鉴权双拦截。读端点不挂 @AuditLog
 * （审计面=写操作，读操作不落审计日志）。职责边界：仅调用 service + 编排响应，禁业务
 * 逻辑与事务（宪法 B.1/A.1-8，DictTypeController 同边界）。
 */
@RestController
@RequestMapping("/api/v1/system/roles")
public class RoleAdminController {

    private final IRoleAdminService roleAdminService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param roleAdminService 角色管理读服务，非空；注入接口类型（B.2-2）
     */
    public RoleAdminController(IRoleAdminService roleAdminService) {
        this.roleAdminService = roleAdminService;
    }

    /**
     * 查询全量角色及各自绑定码集（权限管理台首屏渲染，契约型读豁免分页——角色为百级以内
     * 字典性数据，理由见 IRoleAdminService javadoc）。
     *
     * @return 200 + 角色清单（含 ADMIN 行——permCodes=空清单，运行期全放语义由前端特殊渲染，D3）
     */
    @GetMapping
    public List<RoleAdminVO> listRoles() {
        return roleAdminService.listRoles();
    }
}
