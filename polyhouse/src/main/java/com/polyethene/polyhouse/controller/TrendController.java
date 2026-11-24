package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.TrendBriefingQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendPeriodsQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendWindowQueryDTO;
import com.polyethene.polyhouse.model.vo.TrendBriefingVO;
import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.model.vo.TrendWindowStatsVO;
import com.polyethene.polyhouse.service.TrendBriefingService;
import com.polyethene.polyhouse.service.TrendReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 匿名只读入口逐方法显式声明，未来管理接口不会因类级跳过鉴权被意外公开。 */
@RestController
@RequestMapping("/api/trend")
public class TrendController {
    private final TrendReportService reports;
    private final TrendBriefingService briefings;

    /** 只访问查询服务，不注入采集和发布组件，确保阅读没有外部调用副作用。 */
    public TrendController(TrendReportService reports, TrendBriefingService briefings) {
        this.reports = reports;
        this.briefings = briefings;
    }

    /** 只返回已发布日期，无报告时是空列表；日历日期及分页边界必须校验。 */
    @SkipBearerAuth
    @GetMapping("/periods")
    public Result<TrendPeriodsVO> periods(@Valid @ModelAttribute TrendPeriodsQueryDTO request) {
        return Result.success(reports.periods(request));
    }

    /** 未传期号读取最新日报，指定日期无已发布版本返回 404，绝不自动生成。 */
    @SkipBearerAuth
    @GetMapping("/report")
    public Result<TrendReportVO> report(@Valid @ModelAttribute TrendReportQueryDTO request) {
        return Result.success(reports.report(request));
    }

    /** 基于已入库的近 7 日已发布日报做确定性风向统计，零模型费用且绝不自动生成。 */
    @SkipBearerAuth
    @GetMapping("/window")
    public Result<TrendWindowStatsVO> window(@Valid @ModelAttribute TrendWindowQueryDTO request) {
        return Result.success(reports.window(request));
    }

    /** 只读查询 7 日风口简报；当期未生成时返回 200 且 status=ABSENT，绝不触发模型调用。 */
    @SkipBearerAuth
    @GetMapping("/briefing")
    public Result<TrendBriefingVO> briefing(@Valid @ModelAttribute TrendBriefingQueryDTO request) {
        return Result.success(briefings.query(request));
    }
}
