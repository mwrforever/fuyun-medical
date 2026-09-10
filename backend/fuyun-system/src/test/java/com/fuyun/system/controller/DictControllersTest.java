package com.fuyun.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.service.IDictItemService;
import com.fuyun.system.service.IDictQueryService;
import com.fuyun.system.service.IDictTypeService;
import com.fuyun.system.service.IDictVersionService;
import com.fuyun.system.vo.DictItemVO;
import com.fuyun.system.vo.DictTypeVO;
import com.fuyun.system.vo.DictVersionVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 字典端点单元测试（controller 编排薄层）：入参透传、缓存协商头与发布端点 200 语义。
 *
 * <p>覆盖：类型创建/版本创建透传、条目新增透传、发布端点透传（void 返回 200）、
 * 读端点版本参数透传 + Cache-Control: no-cache 协商头。
 */
@ExtendWith(MockitoExtension.class)
class DictControllersTest {

    @Mock
    private IDictTypeService dictTypeService;

    @Mock
    private IDictVersionService dictVersionService;

    @Mock
    private IDictItemService dictItemService;

    @Mock
    private IDictQueryService dictQueryService;

    private DictTypeController typeController;

    private DictVersionController versionController;

    private DictController queryController;

    @BeforeEach
    void setUp() {
        typeController = new DictTypeController(dictTypeService, dictVersionService);
        versionController = new DictVersionController(dictItemService, dictVersionService);
        queryController = new DictController(dictQueryService);
    }

    @Test
    @DisplayName("类型创建端点：请求透传服务层，响应直返")
    void createTypeDelegatesToService() {
        DictTypeCreateRequest request = new DictTypeCreateRequest("gender", "性别字典", null, null);
        DictTypeVO expected = new DictTypeVO(9001L, "gender", "性别字典", false, null);
        when(dictTypeService.createType(request)).thenReturn(expected);

        assertThat(typeController.createType(request)).isSameAs(expected);
    }

    @Test
    @DisplayName("版本创建端点：类型编码路径参数透传，响应直返")
    void createVersionDelegatesToService() {
        DictVersionVO expected = new DictVersionVO("gender", 1, DictVersionStatus.DRAFT, null, List.of());
        when(dictVersionService.createVersion("gender")).thenReturn(expected);

        assertThat(typeController.createVersion("gender")).isSameAs(expected);
    }

    @Test
    @DisplayName("条目新增端点：版本 ID 与请求透传服务层，响应直返")
    void addItemDelegatesToService() {
        DictItemCreateRequest request = new DictItemCreateRequest("M", "男", null, null);
        DictItemVO expected = new DictItemVO("M", "男", null, 0);
        when(dictItemService.addItem(8101L, request)).thenReturn(expected);

        assertThat(versionController.addItem(8101L, request)).isSameAs(expected);
    }

    @Test
    @DisplayName("发布端点：版本 ID 透传服务层，无响应体返回（状态迁移语义）")
    void publishDelegatesToService() {
        versionController.publish(8101L);

        verify(dictVersionService).publish(8101L);
    }

    @Test
    @DisplayName("读端点：版本参数透传且响应携带 Cache-Control: no-cache 协商头")
    void readVersionDelegatesAndSetsNoCacheHeader() {
        DictVersionVO expected = new DictVersionVO("gender", 2, DictVersionStatus.PUBLISHED, null, List.of());
        when(dictQueryService.readPublished("gender", null)).thenReturn(expected);

        ResponseEntity<DictVersionVO> response = queryController.readVersion("gender", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
        assertThat(response.getBody()).isSameAs(expected);
    }
}
