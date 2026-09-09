package com.fuyun.system.controller;

import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.service.IDictItemService;
import com.fuyun.system.service.IDictVersionService;
import com.fuyun.system.vo.DictItemVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典版本操作端点（POST /api/v1/system/dict-versions/{versionId}/**，BRIEF-PR3-01 §3.2）。
 *
 * <p>权限点与 V303 种子 perm_code 对齐（/api/v1/system/dict-versions/{versionId}/items、
 * /api/v1/system/dict-versions/{versionId}/publish）；受 401 认证拦截。
 * 职责边界：仅 @Valid 校验 + 调用 service + 编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system/dict-versions")
public class DictVersionController {

    private final IDictItemService dictItemService;

    private final IDictVersionService dictVersionService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictItemService    字典条目服务，非空；条目新增用例
     * @param dictVersionService 字典版本服务，非空；发布用例
     */
    public DictVersionController(IDictItemService dictItemService, IDictVersionService dictVersionService) {
        this.dictItemService = dictItemService;
        this.dictVersionService = dictVersionService;
    }

    /**
     * 新增字典条目：所属版本必须为 DRAFT（已发布版本禁改，409）。
     *
     * @param versionId 字典版本 ID（路径参数），非空
     * @param request   条目创建请求，非空；itemCode/itemName 非空由 JSR-303 校验
     * @return 条目出参
     */
    @PostMapping("/{versionId}/items")
    public DictItemVO addItem(
            @PathVariable("versionId") Long versionId, @Valid @RequestBody DictItemCreateRequest request) {
        return dictItemService.addItem(versionId, request);
    }

    /**
     * 发布版本：DRAFT→PUBLISHED 状态机 + 旧 PUBLISHED 置 DEPRECATED + 事务提交后广播
     * system.dict.published。
     *
     * @param versionId 字典版本 ID（路径参数），非空
     */
    @PostMapping("/{versionId}/publish")
    public void publish(@PathVariable("versionId") Long versionId) {
        dictVersionService.publish(versionId);
    }
}
