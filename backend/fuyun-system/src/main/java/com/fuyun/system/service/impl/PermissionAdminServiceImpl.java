package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.enums.PermissionType;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.service.IPermissionAdminService;
import com.fuyun.system.vo.PermissionGroupVO;
import com.fuyun.system.vo.PermissionGroupVO.PermissionPointVO;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限点管理读服务实现（权限管理台权限点分组清单组装，PR-4F Task 4）。
 *
 * <p>单表全量投影（精确取 perm_code/perm_name/perm_type，A.4.3-14；deleted=0 由
 * @TableLogic 自动携带）后内存按 permType 分组（EnumMap——组序随枚举声明序，确定性
 * 输出）+ 组内按 perm_code 排序。类型归属由 perm_type 列承载（非码形态解析——API/
 * MENU/ELEMENT 三态码形态仅作数据自证）。只读查询方法级只读事务（A.4.2-7）。
 *
 * <p>装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为 SystemWebConfig @Import。
 */
@Slf4j
public class PermissionAdminServiceImpl implements IPermissionAdminService {

    /** 权限点数据访问：单表全量投影载体 */
    private final PermissionMapper permissionMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param permissionMapper 权限点 mapper，非空；来源：同模块 mapper 包（@MapperScan 注册）
     */
    public PermissionAdminServiceImpl(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /**
     * 查询全量权限点并按类型分组（权限管理台矩阵编辑器渲染数据源）。
     *
     * <p>执行流程（单表全量 + 内存分组）：sys_permission 全量三列投影 → EnumMap 按
     * permType 分组 → 组内按 perm_code 字典序排序。空表直接返回空清单。
     *
     * @return 按类型分组的权限点清单（组序随枚举声明序、组内按 perm_code 排序）；空表为空清单，非 null
     */
    @Override
    @Transactional(readOnly = true)
    public List<PermissionGroupVO> listGrouped() {
        List<PermissionEntity> permissions = permissionMapper.selectList(Wrappers.<PermissionEntity>lambdaQuery()
                .select(PermissionEntity::getPermCode, PermissionEntity::getPermName, PermissionEntity::getPermType));
        if (permissions.isEmpty()) {
            return List.of();
        }
        // EnumMap 分组：组序随枚举声明序（MENU/API/ELEMENT）确定，前端无需再排组
        Map<PermissionType, List<PermissionEntity>> byType = permissions.stream()
                .collect(Collectors.groupingBy(
                        PermissionEntity::getPermType, () -> new EnumMap<>(PermissionType.class), Collectors.toList()));
        List<PermissionGroupVO> result = byType.entrySet().stream()
                .map(entry -> new PermissionGroupVO(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(PermissionEntity::getPermCode))
                                .map(permission ->
                                        new PermissionPointVO(permission.getPermCode(), permission.getPermName()))
                                .toList()))
                .toList();
        log.debug("管理台权限点分组查询完成：groupCount={}，pointCount={}", result.size(), permissions.size());
        return result;
    }
}
