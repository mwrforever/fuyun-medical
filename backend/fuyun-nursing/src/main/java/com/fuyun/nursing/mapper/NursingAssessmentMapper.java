package com.fuyun.nursing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.nursing.entity.NursingAssessment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 护理评估单 mapper：单表链式能力全集。写面仅 insert（评估单创建，唯一约束冲突由服务层转
 * NS-1016 幂等拒绝）与 MP updateById（高危联动 triggered_task_ref 同事务回填——行由本事务
 * 插入无并发窗口，无状态流转语义，不需要 CAS 条件更新）；读面患者评估清单为链式查询
 * （visit_id + 可选 scale_type 谓词，DB 侧 assessed_at 降序）。
 */
@Mapper
public interface NursingAssessmentMapper extends BaseMapper<NursingAssessment> {}
