package com.fuyun.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.enums.PermissionType;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.vo.PermissionGroupVO;
import com.fuyun.system.vo.PermissionGroupVO.PermissionPointVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 权限点管理读服务单元测试（权限管理台权限点分组清单，PR-4F Task 4）。
 *
 * <p>覆盖 listGrouped 单表全量分组语义：①API/MENU/ELEMENT 三型混出各归其位且组内按
 * perm_code 排序（前端矩阵编辑器再按域前缀细分组）；②ELEMENT 组 permCode 形态含
 * 四段冒号码的 :btn: 段（类型归属由 perm_type 列承载而非码形态解析，形态断言仅作数据自证）；
 * ③空表返回空清单。mapper 以 Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class PermissionAdminServiceImplTest {

    @Mock
    private PermissionMapper permissionMapper;

    private PermissionAdminServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单表全量投影的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PermissionEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new PermissionAdminServiceImpl(permissionMapper);
    }

    @Test
    @DisplayName("三型混出各归其位：组序随枚举声明序（MENU/API/ELEMENT）锁定且组内按 perm_code 排序（乱序入库自证）")
    void listGroupedGroupsByTypeAndSortsPointsByCode() {
        // 乱序返回（类型与码序均乱）：分组与排序语义由 service 内存组装承载
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(
                        perm(11L, "nursing:ward:btn:task", "病区任务按钮", PermissionType.ELEMENT),
                        perm(1L, "GET /api/v1/nursing/tasks", "护理任务查询", PermissionType.API),
                        perm(21L, "billing:refund:btn:approve", "退费审批按钮", PermissionType.ELEMENT),
                        perm(31L, "system:permission:manage", "权限管理台菜单", PermissionType.MENU),
                        perm(2L, "GET /api/v1/system/roles", "角色清单读取", PermissionType.API)));

        List<PermissionGroupVO> groups = service.listGrouped();

        // 组序确定性锁定（Task 4 审查遗留收口）：EnumMap 按枚举声明序迭代，前端免再排组——
        // 严格序断言锁实现现状（MENU→API→ELEMENT），非改语义
        assertThat(groups)
                .extracting(PermissionGroupVO::permType)
                .containsExactly(PermissionType.MENU, PermissionType.API, PermissionType.ELEMENT);
        assertThat(codesOf(groups, PermissionType.ELEMENT))
                .containsExactly("billing:refund:btn:approve", "nursing:ward:btn:task");
        assertThat(codesOf(groups, PermissionType.API))
                .containsExactly("GET /api/v1/nursing/tasks", "GET /api/v1/system/roles");
        assertThat(codesOf(groups, PermissionType.MENU)).containsExactly("system:permission:manage");
        // 点位名称随码同序透出（矩阵编辑器展示双字段）
        assertThat(pointsOf(groups, PermissionType.ELEMENT).get(0))
                .isEqualTo(new PermissionPointVO("billing:refund:btn:approve", "退费审批按钮"));
    }

    @Test
    @DisplayName("ELEMENT 组码形态自证：四段冒号码含 :btn: 段（类型归属仍由 perm_type 列承载）")
    void listGroupedKeepsFourSegmentElementCodes() {
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(
                        perm(11L, "nursing:ward:btn:task", "病区任务按钮", PermissionType.ELEMENT),
                        perm(12L, "patient:archive:panel:list", "患者档案面板", PermissionType.ELEMENT)));

        List<PermissionGroupVO> groups = service.listGrouped();

        // 形态断言仅作数据自证（V1120 种子码为四段 域:功能:btn|panel:动作，含 :btn: 段）；分组判定不依赖码形态
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).permType()).isEqualTo(PermissionType.ELEMENT);
        assertThat(codesOf(groups, PermissionType.ELEMENT))
                .anySatisfy(code -> assertThat(code).contains(":btn:"));
        assertThat(codesOf(groups, PermissionType.ELEMENT))
                .allSatisfy(code -> assertThat(code.split(":")).hasSize(4));
    }

    @Test
    @DisplayName("空表：返回空清单非 null（管理台首屏空态）")
    void listGroupedReturnsEmptyWhenTableEmpty() {
        when(permissionMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listGrouped()).isEmpty();
    }

    /** 构造权限点样本（id/码/名/类型四字段承载分组组装） */
    private PermissionEntity perm(Long permissionId, String permCode, String permName, PermissionType permType) {
        PermissionEntity permission = new PermissionEntity();
        permission.setId(permissionId);
        permission.setPermCode(permCode);
        permission.setPermName(permName);
        permission.setPermType(permType);
        return permission;
    }

    /** 取指定类型组的点位清单 */
    private List<PermissionPointVO> pointsOf(List<PermissionGroupVO> groups, PermissionType permType) {
        return groups.stream()
                .filter(group -> group.permType() == permType)
                .findFirst()
                .orElseThrow()
                .points();
    }

    /** 取指定类型组的编码清单 */
    private List<String> codesOf(List<PermissionGroupVO> groups, PermissionType permType) {
        return pointsOf(groups, permType).stream()
                .map(PermissionPointVO::permCode)
                .toList();
    }
}
