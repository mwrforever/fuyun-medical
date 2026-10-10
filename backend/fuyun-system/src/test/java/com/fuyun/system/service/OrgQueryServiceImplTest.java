package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.entity.OrgEntity;
import com.fuyun.system.enums.OrgAttr;
import com.fuyun.system.enums.OrgStatus;
import com.fuyun.system.enums.OrgType;
import com.fuyun.system.mapper.OrgMapper;
import com.fuyun.system.service.impl.OrgQueryServiceImpl;
import com.fuyun.system.vo.OrgVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 组织机构清单查询服务单测（GET /api/v1/system/orgs 执行点，M01 演示链路真数据源切片）：
 * 覆盖 ①WARD/DEPT 两类型查询（启用状态过滤 + sort 升序 + orgCode 唯一次序键的清单透传与
 * 逐列映射）；②非法类型 code SYS-1031/400 拒绝（OrgType.fromCode 收口，禁散落裸 IAE）；
 * ③空清单返回空数组非 null。mapper 以 Mockito 模拟（DictQueryServiceImplTest 同款形态；
 * wrapper 装配与 ORDER BY 的 SQL 语义由 Testcontainers IT 于 CI verify 承载——本地 Docker
 * 不可用，IT 不本地执行）。
 */
@ExtendWith(MockitoExtension.class)
class OrgQueryServiceImplTest {

    @Mock
    private OrgMapper orgMapper;

    private OrgQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次，DictQueryServiceImplTest 先例）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrgEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrgQueryServiceImpl(orgMapper);
    }

    @Test
    @DisplayName("WARD 清单：启用病区按 sort 升序返回且实体字段逐列映射出参")
    void listByTypeReturnsActiveWardsInSortOrderWithFieldMapping() {
        when(orgMapper.selectList(any()))
                .thenReturn(List.of(
                        ward(1123000000000000001L, "W01", "演示病区", 1), ward(1123000000000000002L, "1001", "演示病区", 2)));

        List<OrgVO> orgs = service.listByType("WARD");

        // 清单按 mapper 返回序透传（DB 侧 ORDER BY sort 升序，本测锚定映射不改序）
        assertThat(orgs).hasSize(2);
        OrgVO first = orgs.get(0);
        assertThat(first.id()).isEqualTo(1123000000000000001L);
        assertThat(first.orgCode()).isEqualTo("W01");
        assertThat(first.orgName()).isEqualTo("演示病区");
        assertThat(first.orgType()).isEqualTo(OrgType.WARD);
        assertThat(first.orgAttr()).isEqualTo(OrgAttr.CLINICAL);
        assertThat(first.parentId()).isNull();
        assertThat(first.sort()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(OrgStatus.ACTIVE);
        assertThat(orgs.get(1).orgCode()).isEqualTo("1001");
    }

    @Test
    @DisplayName("DEPT 清单：科室类型同口径返回（内科演示链路）")
    void listByTypeReturnsDeptsSameContract() {
        when(orgMapper.selectList(any())).thenReturn(List.of(ward(1123000000000000003L, "DEPT-INT", "内科", 1)));

        List<OrgVO> orgs = service.listByType("DEPT");

        assertThat(orgs).hasSize(1);
        assertThat(orgs.get(0).orgCode()).isEqualTo("DEPT-INT");
        assertThat(orgs.get(0).orgName()).isEqualTo("内科");
        assertThat(orgs.get(0).orgType()).isEqualTo(OrgType.DEPT);
    }

    @Test
    @DisplayName("非法类型 code：SYS-1031/400 拒绝（不触达 mapper）")
    void listByTypeRejectsUnknownTypeCodeWithBizException() {
        assertThatThrownBy(() -> service.listByType("CLINIC")).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.ENUM_VALUE_INVALID);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    @DisplayName("无启用机构：返回空数组非 null（前端空态直渲染）")
    void listByTypeReturnsEmptyListWhenNoActiveOrg() {
        when(orgMapper.selectList(any())).thenReturn(List.of());

        List<OrgVO> orgs = service.listByType("WARD");

        assertThat(orgs).isNotNull();
        assertThat(orgs).isEmpty();
    }

    /** 构造启用病区/科室实体样本（parentId 置空表达 P0 邻接表根节点口径） */
    private OrgEntity ward(Long id, String orgCode, String orgName, int sort) {
        OrgEntity entity = new OrgEntity();
        entity.setId(id);
        entity.setOrgCode(orgCode);
        entity.setOrgName(orgName);
        entity.setOrgType(orgCode.startsWith("DEPT-") ? OrgType.DEPT : OrgType.WARD);
        entity.setOrgAttr(OrgAttr.CLINICAL);
        entity.setParentId(null);
        entity.setSort(sort);
        entity.setStatus(OrgStatus.ACTIVE);
        return entity;
    }
}
