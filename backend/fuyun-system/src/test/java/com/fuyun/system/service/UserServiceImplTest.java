package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.UserEntity;
import com.fuyun.system.enums.UserStatus;
import com.fuyun.system.mapper.UserMapper;
import com.fuyun.system.service.impl.UserServiceImpl;
import java.time.OffsetDateTime;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 用户账号服务单元测试（登录状态机写路径，M01 Spec §5 + SecurityConstants 阈值语义）。
 *
 * <p>覆盖：按登录名查询投影、失败计数累加（未达阈值不置锁定）、达阈值 5 次置 locked_until
 * （now+30 分钟）、成功复位（计数清零/锁定清空/最近登录时刻）。mapper 以 Mockito 模拟，
 * 更新内容经捕获的 Wrapper 参数断言（业务写回值，不绑定 SQL 细节）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Captor
    private ArgumentCaptor<Wrapper<UserEntity>> updateWrapperCaptor;

    private UserServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件/更新集的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl();
        // ServiceImpl 的 baseMapper/entityClass 为 protected 字段，单测经反射注入（等价容器装配）
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        ReflectionTestUtils.setField(service, "entityClass", UserEntity.class);
    }

    @Test
    @DisplayName("按登录名查询：命中返回账号实体（含口令哈希与锁定状态，供认证链路使用）")
    void findByLoginNameReturnsMatchedUser() {
        UserEntity expected = new UserEntity();
        expected.setId(123L);
        expected.setLoginName("admin");
        when(userMapper.selectOne(any())).thenReturn(expected);

        UserEntity actual = service.findByLoginName("admin");

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("失败计数累加未达阈值：仅写 fail_count，不触碰锁定列")
    void recordLoginFailureIncrementsCountWithoutLockBelowThreshold() {
        UserEntity user = user(1);

        service.recordLoginFailure(user);

        verify(userMapper).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<UserEntity> wrapper = asLambdaUpdateWrapper(updateWrapperCaptor.getValue());
        // 业务写回断言：新计数=2；无任何时刻值写入（未置锁定）
        assertThat(wrapper.getParamNameValuePairs().values()).contains(2);
        assertThat(wrapper.getParamNameValuePairs().values()).noneMatch(value -> value instanceof OffsetDateTime);
    }

    @Test
    @DisplayName("连续失败达阈值 5 次：置 locked_until 为约 30 分钟后（自动到期恢复）")
    void recordLoginFailureLocksAccountAtThreshold() {
        UserEntity user = user(4);

        service.recordLoginFailure(user);

        verify(userMapper).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<UserEntity> wrapper = asLambdaUpdateWrapper(updateWrapperCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(5);
        // 锁定截止时刻写入：与 now+30 分钟偏差在秒级内（锁定时长语义断言）
        OffsetDateTime lockedUntil = wrapper.getParamNameValuePairs().values().stream()
                .filter(OffsetDateTime.class::isInstance)
                .map(OffsetDateTime.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(lockedUntil).isAfter(OffsetDateTime.now().plusMinutes(29));
        assertThat(lockedUntil).isBefore(OffsetDateTime.now().plusMinutes(31));
    }

    @Test
    @DisplayName("登录成功复位：fail_count 清零 + locked_until 清空（显式写 null）+ last_login_at 更新")
    void recordLoginSuccessResetsFailureStateAndStampsLastLogin() {
        UserEntity user = user(3);

        service.recordLoginSuccess(user);

        verify(userMapper).update(isNull(), updateWrapperCaptor.capture());
        LambdaUpdateWrapper<UserEntity> wrapper = asLambdaUpdateWrapper(updateWrapperCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(0);
        assertThat(wrapper.getParamNameValuePairs().values()).contains((Object) null);
        assertThat(wrapper.getParamNameValuePairs().values()).anyMatch(value -> value instanceof OffsetDateTime);
    }

    /** 构造账号实体样本（仅状态机相关字段） */
    private UserEntity user(int failCount) {
        UserEntity user = new UserEntity();
        user.setId(123L);
        user.setLoginName("admin");
        user.setStatus(UserStatus.ACTIVE);
        user.setFailCount(failCount);
        return user;
    }

    /** 将捕获的 Wrapper 断言为 LambdaUpdateWrapper 以读取写回参数 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<UserEntity> asLambdaUpdateWrapper(Wrapper<UserEntity> wrapper) {
        return (LambdaUpdateWrapper<UserEntity>) wrapper;
    }
}
