package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.GatewayQueryRequest;
import com.fuyun.iot.dto.SaveGatewayRequest;
import com.fuyun.iot.entity.IotGatewayEntity;
import com.fuyun.iot.enums.GatewayMode;
import com.fuyun.iot.enums.GatewayStatus;
import com.fuyun.iot.mapper.IotGatewayMapper;
import com.fuyun.iot.service.IGatewayService;
import com.fuyun.iot.vo.GatewayVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 边缘网关管理服务单测（P2 PR-2 Task 11 Step 1，TDD 先行）：CRUD 主链（登记唯一性 IOT-1024/
 * 更新与删除存在性 IOT-1023）与 standby 校验四分支（对端不存在/自引用/双节点环/三节点环 IOT-1025）、
 * 删除守卫（他网关引用本网关为热备对端拒删；EX-22/BE-A2-03 起守卫反查折入置删 CAS 语句原子生效，
 * 并发删除先行按幂等成功归因）、分页缺省值。JaCoCo 核心包
 * com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>mapper 以 Mockito 模拟（真实 SQL 归 fuyun-app 集成面验证）；lambda 条件列名解析依赖
 * TableInfo（容器外单测需手动初始化一次，BindingServiceImplTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class GatewayServiceImplTest {

    /** 网关标识夹具（主操作网关） */
    private static final String GATEWAY_ID = "fuyun-gw-001";

    /** 热备对端网关标识夹具 */
    private static final String STANDBY_ID = "fuyun-gw-002";

    /** 第三节点网关标识夹具（三节点环构造） */
    private static final String THIRD_ID = "fuyun-gw-003";

    /** 测试病区 ID */
    private static final long WARD_ID = 1001L;

    @Mock
    private IotGatewayMapper gatewayMapper;

    @Captor
    private ArgumentCaptor<IotGatewayEntity> entityCaptor;

    private GatewayServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotGatewayEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无 Spring 上下文直构（Bean 注册归 app 侧 IotConfig @Import）；ServiceImpl 基类字段手工注入
        // （baseMapper/entityClass，BindingServiceImplTest 同款形态——链式与 IService 能力的载体）
        service = new GatewayServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", gatewayMapper);
        ReflectionTestUtils.setField(service, "entityClass", IotGatewayEntity.class);
    }

    @Test
    @DisplayName("登记成功：自然键落行且字段全量投影，返回网关视图")
    void createPersistsGatewayWithFullProjection() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, null));

        GatewayVO vo = service.create(request(GATEWAY_ID, STANDBY_ID, null));

        verify(gatewayMapper).insert(entityCaptor.capture());
        IotGatewayEntity inserted = entityCaptor.getValue();
        assertThat(inserted.getGatewayId()).isEqualTo(GATEWAY_ID);
        assertThat(inserted.getGatewayName()).isEqualTo("一号网关");
        assertThat(inserted.getMode()).isEqualTo(GatewayMode.C);
        assertThat(inserted.getStandbyOf()).isEqualTo(STANDBY_ID);
        assertThat(inserted.getWardId()).isEqualTo(WARD_ID);
        assertThat(inserted.getStatus()).isEqualTo(GatewayStatus.ONLINE);
        assertThat(vo.gatewayId()).isEqualTo(GATEWAY_ID);
        assertThat(vo.standbyOf()).isEqualTo(STANDBY_ID);
    }

    @Test
    @DisplayName("登记拒绝：gateway_id 已存在 IOT-1024（409），不触写路径")
    void createRejectsDuplicateGatewayId() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, null));

        assertThatThrownBy(() -> service.create(request(GATEWAY_ID, null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_ALREADY_EXISTS);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(gatewayMapper, never()).insert(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("登记拒绝：PK 冲突翻译 IOT-1024（409）——软删行占物理 PK/并发竞态 insert 兜底，不裸 500")
    void createTranslatesPrimaryKeyViolationToBizException() {
        // 唯一性预检探测不到软删行（@TableLogic 过滤）：insert 撞物理 PK 抛 DIVE → 服务层转 IOT-1024
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);
        when(gatewayMapper.insert(any(IotGatewayEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> service.create(request(GATEWAY_ID, null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_ALREADY_EXISTS);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    @DisplayName("standby 校验：对端网关不存在 IOT-1025（409）")
    void createRejectsMissingStandbyTarget() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.create(request(GATEWAY_ID, STANDBY_ID, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(gatewayMapper, never()).insert(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：自引用 IOT-1025（409）——双网关热备禁指向自身")
    void createRejectsSelfReferencingStandby() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.create(request(GATEWAY_ID, GATEWAY_ID, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(gatewayMapper, never()).insert(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：双节点环 IOT-1025（409）——更新路径 A→B 且 B→A 游走回到自身拒保存")
    void updateRejectsTwoNodeStandbyCycle() {
        // 更新路径两网关均已存在（登记期对端不存在语义已前置拦截，环检测在存量链上游走触发）
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, STANDBY_ID));
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, GATEWAY_ID));

        assertThatThrownBy(() -> service.update(GATEWAY_ID, request(GATEWAY_ID, STANDBY_ID, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(gatewayMapper, never()).updateById(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：三节点环 IOT-1025（409）——更新路径 A→B→C→A 游走回到自身拒保存")
    void updateRejectsThreeNodeStandbyCycle() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, STANDBY_ID));
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, THIRD_ID));
        when(gatewayMapper.selectById(THIRD_ID)).thenReturn(gateway(THIRD_ID, GATEWAY_ID));

        assertThatThrownBy(() -> service.update(GATEWAY_ID, request(GATEWAY_ID, STANDBY_ID, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID));
        verify(gatewayMapper, never()).updateById(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：链尾非环放行——A→B→C（C 无对端）正常保存")
    void createAllowsAcyclicStandbyChain() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, THIRD_ID));
        when(gatewayMapper.selectById(THIRD_ID)).thenReturn(gateway(THIRD_ID, null));

        service.create(request(GATEWAY_ID, STANDBY_ID, null));

        verify(gatewayMapper).insert(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：既有脏数据自成环且不含保存目标——游走以 visited 终止，保存不误伤")
    void createAllowsWhenExistingCycleExcludesTarget() {
        // 存量网关 B↔C 已成环（脏数据）：保存 A（standby=B）——游走 B→C→B 撞 visited 终止，A 不在环上
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, THIRD_ID));
        when(gatewayMapper.selectById(THIRD_ID)).thenReturn(gateway(THIRD_ID, STANDBY_ID));

        service.create(request(GATEWAY_ID, STANDBY_ID, null));

        verify(gatewayMapper).insert(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("standby 校验：standbyOf 为空白等价未配置（放行，无双机热备场景）")
    void createTreatsBlankStandbyAsUnset() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);

        service.create(new SaveGatewayRequest(GATEWAY_ID, "一号网关", GatewayMode.B, "  ", WARD_ID, GatewayStatus.ONLINE));

        verify(gatewayMapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStandbyOf()).as("空白归一为 null 落列").isNull();
    }

    @Test
    @DisplayName("更新成功：以路径 ID 定位全量覆写（请求体同名 ID 仅回显），返回更新后视图")
    void updateOverwritesByPathGatewayId() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, null));
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(gateway(STANDBY_ID, null));

        GatewayVO vo = service.update(GATEWAY_ID, request(GATEWAY_ID, STANDBY_ID, GatewayStatus.MAINTENANCE));

        verify(gatewayMapper).updateById(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getStatus()).isEqualTo(GatewayStatus.MAINTENANCE);
        assertThat(entityCaptor.getValue().getStandbyOf()).isEqualTo(STANDBY_ID);
        assertThat(vo.status()).isEqualTo(GatewayStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("更新拒绝：网关不存在 IOT-1023（404）")
    void updateRejectsMissingGateway() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(GATEWAY_ID, request(GATEWAY_ID, null, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(gatewayMapper, never()).updateById(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("更新拒绝：standby 指向已删除对端 IOT-1025（409）——@TableLogic 未命中即不存在")
    void updateRejectsStandbyPointingToDeletedTarget() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, null));
        when(gatewayMapper.selectById(STANDBY_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(GATEWAY_ID, request(GATEWAY_ID, STANDBY_ID, null)))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID));
        verify(gatewayMapper, never()).updateById(any(IotGatewayEntity.class));
    }

    @Test
    @DisplayName("删除成功：守卫 CAS 软删放行（无他网关引用本网关为热备对端）")
    void deleteSoftDeletesWhenNoInboundStandbyReference() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, null));
        // 守卫反查与置删同一语句原子生效（EX-22）：CAS 命中即守卫通过且已软删
        when(gatewayMapper.casSoftDeleteIfNoInboundStandby(GATEWAY_ID)).thenReturn(1);

        service.delete(GATEWAY_ID);

        verify(gatewayMapper).casSoftDeleteIfNoInboundStandby(GATEWAY_ID);
        // 无守卫的 removeById 通道不再触达（守卫折入写语句，防读后写竞态面回退）
        verify(gatewayMapper, never()).deleteById(GATEWAY_ID);
    }

    @Test
    @DisplayName("删除守卫：他网关引用本网关为热备对端 IOT-1025（409），热备关系先行解除")
    void deleteRejectsWhenReferencedAsStandbyByOtherGateway() {
        // 行仍在册 + CAS 零命中 = 置删语句内守卫命中（拒删必经写语句判定路径，非预检读路径）
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(gateway(GATEWAY_ID, null));
        when(gatewayMapper.casSoftDeleteIfNoInboundStandby(GATEWAY_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(GATEWAY_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.GATEWAY_STANDBY_INVALID);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(gatewayMapper, never()).deleteById(GATEWAY_ID);
        // 守卫折入写语句（EX-22）：全程零 count 预检读——读后写通道不再存在
        verify(gatewayMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("删除边界：CAS 零命中且行已消失（并发删除先行）——幂等成功不误报 409")
    void deleteTreatsConcurrentDeleteAsIdempotentSuccess() {
        // requireGateway 时行在册，置删语句执行时行已被并发软删（分类归因读返回 null）
        when(gatewayMapper.selectById(GATEWAY_ID))
                .thenReturn(gateway(GATEWAY_ID, null))
                .thenReturn(null);
        when(gatewayMapper.casSoftDeleteIfNoInboundStandby(GATEWAY_ID)).thenReturn(0);

        assertThatCode(() -> service.delete(GATEWAY_ID)).doesNotThrowAnyException();
        verify(gatewayMapper, never()).deleteById(GATEWAY_ID);
    }

    @Test
    @DisplayName("删除拒绝：网关不存在 IOT-1023（404）")
    void deleteRejectsMissingGateway() {
        when(gatewayMapper.selectById(GATEWAY_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(GATEWAY_ID))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.GATEWAY_NOT_FOUND));
        verify(gatewayMapper, never()).deleteById(GATEWAY_ID);
    }

    @Test
    @DisplayName("分页：page/size 缺省补齐（0/20）且过滤条件透传，0 基回显")
    void pageAppliesDefaultsAndMapsToVo() {
        Page<IotGatewayEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(gateway(GATEWAY_ID, null)));
        page.setTotal(1);
        when(gatewayMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<GatewayVO> result = service.page(new GatewayQueryRequest(null, null, WARD_ID, null, null, null));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).gatewayId()).isEqualTo(GATEWAY_ID);
    }

    @Test
    @DisplayName("分页：显式 page/size 透传且全过滤条件命中（病区/模式/状态/标识精确过滤）")
    void pageAppliesExplicitPagingAndAllFilters() {
        Page<IotGatewayEntity> page = new Page<>(3, 50);
        page.setRecords(List.of(gateway(GATEWAY_ID, null)));
        page.setTotal(101);
        when(gatewayMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<GatewayVO> result =
                service.page(new GatewayQueryRequest(2, 50, WARD_ID, GatewayMode.C, GatewayStatus.ONLINE, GATEWAY_ID));

        assertThat(result.page()).as("0 基回显").isEqualTo(2);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.total()).isEqualTo(101);
    }

    @Test
    @DisplayName("配对纪律（A.4.3-20）：IGatewayService 两侧继承 IService/ServiceImpl，接口面承载链式能力")
    void serviceCarriesIServicePairingContract() {
        // CRUD 单表服务强制配对：接口缺 extends IService / 实现缺 extends ServiceImpl 即本用例红
        assertThat(IService.class.isAssignableFrom(IGatewayService.class))
                .as("接口侧配对：IGatewayService extends IService<IotGatewayEntity>")
                .isTrue();
        assertThat(service).as("实现侧配对：GatewayServiceImpl extends ServiceImpl").isInstanceOf(IService.class);
    }

    @Test
    @DisplayName("分页链式等价：无命中空结果边界——空清单 + total 0 + 0 基回显，行为与链式化前一致")
    void pageReturnsEmptyResultWithZeroTotalWhenNoMatch() {
        // 边界场景（主表查询链式化后的行为等价守护）：过滤无命中时不出错、不造数据
        Page<IotGatewayEntity> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(gatewayMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<GatewayVO> result = service.page(new GatewayQueryRequest(null, null, WARD_ID, null, null, null));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isZero();
        assertThat(result.content()).isEmpty();
    }

    /** 保存请求夹具（status 可覆写） */
    private static SaveGatewayRequest request(String gatewayId, String standbyOf, GatewayStatus status) {
        return new SaveGatewayRequest(
                gatewayId, "一号网关", GatewayMode.C, standbyOf, WARD_ID, status == null ? GatewayStatus.ONLINE : status);
    }

    /** 网关行夹具（standbyOf 可指定，用于构造 standby 链） */
    private static IotGatewayEntity gateway(String gatewayId, String standbyOf) {
        IotGatewayEntity entity = new IotGatewayEntity();
        entity.setGatewayId(gatewayId);
        entity.setGatewayName(gatewayId + "-名");
        entity.setMode(GatewayMode.C);
        entity.setStandbyOf(standbyOf);
        entity.setWardId(WARD_ID);
        entity.setStatus(GatewayStatus.ONLINE);
        return entity;
    }
}
