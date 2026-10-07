package com.fuyun.system.service;

import com.fuyun.system.vo.PermissionGroupVO;
import java.util.List;

/**
 * 权限点管理读服务（权限管理台权限点分组清单查询入口，PR-4F Task 4）。
 *
 * <p>聚合/报表型接口（宪法 A.4.3-20）：不继承 IService，实现注入所需 mapper 组装
 * 分组读视图。读端点与写端点（Task 5 矩阵覆写）分离——本接口只承载管理台矩阵编辑器
 * 的可配置权限点全集渲染（API/MENU/ELEMENT 三型分组）。
 */
public interface IPermissionAdminService {

    /**
     * 查询全量权限点并按类型分组（权限管理台矩阵编辑器渲染数据源）。
     *
     * <p>单表全量投影（perm_code/perm_name/perm_type）后内存按 permType 分组、组内按
     * perm_code 字典序排序；类型归属由 perm_type 列承载（非码形态解析——API 码含空格、
     * MENU 三段冒号码、ELEMENT 四段冒号码三态仅作数据自证）。前端矩阵编辑器再按域
     * 前缀细分组，本服务不预切域。
     *
     * @return 按类型分组的权限点清单（组内按 perm_code 排序）；空表时为空清单，非 null
     */
    List<PermissionGroupVO> listGrouped();
}
