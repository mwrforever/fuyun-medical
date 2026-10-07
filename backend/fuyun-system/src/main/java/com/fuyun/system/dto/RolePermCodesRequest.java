package com.fuyun.system.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 角色权限矩阵全量覆写请求体（PUT /api/v1/system/roles/{roleCode}/permissions，PR-4F Task 5）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 * 全量覆写语义：permCodes 即目标态——空清单合法（清空该角色全部绑定）；元素级合法性
 * （未登记码）由服务端 SYS-1042 校验承载并在 detail 携带全部非法码，不在契约层重复。
 *
 * @param permCodes 目标权限码全集（API/MENU/ELEMENT 混出；允许重复提交，服务端去重保序），
 *                  非 null；来源：管理台矩阵编辑器提交
 */
public record RolePermCodesRequest(@NotNull List<String> permCodes) {}
