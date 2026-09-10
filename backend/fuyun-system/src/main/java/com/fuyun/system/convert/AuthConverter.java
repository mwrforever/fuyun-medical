package com.fuyun.system.convert;

import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenPair;
import com.fuyun.system.vo.LoginResponse;
import com.fuyun.system.vo.UserVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * 认证域 MapStruct 转换器（BRIEF-PR3-01 §3.1）：会话身份 → 用户出参、令牌对 → 登录响应组装。
 *
 * <p>componentModel 取默认（非 spring）：Bean 注册点为 SystemWebConfig @Import 经
 * {@link Mappers#getMapper} 装配（宪法 B.1 装配归 app 侧配置，避免生成 Impl 类依赖组件扫描）。
 * userId/orgId 映射保持 Long 形态，JSON 字符串化由全局 Jackson 定制承担（A.3-8 禁零散处理）。
 */
@Mapper
public interface AuthConverter {

    /** 默认组件模型的生成实现获取入口（单测与装配同源） */
    AuthConverter INSTANCE = Mappers.getMapper(AuthConverter.class);

    /**
     * 会话身份 → 用户出参：userId/loginName/displayName/orgId/roles 同名映射
     * （employeeId 不出参，防内部身份标识外泄）。
     *
     * @param user 登录会话身份，非空
     * @return 用户出参，非空
     */
    UserVO toUserVO(SessionUser user);

    /**
     * 登录成功响应组装：令牌对 + 用户出参 + 令牌方案与有效期聚合。
     *
     * @param pair       签发令牌对，非空；access/refresh 各取其位
     * @param tokenType  令牌方案名（"Bearer"），非空；来源：SecurityConstants
     * @param expiresIn  access 有效期（秒），非负；来源：SecurityProperties.accessTokenTtl 换算
     * @param user       用户出参，非空；来源：{@link #toUserVO(SessionUser)}
     * @return 登录响应，非空
     */
    @Mapping(target = "accessToken", source = "pair.accessToken")
    @Mapping(target = "refreshToken", source = "pair.refreshToken")
    @Mapping(target = "user", source = "user")
    LoginResponse toLoginResponse(TokenPair pair, String tokenType, long expiresIn, UserVO user);
}
