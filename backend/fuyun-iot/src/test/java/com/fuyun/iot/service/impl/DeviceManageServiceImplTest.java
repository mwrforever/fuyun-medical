package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.DeviceQueryRequest;
import com.fuyun.iot.dto.DeviceRegisterRequest;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.registry.DeviceCredential;
import com.fuyun.iot.registry.DeviceShadow;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.RegistryDeviceSpec;
import com.fuyun.iot.registry.RegistryException;
import com.fuyun.iot.vo.DeviceCredentialResetVO;
import com.fuyun.iot.vo.DeviceShadowVO;
import com.fuyun.iot.vo.DeviceVO;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备管理服务单测（P2 PR-2 Task 5 Step 1）：注册流水线（Registry 签发 + 本地落行 INACTIVE +
 * secret 一次性透出）、重复注册 IOT-1008 前置拒绝、凭证重置热更新（credential_ref 轮换 + 新
 * secret 一次性透出 + 实体无 secret 字段红线断言）、DISABLED 置位 CAS（IOT-1007）、影子直通
 * 与分页/详情查询。JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>Registry/mapper 以 Mockito 模拟（真实 SQL 归 fuyun-app 集成面验证）；lambda 条件列名解析
 * 依赖 TableInfo（容器外单测需手动初始化一次）。
 */
@ExtendWith(MockitoExtension.class)
class DeviceManageServiceImplTest {

    /** 设备标识夹具（IoTDA 自然键） */
    private static final String DEVICE_ID = "dev-001";

    /** 产品标识夹具（注册中心产品） */
    private static final String PRODUCT_ID = "SIM-prod-001";

    /** 一机一密明文夹具（仅测试内存面，断言其禁入实体/更新投影） */
    private static final String SECRET = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    /** 换发后凭证引用夹具 */
    private static final String NEW_CREDENTIAL_REF = "sim-cred-rotated";

    @Mock
    private IotDeviceRegistry registry;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Captor
    private ArgumentCaptor<IotDeviceEntity> deviceCaptor;

    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<IotDeviceEntity>> updateCaptor;

    private DeviceManageServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        // 无 Spring 上下文直构（Bean 注册归 app 侧 IotConfig @Import）；ServiceImpl 基类字段手工注入
        service = new DeviceManageServiceImpl(registry);
        ReflectionTestUtils.setField(service, "baseMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "entityClass", IotDeviceEntity.class);
    }

    @Test
    @DisplayName("注册流水线：Registry 签发一次 + 本地落行 credential_ref/INACTIVE + 响应一次性透出 secret")
    void registerCallsRegistryThenInsertsInactiveRowAndExposesSecretOnce() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        when(registry.registerDevice(any(RegistryDeviceSpec.class)))
                .thenReturn(new DeviceCredential("sim-cred-new", SECRET));

        DeviceVO vo = service.register(request());

