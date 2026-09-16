package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.vo.CardTxnVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 一卡通账户端点薄层单测：流水分页 200（0 基分页契约直出）与销户 204 无体返回
 * （controller 禁业务逻辑与事务，状态机守卫归 service 单测）。
 */
@ExtendWith(MockitoExtension.class)
class CardAccountControllerTest {

    @Mock
    private ICardAccountService cardAccountService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new CardAccountController(cardAccountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("账户流水分页：0 基分页参数透传，台账四字段契约直出（200）")
    void listTxnsReturnsLedgerPage() throws Exception {
        CardTxnVO vo = new CardTxnVO();
        vo.setId(88L);
        vo.setTxnType("RECHARGE");
        vo.setAmount(5000L);
        vo.setBalanceAfter(5000L);
        vo.setBizRef("BILL-1");
        when(cardAccountService.listTxns(7L, 0, 20)).thenReturn(PageResult.of(List.of(vo), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/patient/card-accounts/7/txns?page=0&size=20"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"balanceAfter\":5000")
                .contains("\"total\":1")
                .contains("\"page\":0");
        verify(cardAccountService).listTxns(7L, 0, 20);
    }

    @Test
    @DisplayName("销户端点：204 无体返回（WRITE 审计落点由切面承载）")
    void closeReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/patient/card-accounts/7/close")).andExpect(status().isNoContent());
        verify(cardAccountService).close(7L);
    }
}
