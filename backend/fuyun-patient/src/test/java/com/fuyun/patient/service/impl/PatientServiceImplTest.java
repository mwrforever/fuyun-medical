package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientFrozenPayload;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.dto.PatientSearchQuery;
import com.fuyun.patient.dto.PatientUpdateRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.mapper.PatientMapper;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.vo.PatientVO;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 患者主索引服务单测（状态机 + 部分更新 + 检索分派 + 详情脱敏）：冻结/解冻守卫、变更清单与事件、
 * MERGED 拒改、keyword 形态分派等核心路径；getById/updateById/getBaseMapper 以测试子类覆写
 * （IService 继承方法的可测化替身）。
 */
@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock
    private PatientFieldCrypto crypto;

    @Mock
    private PrivacyMaskService privacyMaskService;

    @Mock
    private PatientCacheService cacheService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PatientMapper patientMapper;

    /** 被测服务（覆写三个 DB/链式触点） */
    private TestablePatientServiceImpl service;

    /** 测试替身：仅覆写 getById/updateById/getBaseMapper，其余继承能力原样 */
    static class TestablePatientServiceImpl extends PatientServiceImpl {
        Patient stored;

        private PatientMapper chainMapper;

        TestablePatientServiceImpl(
                Patient stored,
                PatientFieldCrypto c,
                PrivacyMaskService m,
                PatientCacheService ca,
                ApplicationEventPublisher p) {
            super(c, m, ca, p);
            this.stored = stored;
        }

        /** 检索链式查询用 mapper 替身注入（null 时回落父类，非检索用例零感知） */
        void useChainMapper(PatientMapper mapper) {
            this.chainMapper = mapper;
        }

        @Override
        public Patient getById(Serializable id) {
            return stored;
        }

        @Override
        public boolean updateById(Patient entity) {
            this.stored = entity;
            return true;
        }

        @Override
        public PatientMapper getBaseMapper() {
            return chainMapper != null ? chainMapper : super.getBaseMapper();
        }

        @Override
        public Class<Patient> getEntityClass() {
            // 直返实体类型：绕开默认实现的 mapper 代理反射抽取（Mockito mock 无 sqlSession 属性）
            return Patient.class;
        }
    }

    @BeforeEach
    void setUp() {
        Patient stored = new Patient();
        stored.setPatientId(5L);
        stored.setName("张三");
        stored.setSex("1");
        stored.setStatus("NORMAL");
        service = new TestablePatientServiceImpl(stored, crypto, privacyMaskService, cacheService, eventPublisher);
        lenient().when(crypto.hash(anyString())).thenReturn("hash");
        lenient().when(crypto.encrypt(any())).thenReturn("cipher");
        lenient().when(crypto.decrypt(any())).thenReturn("110101199003077890");
        lenient().when(privacyMaskService.applyAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("冻结：NORMAL→FROZEN 落库、缓存失效、发布 frozen 事件（载荷携原因）")
    void freezeTransitionsNormalToFrozenAndPublishes() {
        service.freeze(5L, "身份存疑");
        assertThat(service.stored.getStatus()).isEqualTo("FROZEN");
        verify(cacheService).evictView(5L);
        ArgumentCaptor<PatientDomainEvent> captor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PatientMessagingConstants.EVENT_FROZEN);
        assertThat(((PatientFrozenPayload) captor.getValue().payload()).reason())
                .isEqualTo("身份存疑");
    }

    @Test
    @DisplayName("重复冻结：PAT-1004 拒绝（第二次不发事件）")
    void doubleFreezeRejected() {
        service.freeze(5L, "第一次");
        assertThatThrownBy(() -> service.freeze(5L, "第二次")).isInstanceOf(BizException.class);
        verify(eventPublisher).publishEvent(any(PatientDomainEvent.class));
    }

    @Test
    @DisplayName("冻结不存在的档案：PAT-1001 拒绝")
    void freezeMissingArchiveRejected() {
        TestablePatientServiceImpl missing = newUnstoredService();
        assertThatThrownBy(() -> missing.freeze(5L, "身份存疑"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("冻结 MERGED 档案：PAT-1003 拒绝（已收敛主档不可变更状态）")
    void freezeMergedArchiveRejected() {
        service.stored.setStatus("MERGED");
        assertThatThrownBy(() -> service.freeze(5L, "身份存疑"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_ALREADY_MERGED);
    }

    @Test
    @DisplayName("解冻非冻结档案：PAT-1005 拒绝")
    void unfreezeNonFrozenRejected() {
        assertThatThrownBy(() -> service.unfreeze(5L)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("解冻：FROZEN→NORMAL 落库、缓存失效、发布 unfrozen 事件")
    void unfreezeRestoresFrozenToNormalAndPublishes() {
        service.stored.setStatus("FROZEN");
        service.unfreeze(5L);
        assertThat(service.stored.getStatus()).isEqualTo("NORMAL");
        verify(cacheService).evictView(5L);
        ArgumentCaptor<PatientDomainEvent> captor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PatientMessagingConstants.EVENT_UNFROZEN);
    }

    @Test
    @DisplayName("解冻不存在的档案：PAT-1001 拒绝")
    void unfreezeMissingArchiveRejected() {
        TestablePatientServiceImpl missing = newUnstoredService();
        assertThatThrownBy(() -> missing.unfreeze(5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("部分更新：仅变更字段重加密落库并回传变更清单，缓存失效 + updated 事件")
    void partialUpdateCollectsChangedFieldsAndPublishes() {
        service.stored.setMobileHash("old-hash");
        List<String> changed = service.update(
                5L, new PatientUpdateRequest(null, null, null, null, null, null, null, "13800001234", null));
        assertThat(changed).containsExactly("mobile");
        assertThat(service.stored.getMobileCipher()).isEqualTo("cipher");
        verify(cacheService).evictView(5L);
        verify(eventPublisher).publishEvent(any(PatientDomainEvent.class));
    }

    @Test
    @DisplayName("全字段更新：九个字段全部判定变更并回传完整清单")
    void fullUpdateCollectsAllChangedFields() {
        service.stored.setMobileHash("old-hash");
        service.stored.setBirthDate(LocalDate.of(1990, 3, 7));
        List<String> changed = service.update(
                5L,
                new PatientUpdateRequest("李四", "2", "1991-05-01", "01", "20", "医生", "A", "13900005678", "北京市海淀区yy路2号"));
        assertThat(changed)
                .containsExactly(
                        "name",
                        "sex",
                        "birthDate",
                        "ethnicity",
                        "maritalStatus",
                        "occupation",
                        "bloodType",
                        "mobile",
                        "address");
        assertThat(service.stored.getNamePinyin()).isEqualTo("李四");
        assertThat(service.stored.getAddressCipher()).isEqualTo("cipher");
    }

    @Test
    @DisplayName("无变更更新：空清单静默返回，不落库不发事件不失效缓存")
    void updateWithoutChangesReturnsEmptySilently() {
        List<String> changed =
                service.update(5L, new PatientUpdateRequest(null, null, null, null, null, null, null, null, null));
        assertThat(changed).isEmpty();
        verifyNoInteractions(cacheService, eventPublisher);
    }

    @Test
    @DisplayName("更新不存在的档案：PAT-1001 拒绝")
    void updateMissingArchiveRejected() {
        TestablePatientServiceImpl missing = newUnstoredService();
        assertThatThrownBy(() -> missing.update(
                        5L, new PatientUpdateRequest("李四", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("MERGED 档案拒绝更新：PAT-1003（主数据以主档为准）")
    void mergedArchiveRejectsUpdate() {
        service.stored.setStatus("MERGED");
        assertThatThrownBy(() -> service.update(
                        5L, new PatientUpdateRequest("李四", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_ALREADY_MERGED);
    }

    @Test
    @DisplayName("详情：档案解密后经脱敏引擎回填敏感三列；不存在时 PAT-1001 拒绝")
    void detailDecryptsThenMasksAndMissingRejected() {
        PatientVO vo = service.getDetail(5L);
        assertThat(vo.getPatientId()).isEqualTo(5L);
        assertThat(vo.getIdCardNo()).isEqualTo("110101199003077890");
        assertThat(vo.getMobile()).isEqualTo("110101199003077890");
        assertThat(vo.getAddress()).isEqualTo("110101199003077890");
        assertThat(vo.getStatus()).isEqualTo("NORMAL");
        TestablePatientServiceImpl missing = newUnstoredService();
        assertThatThrownBy(() -> missing.getDetail(5L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("检索按 keyword 形态分派：证件号/手机号/姓名/空词均可执行并回传脱敏分页")
    void searchDispatchesKeywordForms() {
        // 检索链式查询触达 selectPage：替身回填单页单行（断言只针对业务分页结果，不绑定 SQL 细节）
        when(patientMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Patient> page = inv.getArgument(0);
            page.setRecords(List.of(service.stored));
            page.setTotal(1);
            return page;
        });
        service.useChainMapper(patientMapper);
        // 18 位证件号（数字校验位）→ idCard 盲索引等值
        PageResult<PatientVO> byIdCard = service.search(new PatientSearchQuery("110101199003077890", 0, 20));
        // 15 位老号形态 → idCard 盲索引等值
        service.search(new PatientSearchQuery("110101900307789", 0, 20));
        // 18 位含 X 校验位形态 → idCard 盲索引等值
        service.search(new PatientSearchQuery("11010119900307789X", 0, 20));
        // 手机号形态 → mobile 盲索引等值
        service.search(new PatientSearchQuery("13800001234", 0, 20));
        // 姓名形态 → name 模糊
        PageResult<PatientVO> byName = service.search(new PatientSearchQuery("张", 0, 20));
        // 空白关键词与 null 关键词 → like 条件关闭的空数据页
        PageResult<PatientVO> blank = service.search(new PatientSearchQuery("  ", 0, 20));
        PageResult<PatientVO> absent = service.search(new PatientSearchQuery(null, 0, 20));
        assertThat(byIdCard.content()).hasSize(1);
        assertThat(byIdCard.total()).isEqualTo(1);
        assertThat(byIdCard.page()).isZero();
        assertThat(byName.content()).hasSize(1);
        assertThat(blank.content()).hasSize(1);
        assertThat(absent.content()).hasSize(1);
    }

    /** 无档案替身服务（stored=null：getById 恒空，覆盖 PAT-1001 守卫） */
    private TestablePatientServiceImpl newUnstoredService() {
        return new TestablePatientServiceImpl(null, crypto, privacyMaskService, cacheService, eventPublisher);
    }
}
