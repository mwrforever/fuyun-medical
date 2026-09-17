package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.service.IMergeRecordService;
import com.fuyun.patient.service.IPossibleDuplicateService;
import com.fuyun.patient.vo.MergeRecordVO;
import com.fuyun.patient.vo.PossibleDuplicateVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 重复治理与合并端点薄层单测：待审列表 200、排除缺理由 400、排除成功 204、审批委托 200 出 COMPLETED
 * （对外 API 单测义务；审计与 RBAC 由切面/鉴权链在装配与真栈验证承载）。
 */
@ExtendWith(MockitoExtension.class)
class DuplicateMergeControllerTest {

    @Mock
    private IPossibleDuplicateService duplicateService;

    @Mock
    private IMergeRecordService mergeRecordService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new DuplicateMergeController(duplicateService, mergeRecordService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("待审列表：缺省 PENDING 分页 200 直出 {content,page,size,total}")
    void listReturnsPagedPendingRows() throws Exception {
        PossibleDuplicateVO row = new PossibleDuplicateVO();
        row.setId(5L);
        row.setPatientIdA(3L);
        row.setPatientIdB(9L);
        row.setStatus("PENDING");
        when(duplicateService.list("PENDING", 0, 20)).thenReturn(PageResult.of(List.of(row), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/patient/possible-duplicates"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"total\":1").contains("\"patientIdA\":3");
    }

    @Test
    @DisplayName("排除待审对：理由缺失 → 400（@Valid 非空校验）")
    void excludeWithoutNoteRejected() throws Exception {
        mockMvc.perform(post("/api/v1/patient/possible-duplicates/5/exclude")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("排除待审对：理由齐备 → 204（PENDING→EXCLUDED）")
    void excludeWithNoteReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/patient/possible-duplicates/5/exclude")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"同名非同人，证件号不同\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("审批合并：委托服务层（当前操作人回退 system）并返回 COMPLETED 出参")
    void approveDelegatesAndReturnsRecord() throws Exception {
        MergeRecordVO vo = new MergeRecordVO();
        vo.setId(9L);
        vo.setSurvivorPatientId(1L);
        vo.setMergedPatientId(2L);
        vo.setStatus("COMPLETED");
        when(mergeRecordService.approve(9L, "system")).thenReturn(vo);

        String body = mockMvc.perform(post("/api/v1/patient/merges/9/approve"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"status\":\"COMPLETED\"").contains("\"id\":9");
    }
}
