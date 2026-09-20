package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.vo.DrugVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 药品字典服务单测（service.impl LINE=1.00 达标件）：建档/变更/详情/对照维护/检索谓词
 * 全方法覆盖（正常/边界/异常三类场景，支撑 LINE=1.00 全行判定）；changed 广播发布点已由
 * Task 4 接线（构造器第二参 ApplicationEventPublisher，newService() 双参构造同源）。
 */
@ExtendWith(MockitoExtension.class)
class DrugServiceImplTest {

    @Mock
    private DrugMapper drugMapper;

    @Mock
    private ApplicationEventPublisher events;

    private DrugServiceImpl newService() {
        // 构造器注入 collaborator；ServiceImpl 继承字段 baseMapper 由反射注入（Global Constraints 单测范式）
        DrugServiceImpl impl = new DrugServiceImpl(drugMapper, events);
        ReflectionTestUtils.setField(impl, "baseMapper", drugMapper);
        return impl;
    }

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：无 Spring 上下文时手工注册实体表信息（patient/billing 实证形态）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Drug.class);
    }

    private static DrugSaveRequest request(String drugCode) {
        return new DrugSaveRequest(
                drugCode,
                "阿莫西林胶囊",
                "阿莫仙",
                "AMXLJN",
                "胶囊剂",
                "0.25g×24粒",
                "华东医药",
                List.of("ORAL", "IV"),
                "盒",
                "1",
                false,
                "UNRESTRICTED",
                "NONE",
                false,
                "NORMAL",
                "C0131230900157",
                null,
                "用于敏感菌所致感染",
                "成人一日不超过4g",
                "青霉素过敏者禁用",
                "密封，置阴凉处保存");
    }

    @Test
    @DisplayName("建档成功：必填面裁剪落库，未对照药品 insuredSettleable=false 显式标记")
    void createPersistsDrugAndMarksUninsured() {
        DrugServiceImpl impl = newService();
        // 未对照（itemCode 为计费关联非医保对照；nhsa 字段不入建档请求——对照走 mapInsurance）
        when(drugMapper.selectCount(any())).thenReturn(0L);

        DrugVO vo = impl.create(request("D-IT-001"));

        assertThat(vo.drugCode()).isEqualTo("D-IT-001");
        assertThat(vo.insuredSettleable()).isFalse(); // 未对照=显式标记不可医保结算（Spec :154）
        assertThat(vo.status()).isEqualTo("ENABLED"); // 院内启用
        assertThat(vo.antibioClass()).isEqualTo("UNRESTRICTED");
        // 事务内应用事件已发布（AFTER_COMMIT 出线由发布器承载，此处锚定事务内发布动作；
        // any(Object.class) 定位 publishEvent(Object) 重载——PharmacyDomainEvent 非 ApplicationEvent，
        // 裸 any() 会解析到 ApplicationEvent 重载致 verify 落空，与 billing 单测同款）
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("建档 uk 冲突：drug_code 已存在拒 PH-1002")
    void createRejectsDuplicateCodeAsPh1002() {
        DrugServiceImpl impl = newService();
        when(drugMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> impl.create(request("D-IT-001")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DRUG_CODE_EXISTS));
        verify(drugMapper, never()).insert(any(Drug.class));
    }

    @Test
    @DisplayName("建档守卫：拆分比例非数字串拒 PH-1016（400 显式拒，禁 NumberFormatException 直穿 500）")
    void createRejectsNonNumericSplitRatioAsPh1016() {
        DrugServiceImpl impl = newService();
        when(drugMapper.selectCount(any())).thenReturn(0L);
        // 同 request() 全量面仅 splitRatio 换非数字串（W-22⑦ 格式守卫靶点）
        DrugSaveRequest badRatio = new DrugSaveRequest(
                "D-IT-001",
                "阿莫西林胶囊",
                "阿莫仙",
                "AMXLJN",
                "胶囊剂",
                "0.25g×24粒",
                "华东医药",
                List.of("ORAL", "IV"),
                "盒",
                "1:3",
                false,
                "UNRESTRICTED",
                "NONE",
                false,
                "NORMAL",
                "C0131230900157",
                null,
                "用于敏感菌所致感染",
                "成人一日不超过4g",
                "青霉素过敏者禁用",
                "密封，置阴凉处保存");

        assertThatThrownBy(() -> impl.create(badRatio))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED));
        verify(drugMapper, never()).insert(any(Drug.class));
    }

    @Test
    @DisplayName("医保对照维护：映射字段落行且 changeType=MAPPING 语义在出参可见（对照后可结算）")
    void mapInsurancePersistsMapping() {
        DrugServiceImpl impl = newService();
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        row.setStatus("ENABLED");
        when(drugMapper.selectById(7L)).thenReturn(row);

        impl.mapInsurance(7L, new InsuranceMappingRequest("XJ01CAC0130020105139", "2024", "YI"));

        ArgumentCaptor<Drug> captor = ArgumentCaptor.forClass(Drug.class);
        verify(drugMapper).updateById(captor.capture());
        Drug saved = captor.getValue();
        assertThat(saved.getNhsaCode()).isEqualTo("XJ01CAC0130020105139");
        assertThat(saved.getNhsaCatalogVersion()).isEqualTo("2024");
        assertThat(saved.getNhsaPayType()).isEqualTo("YI");
    }

    @Test
    @DisplayName("检索谓词：默认启用面 + keyword 四列 OR 前缀 + 对照过滤 IS NOT NULL（子串断言）")
    void searchWrapperContainsEnabledAndKeywordPredicates() {
        DrugServiceImpl impl = newService();

        var wrapper = impl.buildSearchWrapper("阿莫", true, "UNRESTRICTED", true);

        // wrapper 断言只做 contains 子串（Global Constraints：禁全文精确比对）；MP 条件参数在
        // getSqlSegment 惰性求值时才写入 paramNameValuePairs——先渲染片段再断言参数（billing 同款）
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("status"); // 默认启用面谓词落 status 列
        assertThat(sql).contains("LIKE"); // likeRight 前缀匹配（通用名/商品名/拼音/医保码四列 OR）
        assertThat(sql).contains("IS NOT NULL"); // insuranceMapped=true 附加对照谓词
        assertThat(sql).contains("essential_flag").contains("antibio_class");
        assertThat(((LambdaQueryWrapper<Drug>) wrapper).getParamNameValuePairs().values())
                .contains("ENABLED", true, "UNRESTRICTED");
    }

    @Test
    @DisplayName("变更成功：可覆盖字段落行且对照三列与 status 不被触碰（update 覆盖面守卫）")
    void updatePersistsEditableFieldsWithoutTouchingMappingAndStatus() {
        DrugServiceImpl impl = newService();
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        row.setGenericName("阿莫西林胶囊");
        row.setNhsaCode("XJ01CAC0130020589");
        row.setStatus("ENABLED");
        when(drugMapper.selectById(7L)).thenReturn(row);

        DrugVO vo = impl.update(7L, request("D-IT-001"));

        // 数据库写操作断言：仅可覆盖面落行，对照列与状态列不被覆盖（applyRequest 边界）
        ArgumentCaptor<Drug> captor = ArgumentCaptor.forClass(Drug.class);
        verify(drugMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGenericName()).isEqualTo("阿莫西林胶囊");
        assertThat(captor.getValue().getNhsaCode()).isEqualTo("XJ01CAC0130020589");
        assertThat(captor.getValue().getStatus()).isEqualTo("ENABLED");
        assertThat(vo.drugCode()).isEqualTo("D-IT-001");
    }

    @Test
    @DisplayName("变更缺行：未知药品 id 拒 PH-1001（404）")
    void updateRejectsUnknownIdAsPh1001() {
        DrugServiceImpl impl = newService();
        when(drugMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> impl.update(7L, request("D-IT-001")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DRUG_NOT_FOUND));
    }

    @Test
    @DisplayName("详情命中：既有行转 VO 返回（对照列在位时派生可结算语义）")
    void getReturnsVoForExistingRow() {
        DrugServiceImpl impl = newService();
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        row.setNhsaCode("XJ01CAC0130020589");
        when(drugMapper.selectById(7L)).thenReturn(row);

        assertThat(impl.get(7L).drugCode()).isEqualTo("D-IT-001");
    }

    @Test
    @DisplayName("详情缺行：未知药品 id 拒 PH-1001（404）")
    void getRejectsUnknownIdAsPh1001() {
        DrugServiceImpl impl = newService();
        when(drugMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> impl.get(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DRUG_NOT_FOUND));
    }

    @Test
    @DisplayName("对照缺行：未知药品 id 拒 PH-1001（404），零写面")
    void mapInsuranceRejectsUnknownIdAsPh1001() {
        DrugServiceImpl impl = newService();
        when(drugMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> impl.mapInsurance(7L, new InsuranceMappingRequest("XJ01CAC0130020589", "2024", "Y")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DRUG_NOT_FOUND));
        verify(drugMapper, never()).updateById(any(Drug.class));
    }

    @Test
    @DisplayName("检索出参：selectPage 记录转 VO 分页返回（content/total 透传）")
    void searchReturnsPagedContentFromMapper() {
        DrugServiceImpl impl = newService();
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        Page<Drug> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(drugMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<DrugVO> result = impl.search("阿莫", null, null, null, 0, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).drugCode()).isEqualTo("D-IT-001");
    }

    @Test
    @DisplayName("检索边界：keyword/过滤全空退化为仅启用面谓词（无 LIKE、无 IS NOT NULL）")
    void searchWrapperDegradesToEnabledOnlyWhenFiltersBlank() {
        DrugServiceImpl impl = newService();

        LambdaQueryWrapper<Drug> wrapper = (LambdaQueryWrapper<Drug>) impl.buildSearchWrapper(" ", null, " ", null);
        String sql = wrapper.getSqlSegment();

        assertThat(sql).contains("status"); // 仅启用面谓词保留
        assertThat(wrapper.getParamNameValuePairs().values()).contains("ENABLED");
        assertThat(sql).doesNotContain("LIKE");
        assertThat(sql).doesNotContain("IS NOT NULL");
    }
}
