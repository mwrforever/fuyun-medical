package com.fuyun.system.vo;

import java.util.List;

/**
 * 角色管理台出参（GET /api/v1/system/roles 响应项，PR-4F Task 4）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。status/dataScopeType 以存储 code
 * 字符串输出（与前端管理台字典对照消费面一致，不透出枚举类型形态）。
 *
 * @param roleCode       角色编码（如 ADMIN），非空；内置超管角色的特殊渲染锚（D3 注记）
 * @param roleName       角色名称，非空
 * @param status         启停状态 code（ACTIVE/DISABLED），非空；管理台需展示并允许操作启停（含停用角色）
 * @param dataScopeType  数据范围 code（ALL/HOSP/DEPT/WARD/SELF），非空
 * @param permCodes      绑定权限点编码清单（API/MENU/ELEMENT 混出，F2 形态 A）；ADMIN 无绑定行
 *                       与未绑定角色为空清单非 null——ADMIN 运行期全放语义由前端特殊渲染（D3）
 */
public record RoleAdminVO(
        String roleCode, String roleName, String status, String dataScopeType, List<String> permCodes) {}
