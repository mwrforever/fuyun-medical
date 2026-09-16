package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.service.PrivacyMaskService;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * 患者上下文解析单测（CF-3 冻结语义）：MERGED 链收敛主档、FROZEN 拦截标记、缓存优先、
 * 环路守卫、未命中 404 五条（验收 IT「冻结患者拦截」的单测层基座）。
 */
@ExtendWith(MockitoExtension.class)
class PatientContextResolverTest {

    @Mock
    private PatientFieldCrypto crypto;

    @Mock
    private PrivacyMaskService privacyMaskService;

    @Mock
    private PatientCacheService cacheService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** 内存表替身（id → 实体），承载 getById 多跳查询 */
    private final Map<Long, Patient> table = new HashMap<>();

    private PatientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PatientServiceImpl(crypto, privacyMaskService, cacheService, eventPublisher) {
            @Override
            public Patient getById(Serializable id) {
                return table.get(((Number) id).longValue());
            }
        };
        lenient().when(cacheService.getView(anyLong())).thenReturn(Optional.empty());
    }

    private Patient row(long id, String status, Long mergedInto) {
        Patient p = new Patient();
        p.setPatientId(id);
        p.setStatus(status);
        p.setMergedIntoPatientId(mergedInto);
        return p;
    }

    @Test
    @DisplayName("从档解析收敛主档（MERGED 链一跳）且不拦截")
    void mergedArchiveResolvesToSurvivor() {
        table.put(1L, row(1L, "NORMAL", null));
        table.put(2L, row(2L, "MERGED", 1L));
        PatientContextView view = service.resolve(2L);
        assertThat(view.patientId()).isEqualTo(2L);
        assertThat(view.resolvedPatientId()).isEqualTo(1L);
        assertThat(view.blocked()).isFalse();
    }

    @Test
    @DisplayName("MERGED 但合并指针为空：终止收敛返回自身视图（指针脏数据不拦截）")
    void mergedArchiveWithNullPointerResolvesToItself() {
        table.put(8L, row(8L, "MERGED", null));
        PatientContextView view = service.resolve(8L);
        assertThat(view.resolvedPatientId()).isEqualTo(8L);
        assertThat(view.status()).isEqualTo("MERGED");
        assertThat(view.blocked()).isFalse();
    }

    @Test
    @DisplayName("冻结主档返回拦截标记（业务模块拒绝新就诊依据）")
    void frozenSurvivorYieldsBlockedView() {
        table.put(1L, row(1L, "FROZEN", null));
        PatientContextView view = service.resolve(1L);
        assertThat(view.blocked()).isTrue();
        assertThat(view.blockReason()).contains("冻结");
    }

    @Test
    @DisplayName("缓存命中优先（不回源 DB）")
    void cacheHitSkipsDbLoad() {
        when(cacheService.getView(9L)).thenReturn(Optional.of(new PatientContextView(9L, 9L, "NORMAL", false, "")));
        PatientContextView view = service.resolve(9L);
        assertThat(view.patientId()).isEqualTo(9L);
        assertThat(table).isEmpty();
    }

    @Test
    @DisplayName("合并指针环（自指）触发 5 跳守卫 → PAT 状态异常而非死循环")
    void pointerLoopGuardFailsFast() {
        table.put(7L, row(7L, "MERGED", 7L));
        assertThatThrownBy(() -> service.resolve(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PatientErrorCode.PATIENT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("档案不存在 → PAT-1001（404）")
    void missingArchiveYieldsNotFound() {
        assertThatThrownBy(() -> service.resolve(404L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }
}
