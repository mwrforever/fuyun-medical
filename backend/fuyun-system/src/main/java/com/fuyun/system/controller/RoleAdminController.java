package com.fuyun.system.controller;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.dto.RolePermCodesRequest;
import com.fuyun.system.dto.RoleStatusRequest;
import com.fuyun.system.service.IRoleAdminService;
import com.fuyun.system.vo.RoleAdminVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理端点（/api/v1/system/roles/**，PR-4F Task 4 读端点 + Task 5 写端点）。
 *
 * <p>权限点与 V1120 种子 perm_code 逐字对齐（GET 角色清单与两 PUT 写端点均为 ADMIN 专属码、
 * 不种绑定行——F5 运行期全放）；受 401 认证 + 403 鉴权双拦截。审计面=写操作：两 PUT 端点挂
 * @AuditLog(WRITE)（F5；DictTypeController 先例），读端点不落审计日志。职责边界：仅 @Valid
 * 校验 + 调用 service + 编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8，DictTypeController 同边界）。
 */
@RestController
@RequestMapping("/api/v1/system/roles")
public class RoleAdminController {

    private final IRoleAdminService roleAdminService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param roleAdminService 角色管理服务，非空；注入接口类型（B.2-2）
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

    /**
     * 角色权限矩阵全量覆写（管理台矩阵编辑器保存）：载荷即目标态，diff 删插 + 事务内发布
     * 矩阵变更事件；无变化幂等短路零写零事件。
     *
     * @param roleCode 角色编码（路径参数），非空；与 V1120 API 码模板变量逐字一致
     * @param request  覆写请求，非空；permCodes 非 null（空清单=清空绑定）
     * @return 200 + 覆写后角色视图（permCodes=去重保序载荷即落库终态）
     */
    @PutMapping("/{roleCode}/permissions")
    @AuditLog(actionType = AuditActionType.WRITE)
    public RoleAdminVO overwritePermissions(
            @PathVariable("roleCode") String roleCode, @Valid @RequestBody RolePermCodesRequest request) {
        return roleAdminService.overwritePermissions(roleCode, request.permCodes());
    }

    /**
     * 角色启停（管理台角色行启停开关）：停用角色禁配新用户，存量会话摘要不回溯撤销。
     *
     * @param roleCode 角色编码（路径参数），非空；与 V1120 API 码模板变量逐字一致
     * @param request  启停请求，非空；status 取 ACTIVE/DISABLED（未知值 SYS-1031 拒绝）
     * @return 200 + 更新后角色视图（permCodes=当前绑定码集实查）
     */
    @PutMapping("/{roleCode}/status")
    @AuditLog(actionType = AuditActionType.WRITE)
    public RoleAdminVO updateStatus(
            @PathVariable("roleCode") String roleCode, @Valid @RequestBody RoleStatusRequest request) {
        return roleAdminService.updateStatus(roleCode, request.status());
    }
}
