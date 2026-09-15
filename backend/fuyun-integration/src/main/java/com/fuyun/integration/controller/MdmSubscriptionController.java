package com.fuyun.integration.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主数据订阅治理端点（/api/v1/integration/mdm-subscriptions，M20 §7 + FU-M20-04）。
 *
 * <p>矩阵语义：GET 列表即「主题 × 订阅方 × 版本 × 对账状态」矩阵（Spec §6 管理界面口径）。
 * 受既有 /api/v1/** 认证拦截；职责边界：仅校验 + 编排（宪法 B.1），禁业务逻辑与事务。
 */
@RestController
@RequestMapping("/api/v1/integration/mdm-subscriptions")
@Validated
public class MdmSubscriptionController {

    private final IMdmSubscriptionService mdmSubscriptionService;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param mdmSubscriptionService 主数据订阅服务，非空；注入接口类型（B.2-2）
     */
    public MdmSubscriptionController(IMdmSubscriptionService mdmSubscriptionService) {
        this.mdmSubscriptionService = mdmSubscriptionService;
    }

    /**
     * 分页查询订阅矩阵。
     *
     * @param topic            主题过滤，可空 = 不过滤
     * @param subscriberModule 订阅方过滤，可空 = 不过滤
     * @param page             页码（0 基），缺省 0
     * @param size             单页条数（1-200），缺省 20
     * @return 分页出参（0 基页码）
     */
    @GetMapping
    public PageResult<MdmSubscriptionVO> list(
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "subscriberModule", required = false) String subscriberModule,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return mdmSubscriptionService.query(new MdmSubscriptionQuery(topic, subscriberModule, page, size));
    }

    /**
     * 登记订阅关系（(topic, subscriber_module) 幂等）。
     *
     * @param request 登记请求，非空；JSR-303 校验失败渲染 400
     * @return 登记后的订阅出参
     */
    @PostMapping
    public MdmSubscriptionVO register(@Valid @RequestBody MdmSubscriptionCreateRequest request) {
        return mdmSubscriptionService.register(request);
    }

    /**
     * 注销订阅关系（逻辑删）。
     *
     * @param id 订阅记录 ID（路径参数），非空
     * @return 204 无响应体；不存在时由全局渲染器输出 404 ProblemDetail（INT-1011）
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unregister(@PathVariable("id") Long id) {
        mdmSubscriptionService.unregister(id);
        return ResponseEntity.noContent().build();
    }
}
