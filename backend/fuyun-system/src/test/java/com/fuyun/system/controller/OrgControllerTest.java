package com.fuyun.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.enums.OrgAttr;
import com.fuyun.system.enums.OrgStatus;
import com.fuyun.system.enums.OrgType;
import com.fuyun.system.service.IOrgQueryService;
import com.fuyun.system.vo.OrgVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 组织机构清单端点单测（controller 编排薄层）：type 查询参数透传服务层、清单原样回传零加工
 * （DictControllersTest 同款形态锚定，宪法 B.1 controller 禁业务逻辑）。
 */
@ExtendWith(MockitoExtension.class)
class OrgControllerTest {

    @Mock
    private IOrgQueryService orgQueryService;

    private OrgController controller;

    @BeforeEach
    void setUp() {
        controller = new OrgController(orgQueryService);
    }

    @Test
    @DisplayName("listOrgs 端点：WARD 类型透传服务层并原样回传（编排零加工）")
    void listOrgsDelegatesWardTypeToService() {
        List<OrgVO> expected = List.of(new OrgVO(
                1123000000000000001L, "W01", "演示病区", OrgType.WARD, OrgAttr.CLINICAL, null, 1, OrgStatus.ACTIVE));
        when(orgQueryService.listByType("WARD")).thenReturn(expected);

        assertThat(controller.listOrgs("WARD")).isSameAs(expected);
        verify(orgQueryService).listByType("WARD");
    }

    @Test
    @DisplayName("listOrgs 端点：DEPT 类型同口径透传（病区/科室共用一读端点）")
    void listOrgsDelegatesDeptTypeToService() {
        List<OrgVO> expected = List.of(new OrgVO(
                1123000000000000003L, "DEPT-INT", "内科", OrgType.DEPT, OrgAttr.CLINICAL, null, 1, OrgStatus.ACTIVE));
        when(orgQueryService.listByType("DEPT")).thenReturn(expected);

        assertThat(controller.listOrgs("DEPT")).isSameAs(expected);
        verify(orgQueryService).listByType("DEPT");
    }
}
