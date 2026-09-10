package com.fuyun.system.controller;

import com.fuyun.system.service.IDictQueryService;
import com.fuyun.system.vo.DictVersionVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典契约型读端点（GET /api/v1/system/dicts/{type}?version=，M01 Spec §7）。
 *
 * <p>受 401 认证拦截（权限点 /api/v1/system/dicts/{type} 已随 V303 种子登记）；
 * 响应携带 {@code Cache-Control: no-cache} 供客户端协商（P0 不建服务端缓存，协商语义
 * 预留 P1 字典缓存版本化）。职责边界：仅参数透传与响应编排（宪法 B.1）。
 */
@RestController
@RequestMapping("/api/v1/system/dicts")
public class DictController {

    private final IDictQueryService dictQueryService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictQueryService 字典契约型读服务，非空；注入接口类型（B.2-2）
     */
    public DictController(IDictQueryService dictQueryService) {
        this.dictQueryService = dictQueryService;
    }

    /**
     * 读取字典版本（含条目全量清单，契约型读豁免分页，理由见 IDictQueryService javadoc）。
     *
     * @param typeCode 字典类型编码（路径参数），非空
     * @param version  版本号（查询参数），可空；null=当前 PUBLISHED 版本
     * @return 200 + 版本出参；Cache-Control: no-cache（每次协商，不共享缓存）
     */
    @GetMapping("/{typeCode}")
    public ResponseEntity<DictVersionVO> readVersion(
            @PathVariable("typeCode") String typeCode,
            @RequestParam(value = "version", required = false) Integer version) {
        // no-cache=可缓存但每次须协商再验证：P0 无 ETag/Last-Modified 协商载体，语义等价每次回源
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(dictQueryService.readPublished(typeCode, version));
    }
}
