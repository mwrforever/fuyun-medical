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
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.DrugSaveRequest;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.entity.Drug;
import com.fuyun.pharmacy.mapper.DrugMapper;
import com.fuyun.pharmacy.service.IDrugService;
import com.fuyun.pharmacy.vo.DrugVO;
import java.util.List;
import java.util.Map;
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
        // 链式查询载体：Mockito 桩 mapper 非 MyBatis 真代理，entityClass 须直设（billing/inpatient 同款）
        ReflectionTestUtils.setField(impl, "entityClass", Drug.class);
        return impl;
    }

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：无 Spring 上下文时手工注册实体表信息（patient/billing 实证形态）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Drug.class);
    }

    /**
     * 取链式 wrapper 底层谓词载体并触发渲染：MP 条件参数在 getSqlSegment 惰性求值时才写入
     * 参数表，须先渲染再断言绑定值（billing ChargeItemServiceImplTest.rendered 同款）。
     *
     * @param chain buildSearchWrapper 产出的链式 wrapper，非空
     * @return 已触发渲染的底层 LambdaQueryWrapper（承载 SQL 片段与参数绑定表），非空
     */
    private static LambdaQueryWrapper<Drug> rendered(LambdaQueryChainWrapper<Drug> chain) {
        LambdaQueryWrapper<Drug> wrapper = (LambdaQueryWrapper<Drug>) chain.getWrapper();
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * SQL 片段按占位符名回填绑定值（'v' 字面量形态）：契约断言面向「列+操作符+值」完整形态，
     * 不锚定 MP 内部 MPGENVALx 编号（编号是实现细节）；参数表为 HashMap 无序，须按名配对
     * 确定性回填（禁按迭代序回填——无序会错配值）。MP 3.5.17 likeRight 将 '%' 载入绑定值
     * （'阿莫%'=前缀匹配形态，与 likeLeft/全包含的值形态可区分）。
     *
     * @param wrapper 已渲染的底层 wrapper，非空
     * @return 回填后的 SQL 片段文本，非空
     */
    private static String resolvedSql(LambdaQueryWrapper<Drug> wrapper) {
        String sql = wrapper.getSqlSegment();
        for (Map.Entry<String, Object> entry : wrapper.getParamNameValuePairs().entrySet()) {
            sql = sql.replace("#{ew.paramNameValuePairs." + entry.getKey() + "}", "'" + entry.getValue() + "'");
        }
        return sql;
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
    @DisplayName("检索谓词 SQL 契约：启用面+四列 OR 前缀+基药/分级等值+对照 IS NOT NULL+id 升序（EX-12 收口）")
    void searchWrapperSqlContractPinsEnabledKeywordFiltersAndOrdering() {
        DrugServiceImpl impl = newService();

        LambdaQueryWrapper<Drug> wrapper = rendered(impl.buildSearchWrapper("阿莫", true, "UNRESTRICTED", true));
        String sql = resolvedSql(wrapper);

        // 默认启用面谓词：status 等值 ENABLED（停用药品不进选药场景）
        assertThat(sql).contains("status = 'ENABLED'");
        // keyword 四列 OR 前缀匹配整块：通用名/商品名/拼音/医保码各一次 LIKE，'%' 载入绑定值
        //   （'阿莫%'=右前缀形态，与 likeLeft/全包含的值形态可区分，四列缺一即红）
        assertThat(sql)
                .contains("(generic_name LIKE '阿莫%' OR trade_name LIKE '阿莫%'"
                        + " OR pinyin_code LIKE '阿莫%' OR nhsa_code LIKE '阿莫%')");
        // 过滤器等值谓词：基药/分级（列+操作符+绑定值完整形态）
        assertThat(sql).contains("essential_flag = 'true'");
        assertThat(sql).contains("antibio_class = 'UNRESTRICTED'");
        // 对照过滤谓词：insuranceMapped=true 附加 nhsa_code 非空（列名锚定，非任意 IS NOT NULL）
        assertThat(sql).contains("nhsa_code IS NOT NULL");
        // 唯一顺序约束（A.4.3-17）：id 升序收尾，其后无其他排序键
        assertThat(sql).endsWith("ORDER BY id ASC");
        // 绑定值全量精确（序无关多集合：参数表为 HashMap 无序）：ENABLED+keyword×4（四列 OR 各绑
        //   一次，'%' 随值承载）+基药+分级，无遗漏无多余
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("ENABLED", "阿莫%", "阿莫%", "阿莫%", "阿莫%", true, "UNRESTRICTED");
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

        // 数据库写操作断言：仅可覆盖面落行，对照列与状态列不被覆盖（applyRequest 边界）。
        // EX-24 断言现代化（D-21 回归红线出口，逐次批准单点单次）：原「nhsaCode/status 等值断言」
        // 冻结整行回写实现细节（以携读点快照同值落库作为「未触碰」的可观测面）；指定列补丁回写下
        // 「未触碰」由列不进 SET 承载，isNull 为更严格契约（NOT_NULL 策略 null 列不落 SET）——
        // 断言业务意图（对照三列与 status 不被触碰）不变且强化
        ArgumentCaptor<Drug> captor = ArgumentCaptor.forClass(Drug.class);
        verify(drugMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGenericName()).isEqualTo("阿莫西林胶囊");
        assertThat(captor.getValue().getNhsaCode()).isNull();
        assertThat(captor.getValue().getStatus()).isNull();
        assertThat(vo.drugCode()).isEqualTo("D-IT-001");
    }

    @Test
    @DisplayName("变更并发防覆写（EX-24）：回写补丁仅携 id+请求面列，对照三列/status/审计列读点快照不进 SET")
    void updateWritesPatchEntityWithoutCarryingMappingOrStatusSnapshot() {
        DrugServiceImpl impl = newService();
        // 读点快照携并发写面已落值：对照三列（mapInsurance 通道）、status（停启用通道）、审计列
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        row.setGenericName("旧通用名（读点快照）");
        row.setNhsaCode("XJ01CAC0130020589");
        row.setNhsaCatalogVersion("2023");
        row.setNhsaPayType("Y");
        row.setStatus("ENABLED");
        row.setCreatedBy("admin-01");
        when(drugMapper.selectById(7L)).thenReturn(row);

        impl.update(7L, request("D-IT-001"));

        ArgumentCaptor<Drug> captor = ArgumentCaptor.forClass(Drug.class);
        verify(drugMapper).updateById(captor.capture());
        Drug saved = captor.getValue();
        // 目标面全量精确（D-21 严格度不低于原断言）：请求面列逐项落补丁 + 主键定位
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getDrugCode()).isEqualTo("D-IT-001");
        assertThat(saved.getGenericName()).isEqualTo("阿莫西林胶囊");
        assertThat(saved.getSpecification()).isEqualTo("0.25g×24粒");
        assertThat(saved.getRouteCodes()).isEqualTo("ORAL,IV");
        assertThat(saved.getSplitRatio()).isEqualByComparingTo("1");
        assertThat(saved.getManufacturer()).isEqualTo("华东医药");
        assertThat(saved.getUnit()).isEqualTo("盒");
        assertThat(saved.getEssentialFlag()).isFalse();
        assertThat(saved.getAntibioClass()).isEqualTo("UNRESTRICTED");
        assertThat(saved.getHazardLevel()).isEqualTo("NONE");
        assertThat(saved.getSkinTestFlag()).isFalse();
        assertThat(saved.getNarcoticClass()).isEqualTo("NORMAL");
        assertThat(saved.getItemCode()).isEqualTo("C0131230900157");
        assertThat(saved.getTraceCodeType()).isNull();
        assertThat(saved.getIndication()).isEqualTo("用于敏感菌所致感染");
        assertThat(saved.getMaxDose()).isEqualTo("成人一日不超过4g");
        assertThat(saved.getContraindication()).isEqualTo("青霉素过敏者禁用");
        assertThat(saved.getStorageCondition()).isEqualTo("密封，置阴凉处保存");
        // 非目标列零携带：NOT_NULL 更新策略下 null 不进 SET 子句——读改写窗口内 mapInsurance
        //   并发提交的对照三列、停启用通道的 status 与审计列不被读点快照覆写吞掉
        assertThat(saved.getNhsaCode()).isNull();
        assertThat(saved.getNhsaCatalogVersion()).isNull();
        assertThat(saved.getNhsaPayType()).isNull();
        assertThat(saved.getStatus()).isNull();
        assertThat(saved.getCreatedBy()).isNull();
    }

    @Test
    @DisplayName("对照维护并发防覆写（EX-24）：回写补丁仅携 id+对照三列，档案面/status 读点快照不进 SET")
    void mapInsuranceWritesPatchEntityWithoutCarryingProfileSnapshot() {
        DrugServiceImpl impl = newService();
        // 读点快照携档案面已落值：通用名/规格（update 通道）与 status
        Drug row = new Drug();
        row.setId(7L);
        row.setDrugCode("D-IT-001");
        row.setGenericName("阿莫西林胶囊");
        row.setSpecification("0.25g×24粒");
        row.setStatus("ENABLED");
        when(drugMapper.selectById(7L)).thenReturn(row);

        impl.mapInsurance(7L, new InsuranceMappingRequest("XJ01CAC0130020105139", "2024", "YI"));

        ArgumentCaptor<Drug> captor = ArgumentCaptor.forClass(Drug.class);
        verify(drugMapper).updateById(captor.capture());
        Drug saved = captor.getValue();
        // 目标面全量精确：对照三列 + 主键定位
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getNhsaCode()).isEqualTo("XJ01CAC0130020105139");
        assertThat(saved.getNhsaCatalogVersion()).isEqualTo("2024");
        assertThat(saved.getNhsaPayType()).isEqualTo("YI");
        // 非目标列零携带：读改写窗口内 update() 并发提交的档案面变更与 status 不被快照覆写吞掉
        assertThat(saved.getDrugCode()).isNull();
        assertThat(saved.getGenericName()).isNull();
        assertThat(saved.getSpecification()).isNull();
        assertThat(saved.getStatus()).isNull();
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
    @DisplayName("检索边界 SQL 契约：keyword/过滤全空退化为仅启用面谓词（无 LIKE、无 IS NOT NULL、过滤器列零进入）")
    void searchWrapperSqlContractDegradesToEnabledOnlyWhenFiltersBlank() {
        DrugServiceImpl impl = newService();

        LambdaQueryWrapper<Drug> wrapper = rendered(impl.buildSearchWrapper(" ", null, " ", null));
        String sql = resolvedSql(wrapper);

        // 仅启用面谓词 + 唯一顺序约束保留
        assertThat(sql).contains("status = 'ENABLED'");
        assertThat(sql).endsWith("ORDER BY id ASC");
        // 空白关键词/空过滤零谓词零绑定：无前缀匹配、无非空谓词、过滤器列不进 WHERE、参数表仅 ENABLED
        assertThat(sql)
                .doesNotContain("LIKE")
                .doesNotContain("IS NOT NULL")
                .doesNotContain("essential_flag")
                .doesNotContain("antibio_class");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactly("ENABLED");
    }

    @Test
    @DisplayName("配对纪律（A.4.3-20）：IDrugService 两侧继承 IService/ServiceImpl——契约面扩展不触碰字典语义")
    void serviceCarriesIServicePairingContract() {
        // CRUD 单表服务强制配对：接口缺 extends IService / 实现缺 extends ServiceImpl 即本用例红；
        // 字典为单表 CRUD 域：配对仅扩展默认方法集，建档/变更/对照权威仍走本接口自有方法入口
        assertThat(IService.class.isAssignableFrom(IDrugService.class))
                .as("接口侧配对：IDrugService extends IService<Drug>")
                .isTrue();
        assertThat(newService()).as("实现侧配对：DrugServiceImpl extends ServiceImpl").isInstanceOf(IService.class);
    }
}