        verify(registry).registerDevice(new RegistryDeviceSpec(DEVICE_ID, "node-001", PRODUCT_ID, "多参数监护仪"));
        verify(deviceMapper).insert(deviceCaptor.capture());
        IotDeviceEntity inserted = deviceCaptor.getValue();
        assertThat(inserted.getDeviceId()).as("本地档案自然键取请求 deviceId").isEqualTo(DEVICE_ID);
        assertThat(inserted.getProductId()).as("产品引用透传落行").isEqualTo(PRODUCT_ID);
        assertThat(inserted.getCredentialRef())
                .as("落库仅凭证引用且不含 secret 明文（14-iot §9 红线）")
                .isEqualTo("sim-cred-new")
                .doesNotContain(SECRET);
        assertThat(inserted.getStatus()).as("注册初态 INACTIVE（未首次上线）").isEqualTo(DeviceStatus.INACTIVE);
        assertThat(entityFieldNames()).as("实体无 secret 明文字段（密钥禁入库红线）").doesNotContain("secret");
        assertThat(vo.credentialSecret()).as("secret 仅随本次响应一次性透出").isEqualTo(SECRET);
        assertThat(vo.credentialRef()).as("响应凭证引用与落库一致").isEqualTo("sim-cred-new");
    }

    @Test
    @DisplayName("重复注册：IOT-1008（409）前置拒绝，不触注册中心不落行")
    void registerRejectsDuplicateDeviceWithIot1008BeforeRegistry() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));

        assertThatThrownBy(() -> service.register(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_ALREADY_EXISTS);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verifyNoInteractions(registry);
        verify(deviceMapper, never()).insert(any(IotDeviceEntity.class));
    }

    @Test
    @DisplayName("注册拒绝：注册中心不可用 IOT-1022（503），本地不落孤儿行")
    void registerTranslatesRegistryUnavailableToIot1022() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        when(registry.registerDevice(any(RegistryDeviceSpec.class)))
                .thenThrow(new RegistryException("IoTDA 设备注册失败：timeout"));

        assertThatThrownBy(() -> service.register(request())).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
        verify(deviceMapper, never()).insert(any(IotDeviceEntity.class));
    }

    @Test
    @DisplayName("凭证重置热更新：credential_ref 轮换 + 新 secret 仅随响应一次性透出（更新投影无明文）")
    void resetCredentialRotatesCredentialRefAndExposesSecretOnce() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));
        when(registry.resetDeviceCredential(DEVICE_ID)).thenReturn(new DeviceCredential(NEW_CREDENTIAL_REF, SECRET));

        DeviceCredentialResetVO vo = service.resetCredential(DEVICE_ID);

        verify(deviceMapper).update(isNull(), updateCaptor.capture());
        String sqlSet = updateCaptor.getValue().getSqlSet();
        assertThat(sqlSet).as("本地仅更新凭证引用列").contains("credential_ref").doesNotContain(SECRET);
        assertThat(entityFieldNames()).as("实体无 secret 明文字段（密钥禁入库红线）").doesNotContain("secret");
        assertThat(vo.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(vo.credentialRef()).as("轮换后凭证引用随响应回显").isEqualTo(NEW_CREDENTIAL_REF);
        assertThat(vo.secret()).as("新 secret 经本次响应一次性返回（热更新语义）").isEqualTo(SECRET);
    }

    @Test
    @DisplayName("凭证重置拒绝：设备不存在 IOT-1006（404），不触注册中心")
    void resetCredentialRejectsUnknownDeviceWithIot1006() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.resetCredential(DEVICE_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("凭证重置拒绝：注册中心不可用 IOT-1022（503），本地 credential_ref 不变")
    void resetCredentialTranslatesRegistryUnavailableToIot1022() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.INACTIVE));
        when(registry.resetDeviceCredential(DEVICE_ID)).thenThrow(new RegistryException("IoTDA 凭证换发失败：timeout"));

        assertThatThrownBy(() -> service.resetCredential(DEVICE_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
        verify(deviceMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("停用：DISABLED 置位 CAS（status≠DISABLED 条件更新）命中即完成")
    void disableMarksDisabledViaCasConditionalUpdate() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));
        when(deviceMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.disable(DEVICE_ID);

        verify(deviceMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).as("CAS 置位仅投影 status 列").contains("status");
    }

    @Test
    @DisplayName("停用拒绝：已处 DISABLED 状态 IOT-1007（409），不触发更新")
    void disableRejectsAlreadyDisabledDeviceWithIot1007() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.DISABLED));

        assertThatThrownBy(() -> service.disable(DEVICE_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(deviceMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("停用拒绝：设备不存在 IOT-1006（404）")
    void disableRejectsUnknownDeviceWithIot1006() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.disable(DEVICE_ID))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.DEVICE_NOT_FOUND));
    }

    @Test
    @DisplayName("停用 CAS 落败：并发竞态影响行数 0 → IOT-1007（409）")
    void disableTranslatesCasMissToIot1007() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.OFFLINE));
        when(deviceMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.disable(DEVICE_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_STATE_NOT_ALLOWED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
    }

    @Test
    @DisplayName("影子直通：Registry desired/reported 双面原样映射 VO，不做二次加工")
    void shadowPassesThroughRegistryDesiredAndReported() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));
        when(registry.shadow(DEVICE_ID))
                .thenReturn(new DeviceShadow(Map.of("workMode", "sleep"), Map.of("battery", 87)));

        DeviceShadowVO vo = service.shadow(DEVICE_ID);

        verify(registry).shadow(DEVICE_ID);
        assertThat(vo.desired()).as("期望面直通").containsExactlyEntriesOf(Map.of("workMode", "sleep"));
        assertThat(vo.reported()).as("上报面直通").containsExactlyEntriesOf(Map.of("battery", 87));
    }

    @Test
    @DisplayName("影子查询拒绝：设备不存在 IOT-1006（404），不触注册中心")
    void shadowRejectsUnknownDeviceWithIot1006() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.shadow(DEVICE_ID))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.DEVICE_NOT_FOUND));
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("影子查询拒绝：注册中心不可用 IOT-1022（503）")
    void shadowTranslatesRegistryUnavailableToIot1022() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));
        when(registry.shadow(DEVICE_ID)).thenThrow(new RegistryException("IoTDA 影子查询失败：timeout"));

        assertThatThrownBy(() -> service.shadow(DEVICE_ID)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("分页：page/size 缺省补齐（0/20）+ 三过滤透传 + VO 出网")
    void pageAppliesDefaultsAndFilters() {
        when(deviceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<IotDeviceEntity> result = invocation.getArgument(0);
            result.setRecords(List.of(deviceEntity(DeviceStatus.INACTIVE)));
            result.setTotal(1);
            return result;
        });

        PageResult<DeviceVO> defaults = service.page(new DeviceQueryRequest(null, null, null, null, null));
        assertThat(defaults.page()).as("0 基页码按请求口径回显").isZero();
        assertThat(defaults.size()).as("size 缺省 20").isEqualTo(20);
        assertThat(defaults.content()).as("实体经 VO 工厂出网").hasSize(1);
        assertThat(defaults.content().get(0).deviceId()).isEqualTo(DEVICE_ID);
        assertThat(defaults.content().get(0).credentialSecret())
                .as("分页视图无 secret 面（一次性透出仅限注册响应）")
                .isNull();

        PageResult<DeviceVO> filtered =
                service.page(new DeviceQueryRequest(1, 5, 1001L, DeviceStatus.ONLINE, PRODUCT_ID));
        assertThat(filtered.page()).as("0 基页码按请求口径回显").isEqualTo(1);
        assertThat(filtered.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("详情查询：命中回显且无 secret 面 / 未命中 IOT-1006（404）")
    void getByIdReturnsViewWithoutSecretOrThrowsNotFound() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(deviceEntity(DeviceStatus.ONLINE));
        DeviceVO vo = service.getById(DEVICE_ID);
        assertThat(vo.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(vo.status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(vo.credentialSecret()).as("详情视图无 secret 面").isNull();

        when(deviceMapper.selectById("ghost")).thenReturn(null);
        assertThatThrownBy(() -> service.getById("ghost"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(IotErrorCode.DEVICE_NOT_FOUND));
    }

    /** 构造注册请求夹具（六端点 POST 入参投影） */
    private static DeviceRegisterRequest request() {
        return new DeviceRegisterRequest(DEVICE_ID, "node-001", PRODUCT_ID, "多参数监护仪", "MONITOR", DeviceAccessMode.A);
    }

    /** 构造档案实体夹具（分页/详情/状态守卫取数面） */
    private static IotDeviceEntity deviceEntity(DeviceStatus status) {
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(DEVICE_ID);
        entity.setNodeId("node-001");
        entity.setProductId(PRODUCT_ID);
        entity.setDeviceName("多参数监护仪");
        entity.setDeviceType("MONITOR");
        entity.setAccessMode(DeviceAccessMode.A);
        entity.setWardId(1001L);
        entity.setCredentialRef("sim-cred-origin");
        entity.setStatus(status);
        entity.setLastOnlineAt(OffsetDateTime.parse("2026-09-20T08:00:00Z"));
        entity.setLastOfflineAt(OffsetDateTime.parse("2026-09-20T09:00:00Z"));
        return entity;
    }

    /** 实体全字段名清单（secret 禁入库红线的反射断言面） */
    private static List<String> entityFieldNames() {
        return Arrays.stream(IotDeviceEntity.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
    }
}
