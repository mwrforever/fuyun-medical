package com.fuyun.system.controller;

import com.fuyun.system.service.IPermissionAdminService;
import com.fuyun.system.vo.PermissionGroupVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限点管理读端点（GET /api/v1/system/permissions，PR-4F Task 4）。
 *
 * <p>权限点与 V1120 种子 perm_code 对齐（GET /api/v1/system/permissions 为 ADMIN
 * 专属码、不种绑定行——F5 运行期全放）；受 401 认证 + 403 鉴权双拦截。读端点不挂
 * @AuditLog（审计面=写操作）。职责边界：仅调用 service + 编排响应，禁业务逻辑与事务
 * （宪法 B.1/A.1-8，DictTypeController 同边界）。
 */
@RestController
@RequestMapping("/api/v1/system/permissions")
public class PermissionAdminController {

    private final IPermissionAdminService permissionAdminService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param permissionAdminService 权限点管理读服务，非空；注入接口类型（B.2-2）
     */
    public PermissionAdminController(IPermissionAdminService permissionAdminService) {
        this.permissionAdminService = permissionAdminService;
    }

    /**
     * 查询全量权限点分组清单（权限管理台矩阵编辑器渲染，契约型读豁免分页——权限点为
     * 百级以内字典性数据，理由见 IPermissionAdminService javadoc）。
     *
     * @return 200 + 按 MENU/API/ELEMENT 分组的权限点清单（组内按 perm_code 排序）
     */
    @GetMapping
    public List<PermissionGroupVO> listGrouped() {
        return permissionAdminService.listGrouped();
    }
}
