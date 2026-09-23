package com.fuyun.nursing.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * JSONB 文本列 TypeHandler（String ↔ jsonb，PG 专用）：MyBatis 默认对 String 参数以 varchar
 * 类型发送，PostgreSQL 强类型拒绝 varchar→jsonb 隐式转换（Task 11 IT 实测暴露：评估单 answers
 * 与交接班四 JSONB 快照列经 MP insert 真实落库必 500「column is of type jsonb but expression
 * is of type character varying」，单测 mock mapper 不可见）。写侧以 {@link Types#OTHER} 发送
 * ——pgjdbc 以未指定类型参数承载，服务端按目标列 jsonb 上下文收参（免强类型拒绝，且无需
 * 编译期依赖 pg 驱动的 PGobject）；读侧 getString 直读（pgjdbc 原生行为，与
 * NursingWardConfig 只读形态一致）。经实体字段 {@code @TableField(typeHandler = ...)} 逐列
 * 挂载（禁全局注册——String 全局接管会波及全部文本参数）。
 * 线程安全：无状态。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    /**
     * 写侧：以 OTHER 类型参数发送（类型推断交由服务端按 jsonb 目标列上下文收参）。
     *
     * @param ps        语句载体，非空
     * @param i         参数位序
     * @param parameter 服务层已序列化的 JSON 文本，非空
     * @param jdbcType  JDBC 类型（本 handler 恒 OTHER）
     * @throws SQLException 驱动层异常（连接中断等，交由上层事务回滚）
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    /**
     * 读侧：jsonb 列经 getString 直读（pgjdbc 返回 JSON 文本，与 NursingWardConfig 读取形态同源）。
     *
     * @param rs         结果集，非空
     * @param columnName 列名
     * @return JSON 文本；SQL NULL 返回 null
     * @throws SQLException 驱动层异常
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 读侧（列序形态）：同 {@link #getNullableResult(ResultSet, String)}。
     *
     * @param rs          结果集，非空
     * @param columnIndex 列序
     * @return JSON 文本；SQL NULL 返回 null
     * @throws SQLException 驱动层异常
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 读侧（存储过程出参形态）：同 {@link #getNullableResult(ResultSet, String)}。
     *
     * @param cs          调用语句，非空
     * @param columnIndex 出参列序
     * @return JSON 文本；SQL NULL 返回 null
     * @throws SQLException 驱动层异常
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
