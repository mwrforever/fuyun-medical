package com.fuyun.app.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局装配：mapper 扫描与三大插件集中注册（backend 宪法 A.4.3-19，装配归 app）。
 *
 * <p>@MapperScan 以注解过滤一次覆盖 com.fuyun 全部包（含未来模块）：mapper 接口须标注 @Mapper
 * 方可被扫描，避免误注册非 mapper 接口；禁散落 @MapperScan 于各业务模块。
 * 插件顺序按 MP 官方文档建议（"使用多个功能需要注意顺序关系"）：对 SQL 进行单次改造的插件优先放入，
 * 不对 SQL 改造的最后放入——分页（Count 转 Query 与 limit 改写）→ 乐观锁（追加 version 条件）→
 * 防全表攻击（仅校验不改写 SQL），顺序一经确立不得随意调换。
 */
@Configuration
@MapperScan(basePackages = "com.fuyun", annotationClass = Mapper.class)
public class MybatisPlusConfig {

    /**
     * 注册 MP 分页/防全表/乐观锁三大插件（唯一注册点，禁止散落配置）。
     *
     * @return MybatisPlusInterceptor；PaginationInnerInterceptor maxLimit=2000 硬约束单页上限
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件：maxLimit=2000 兜底，越界请求被截断（A.4.3-17 查询必带分页）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(2000L);
        interceptor.addInnerInterceptor(pagination);
        // 乐观锁：@Version 实体并发更新校验（官方顺序：改写型插件在防全表校验之前）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 防全表攻击：无 WHERE 的 UPDATE/DELETE 直接拒绝执行（仅校验，官方顺序置于末位）
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
