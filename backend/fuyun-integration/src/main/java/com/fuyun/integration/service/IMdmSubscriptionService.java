package com.fuyun.integration.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.entity.MdmSubscription;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;

/**
 * 主数据分发订阅服务：订阅登记 / 注销 / 矩阵查询 / 订阅方清单（M20 FU-M20-04）。
 *
 * <p>边界（M20 红线 3）：本服务只做分发治理（谁订了什么、订到什么版本），不存任何主数据业务值，
 * 也不修改主数据本身（权威源唯一 = M01）。
 */
public interface IMdmSubscriptionService extends IService<MdmSubscription> {

    /**
     * 分页查询订阅矩阵（主题 × 订阅方 × 版本 × 对账状态）。
     *
     * @param query 查询条件，非空；page 0 基、size 1-200
     * @return 分页出参（0 基页码），非空
     */
    PageResult<MdmSubscriptionVO> query(MdmSubscriptionQuery query);

    /**
     * 登记订阅关系（(topic, subscriber_module) 幂等：已存在时返回既有行不覆盖）。
     *
     * @param request 登记请求，非空；topic 须在 MdmConstants.TOPICS 内
     * @return 登记后的订阅出参，非空
     * @throws com.fuyun.common.exception.BizException 未知主题（INT-1012，400）时触发
     */
    MdmSubscriptionVO register(MdmSubscriptionCreateRequest request);

    /**
     * 注销订阅关系（逻辑删 deleted=1）。
     *
     * @param id 订阅记录 ID，非空
     * @throws com.fuyun.common.exception.BizException 记录不存在（INT-1011，404）时触发
     */
    void unregister(Long id);

    /**
     * 取指定主题的订阅方模块清单（分发流水登记的 target_modules 数据源）。
     *
     * @param topic 主数据主题，非空；取值见 MdmConstants.TOPICS
     * @return 订阅方模块标识清单，非 null；无订阅方时为空清单（广播事件仍记流水）
     */
    List<String> listSubscriberModules(String topic);
}
