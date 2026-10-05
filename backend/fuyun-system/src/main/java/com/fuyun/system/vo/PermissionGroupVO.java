package com.fuyun.system.vo;

import com.fuyun.system.enums.PermissionType;
import java.util.List;

/**
 * 权限点分组出参（GET /api/v1/system/permissions 响应项，PR-4F Task 4）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。按 perm_type 分组、组内按
 * perm_code 排序；前端矩阵编辑器再按域前缀细分组（本出参不预切域）。
 *
 * @param permType 权限点类型（MENU/API/ELEMENT），非空；分组键由 perm_type 列承载
 * @param points   组内权限点清单（按 perm_code 排序），非 null；空组不出现在响应中
 */
public record PermissionGroupVO(PermissionType permType, List<PermissionPointVO> points) {

    /**
     * 权限点条目出参（矩阵编辑器的最小展示单元）。
     *
     * @param permCode 权限点编码，非空；API 型=METHOD+空格+路径、MENU 型=三段冒号码、
     *                 ELEMENT 型=四段冒号码（域:功能:btn|panel:动作）
     * @param permName 权限点名称，非空；管理台展示用
     */
    public record PermissionPointVO(String permCode, String permName) {}
}
