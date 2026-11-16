package com.polyethene.polyhouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.config.TrendProperties;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.dto.TrendPeriodsQueryDTO;
import com.polyethene.polyhouse.model.dto.TrendReportQueryDTO;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.model.vo.TrendPeriodsVO;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 公开查询以数据库发布指针为准，可选内容Redis与身份无关，阅读不会触发生成。 */
@Service
public class TrendReportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrendReportService.class);
    private final TrendMapper mapper;
    private final ObjectMapper json;
    private final TrendProperties settings;
    private final TrendReportCache cache;

    /** 延迟到实际查询才访问新表，未迁移的既有应用仍可正常启动。 */
    public TrendReportService(TrendMapper mapper, ObjectMapper json, TrendProperties settings) {
        this(mapper, json, settings, null);
    }

    /** 内容缓存独立开关，旧内部构造/未启用配置保持原查询路径。 */
    @Autowired
    public TrendReportService(TrendMapper mapper, ObjectMapper json, TrendProperties settings, TrendReportCache cache) {
        this.mapper = mapper;
        this.json = json;
        this.settings = settings;
        this.cache = cache;
    }

    /** 校验日期与周期，读取最新或某日当前发布版本；无报告返回 404。 */
    public TrendReportVO report(TrendReportQueryDTO request) {
        requireEnabled();
        if (!"DAILY".equals(request.periodType())) throw invalid();
        LocalDate date = date(request.periodKey());
        try {
            if (cache != null && cache.enabled()) {
                var header = date == null ? mapper.latestHeader() : mapper.publishedHeader(date);
                if (header == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                String cached = cache.read(header);
                if (cached != null) {
                    try { return decode(new TrendReportRecord(header.id(), header.periodDate(), header.capturedAt(), cached)); }
                    catch (BusinessException damagedCache) { LOGGER.warn("Trend cached content rejected; reading published database version"); }
                }
                var record = mapper.findVersion(header.id());
                if (record == null) throw unavailable();
                var view = decode(record);
                cache.write(record);
                return view;
            }
            var record = date == null ? mapper.latest() : mapper.published(date);
            if (record == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            return decode(record);
        } catch (DataAccessException failure) { throw unavailable(); }
    }

    /** 多取一项判断是否还有下一页，不靠空页猜测，也不计算全表 COUNT。 */
    public TrendPeriodsVO periods(TrendPeriodsQueryDTO request) {
        requireEnabled();
        if (!"DAILY".equals(request.periodType()) || request.limit() < 1 || request.limit() > 30) throw invalid();
        LocalDate before = date(request.before());
        try {
            var dates = mapper.periods(before, request.limit() + 1);
            boolean more = dates.size() > request.limit();
            var page = dates.subList(0, Math.min(dates.size(), request.limit()));
            return new TrendPeriodsVO("DAILY", page, more ? page.getLast() : null);
        } catch (DataAccessException failure) { throw unavailable(); }
    }

    /** 读取归档时检查结构和版本身份，损坏数据不以成功报告公开。 */
    public TrendReportVO decode(TrendReportRecord record) {
        try {
            if (record.payload() == null || record.payload().length() > 256 * 1024) throw unavailable();
            var view = json.readValue(record.payload(), TrendReportVO.class);
            // JSON保留纳秒而DATETIME(6)仅有微秒，以相同精度验证身份，不能误拒合法历史快照。
            if (!record.id().equals(view.reportId()) || !record.periodDate().equals(view.periodKey())
                    || !"DAILY".equals(view.periodType()) || view.items().isEmpty() || view.items().size() > 25
                    || view.capturedAt() == null || record.capturedAt() != null && !record.capturedAt().equals(
                    LocalDateTime.ofInstant(view.capturedAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))) throw unavailable();
            return view;
        } catch (JsonProcessingException | IllegalArgumentException failure) { throw unavailable(); }
    }

    private void requireEnabled() { if (!settings.enabled()) throw unavailable(); }

    private static LocalDate date(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            var parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1000) throw invalid();
            return parsed;
        }
        catch (DateTimeParseException failure) { throw invalid(); }
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_PARAMETER); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}
