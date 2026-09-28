package com.fuyun.ward.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.ward.dto.RegisterColdChainRecordRequest;
import com.fuyun.ward.dto.SaveColdChainArchiveRequest;
import com.fuyun.ward.enums.ColdChainPurpose;
import com.fuyun.ward.vo.ColdChainArchiveVO;
import com.fuyun.ward.vo.ColdChainRecordVO;
import java.util.List;

/**
 * 冷链合规台账服务接口（FU-M16 冷链域）：档案 CRUD + 三类型记录登记（ALARM_HANDLE 双人核对与
 * 归档事件发布）+ 巡检 overdue 读时惰性判定。实现归 ColdChainServiceImpl（装配归 WardWebConfig）。
 */
public interface IColdChainService {

    /**
     * 建档。
     *
     * @param request 保存请求，非空；来源：POST /cold-chain/archives 请求体（@Valid 后置）
     * @return 建档后视图（overdue 按新建语义为 true——当日尚无巡检）
     */
    ColdChainArchiveVO createArchive(SaveColdChainArchiveRequest request);

    /**
     * 更新档案（用途/设备/区间/验证到期/存量摘要可维护；archive_no 不可变）。
     *
     * @param archiveNo 档案业务号，非空
     * @param request   保存请求，非空
     * @return 更新后视图（带 overdue 注记）
     */
    ColdChainArchiveVO updateArchive(String archiveNo, SaveColdChainArchiveRequest request);

    /**
     * 删除档案（逻辑删；不级联记录——台账留痕红线）。
     *
     * @param archiveNo 档案业务号，非空
     */
    void deleteArchive(String archiveNo);

    /**
     * 档案详情（带巡检 overdue 注记）。
     *
     * @param archiveNo 档案业务号，非空
     * @return 档案视图
     */
    ColdChainArchiveVO getArchive(String archiveNo);

    /**
     * 档案分页（按用途过滤；带巡检 overdue 注记）。
     *
     * @param purpose 用途过滤，可空=不过滤
     * @param page    页码（0 基），非空
     * @param size    单页条数，非空
     * @return 分页出参
     */
    PageResult<ColdChainArchiveVO> pageArchives(ColdChainPurpose purpose, int page, int size);

    /**
     * 登记记录（INSPECTION 巡检 / ALARM_HANDLE 告警处置 / DEVIATION 偏差共用入口）：
     * ALARM_HANDLE 必填 alarm_ref+second_operator（WD-1005），登记完成发布
     * ward.cold-chain.alert-archived（事务内发布，AFTER_COMMIT 出 MQ）。
     *
     * @param archiveNo 档案业务号，非空
     * @param request   登记请求，非空
     * @return 登记后记录视图
     */
    ColdChainRecordVO registerRecord(String archiveNo, RegisterColdChainRecordRequest request);

    /**
     * 记录列表（档案维度，登记时刻倒序）。
     *
     * @param archiveNo 档案业务号，非空
     * @return 记录视图清单；无记录为空清单
     */
    List<ColdChainRecordVO> listRecords(String archiveNo);
}
