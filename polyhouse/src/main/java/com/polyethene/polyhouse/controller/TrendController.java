package com.polyethene.polyhouse.controller;

import com.polyethene.polyhouse.common.annotation.SkipBearerAuth;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.TrendPeriodsQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.TrendReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 匿名只读入口逐方法显式声明，未来管理接口不会因类级跳过鉴权被意外公开。 */
@RestController
@RequestMapping("/api/trend")
public class TrendController {
    private final TrendReportService reports;

    /** 只访问查询服务，不注入采集和发布组件，确保阅读没有外部调用副作用。 */
    public TrendController(TrendReportService reports) { this.reports = reports; }

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
}
