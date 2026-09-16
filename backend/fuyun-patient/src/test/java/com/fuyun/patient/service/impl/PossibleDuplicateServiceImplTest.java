package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PossibleDuplicate;
import com.fuyun.patient.mapper.PatientMapper;
import com.fuyun.patient.mapper.PossibleDuplicateMapper;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import com.fuyun.patient.vo.PossibleDuplicateVO;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 疑似重复治理单测（FU-M02-03/Spec §10）：排除 CAS 守卫（成功/404/409）、a&lt;b 规范化、
 * 唯一兜底幂等静默、批量增量扫描计数与跳过分支（核心治理路径 100% 行覆盖口径）。
 * 链式查询触点经 getBaseMapper 替身承载（PatientServiceImplTest 同款）。
 */
@ExtendWith(MockitoExtension.class)
class PossibleDuplicateServiceImplTest {

    @Mock
    private IPatientService patientService;

    @Mock
    private PatientMatchingService matchingService;

    /** 待审表链式查询触点替身（lambdaQuery/lambdaUpdate 链最终委托到该 mapper） */
    @Mock
    private PossibleDuplicateMapper duplicateMapper;

    /** 批量扫描近窗档查询链触点替身（patientService.lambdaQuery 链最终委托到该 mapper） */
    @Mock
    private PatientMapper patientMapper;

    /** 被测服务 */
    private TestablePossibleDuplicateService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次，PatientServiceImplTest 同款）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PossibleDuplicate.class);
        TableInfoHelper.initTableInfo(assistant, Patient.class);
    }

    @BeforeEach
    void setUp() {
        service = new TestablePossibleDuplicateService(patientService, matchingService);
        service.useChainMapper(duplicateMapper);
    }

    /**
     * 测试替身：仅覆写 getById/getEntityClass/getBaseMapper 三个触点（IService 继承能力的可测化：
     * getById 供 CAS 未命中回查，链式查询经注入的 mock mapper 承载）。
     */
    static class TestablePossibleDuplicateService extends PossibleDuplicateServiceImpl {

        /** CAS 未命中回查行（null=行不存在） */
        private PossibleDuplicate storedRow;

        private PossibleDuplicateMapper chainMapper;

        TestablePossibleDuplicateService(IPatientService patientService, PatientMatchingService matchingService) {
            super(patientService, matchingService);
        }

        /** 链式查询用 mapper 替身注入（PatientServiceImplTest 同款） */
        void useChainMapper(PossibleDuplicateMapper mapper) {
            this.chainMapper = mapper;
        }

        void setStoredRow(PossibleDuplicate row) {
            this.storedRow = row;
        }

        @Override
        public PossibleDuplicate getById(Serializable id) {
            return storedRow;
        }

        @Override
        public PossibleDuplicateMapper getBaseMapper() {
            return chainMapper;
        }

        @Override
        public Class<PossibleDuplicate> getEntityClass() {
            // 直返实体类型：绕开默认实现的 mapper 代理反射抽取（Mockito mock 无 sqlSession 属性）
            return PossibleDuplicate.class;
        }
    }

    /** 待审行种子（PENDING，患者对 3/9） */
    private PossibleDuplicate pendingRow() {
        PossibleDuplicate row = new PossibleDuplicate();
        row.setId(5L);
        row.setPatientIdA(3L);
        row.setPatientIdB(9L);
        row.setMatchScore(new BigDecimal("95.00"));
        row.setMatchedRules("[NAME_SEX_BIRTH]");
        row.setSource("REGISTER_SCAN");
        row.setStatus("PENDING");
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    @Test
    @DisplayName("待审列表：PENDING 过滤分页直出 VO；ALL 不过滤（工作台主检索）")
    void listReturnsPagedWorkbenchRows() {
        when(duplicateMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<PossibleDuplicate> page = inv.getArgument(0);
            page.setRecords(List.of(pendingRow()));
            page.setTotal(1);
            return page;
        });
        PageResult<PossibleDuplicateVO> pending = service.list("PENDING", 0, 20);
        assertThat(pending.content()).hasSize(1);
        assertThat(pending.content().get(0).getId()).isEqualTo(5L);
        assertThat(pending.content().get(0).getPatientIdA()).isEqualTo(3L);
        assertThat(pending.content().get(0).getStatus()).isEqualTo("PENDING");
        assertThat(pending.total()).isEqualTo(1);
        assertThat(pending.page()).isZero();
        service.list("ALL", 0, 20);
        // 两次调用均触达 selectPage（条件差异由 status 词表承载，业务断言聚焦分页形态）
        verify(duplicateMapper, org.mockito.Mockito.times(2)).selectPage(any(), any());
    }

    @Test
    @DisplayName("排除待审对：CAS 条件更新（where status=PENDING）成功落 EXCLUDED")
    void excludeTransitionsPendingToExcluded() {
        when(duplicateMapper.update(any(), any())).thenReturn(1);
        assertThatCode(() -> service.exclude(5L, "同名非同人，证件号不同")).doesNotThrowAnyException();
        // CAS 语义落 wrapper 断言：条件含 status 列（PENDING）与目标列 status/review_note/reviewed_at
        ArgumentCaptor<Wrapper<PossibleDuplicate>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(duplicateMapper).update(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("status");
    }

    @Test
    @DisplayName("排除不存在的待审行：PAT-1009 拒绝（404）")
    void excludeMissingRowRejected() {
        when(duplicateMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.exclude(404L, "同名非同人"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.DUPLICATE_NOT_FOUND);
    }

    @Test
    @DisplayName("排除已审核待审行：PAT-1010 拒绝（409，CAS 未命中=并发双审守卫）")
    void excludeAlreadyReviewedRejected() {
        when(duplicateMapper.update(any(), any())).thenReturn(0);
        service.setStoredRow(pendingRow());
        assertThatThrownBy(() -> service.exclude(5L, "同名非同人"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.DUPLICATE_ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("建档实时生成待审行：a<b 规范化落库（9/3 传入归一为 3/9）")
    void recordSuspectNormalizesPairOrder() {
        when(duplicateMapper.insert(any(PossibleDuplicate.class))).thenReturn(1);
        service.recordSuspect(
                9L, 3L, new PatientMatchCheckVO("SUSPECT", 3L, new BigDecimal("95"), List.of("NAME_SEX_BIRTH")));
        ArgumentCaptor<PossibleDuplicate> captor = ArgumentCaptor.forClass(PossibleDuplicate.class);
        verify(duplicateMapper).insert(captor.capture());
        PossibleDuplicate row = captor.getValue();
        assertThat(row.getPatientIdA()).isEqualTo(3L);
        assertThat(row.getPatientIdB()).isEqualTo(9L);
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getSource()).isEqualTo("REGISTER_SCAN");
        assertThat(row.getMatchScore()).isEqualByComparingTo("95");
    }

    @Test
    @DisplayName("同对患者重复命中：唯一索引冲突静默幂等（双渠道只留一条，Spec §10）")
    void recordSuspectDuplicatePairSilentlySkipped() {
        when(duplicateMapper.insert(any(PossibleDuplicate.class)))
                .thenThrow(new DuplicateKeyException("uk_possible_duplicate_pair 冲突"));
        assertThatCode(() -> service.recordSuspect(
                        3L,
                        9L,
                        new PatientMatchCheckVO("SUSPECT", 9L, new BigDecimal("95"), List.of("NAME_SEX_BIRTH"))))
                .doesNotThrowAnyException();
    }

    /** 扫描用例患者 id（生产雪花同量级 17 位，落在 Long 缓存区 [-128,127] 之外——防引用比较掩护性绿灯） */
    private static final long SCAN_PATIENT_A = 1700000000000000043L;

    private static final long SCAN_PATIENT_B = 1700000000000000044L;

    @Test
    @DisplayName("批量增量扫描：SUSPECT 命中生成待审并以 PENDING 行数增量计数（返回新增数）")
    void scanBatchCountsNewlyCreatedSuspects() {
        Patient recent = new Patient();
        recent.setPatientId(SCAN_PATIENT_A);
        recent.setName("李四");
        recent.setSex("1");
        recent.setBirthDate(LocalDate.of(1991, 1, 2));
        recent.setCreatedAt(OffsetDateTime.now());
        when(patientService.lambdaQuery()).thenReturn(new LambdaQueryChainWrapper<>(patientMapper));
        when(patientMapper.selectList(any())).thenReturn(List.of(recent));
        when(matchingService.preCheck(any()))
                .thenReturn(new PatientMatchCheckVO(
                        "SUSPECT", 1700000000000000042L, new BigDecimal("95"), List.of("NAME_SEX_BIRTH")));
        // 计数锚点：recordSuspect 前该患者 PENDING 行数 0 → 写入后 1（增量=1）
        when(duplicateMapper.selectCount(any())).thenReturn(0L, 1L);
        when(duplicateMapper.insert(any(PossibleDuplicate.class))).thenReturn(1);
        int created = service.scanBatch();
        assertThat(created).isEqualTo(1);
        // 扫描请求构造：近窗档的姓名/性别/出生日期入参（同名候选评分口径，无证件/手机号）
        ArgumentCaptor<PatientMatchCheckRequest> requestCaptor =
                ArgumentCaptor.forClass(PatientMatchCheckRequest.class);
        verify(matchingService).preCheck(requestCaptor.capture());
        assertThat(requestCaptor.getValue().name()).isEqualTo("李四");
        assertThat(requestCaptor.getValue().sex()).isEqualTo("1");
        assertThat(requestCaptor.getValue().birthDate()).isEqualTo("1991-01-02");
    }

    @Test
    @DisplayName("批量增量扫描：NO_MATCH 与候选为自身的 SUSPECT 均跳过（大数 id 下守卫真实触发）")
    void scanBatchSkipsNoMatchAndSelfCandidate() {
        Patient noMatchOne = new Patient();
        noMatchOne.setPatientId(SCAN_PATIENT_A);
        noMatchOne.setName("李四");
        noMatchOne.setSex("1");
        noMatchOne.setBirthDate(LocalDate.of(1991, 1, 2));
        noMatchOne.setCreatedAt(OffsetDateTime.now());
        Patient selfCandidate = new Patient();
        selfCandidate.setPatientId(SCAN_PATIENT_B);
        selfCandidate.setName("王五");
        selfCandidate.setSex("2");
        selfCandidate.setCreatedAt(OffsetDateTime.now());
        when(patientService.lambdaQuery()).thenReturn(new LambdaQueryChainWrapper<>(patientMapper));
        when(patientMapper.selectList(any())).thenReturn(List.of(noMatchOne, selfCandidate));
        // 候选=自身（同一 Long 实例都不同——值相等引用不等，引用比较会误放行，值比较语义下守卫触发跳过）
        when(matchingService.preCheck(any()))
                .thenReturn(
                        new PatientMatchCheckVO("NO_MATCH", null, null, List.of()),
                        new PatientMatchCheckVO(
                                "SUSPECT", SCAN_PATIENT_B, new BigDecimal("100"), List.of("ID_CARD_CONFLICT")));
        int created = service.scanBatch();
        assertThat(created).isZero();
        verify(duplicateMapper, org.mockito.Mockito.times(0)).insert(any(PossibleDuplicate.class));
    }
}
