package com.polyethene.polyhouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.TrendMapper;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.entity.TrendReportRecord;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** 供受控任务内部发布不可变日报，公开阅读不触发生成；归档不承担任务身份和网络职责。 */
@Service
public class TrendArchiveService {
    private final TrendMapper mapper;
    private final TrendReportService reports;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    /** 发布事务独立于网络调用，缩短锁占用；事务超时避免长期阻塞同日更新。 */
    public TrendArchiveService(TrendMapper mapper, TrendReportService reports, ObjectMapper json,
                                PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.reports = reports;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    /** 同一 UUID 幂等；较旧采集不能覆盖较新内容，保留旧版本以供追溯。 */
    public TrendReportVO publish(String publicationId, TrendCollection collection) {
        if (publicationId == null || !publicationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || collection == null || collection.capturedAt() == null || collection.projects().isEmpty()
                || collection.projects().size() > 25) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < collection.projects().size(); i++) {
            var item = collection.projects().get(i).snapshot();
            if (item == null || item.rankNum() != i + 1 || !GithubTrendingParser.REPOSITORY.matcher(item.fullName()).matches()
                    || !item.repoUrl().equals("https://github.com/" + item.fullName())
                    || !names.add(item.fullName().toLowerCase(Locale.ROOT))) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        LocalDate date = collection.capturedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        boolean complete = collection.projects().stream().allMatch(project -> "NOT_REQUIRED".equals(project.snapshot().insightStatus())
                || (project.snapshot().insight() != null && Set.of("ANALYZED", "REUSED").contains(project.snapshot().insightStatus())));
        var view = new TrendReportVO(publicationId, "DAILY", date, collection.capturedAt(), Instant.now(),
                GithubTrendingParser.SOURCE, complete, collection.projects().stream().map(TrendCollection.Project::snapshot).toList());
        final String payload;
        try { payload = json.writeValueAsString(view); }
        catch (JsonProcessingException failure) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
        if (payload.length() > 256 * 1024) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        return transaction.execute(status -> {
            mapper.ensureDay(date);
            var current = mapper.lockDay(date);
            var previous = mapper.findVersion(publicationId);
            if (previous != null) {
                if (!previous.periodDate().equals(date) || !previous.capturedAt().equals(utc(collection.capturedAt()))) {
                    throw new BusinessException(ErrorCode.CONFLICT);
                }
                return reports.decode(previous);
            }
            if (current != null && current.id() != null && !current.capturedAt().isBefore(utc(collection.capturedAt()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "已有更新的日报版本");
            }
            mapper.insertVersion(new TrendReportRecord(publicationId, date, utc(collection.capturedAt()), payload));
            if (mapper.publish(date, publicationId) != 1) throw new BusinessException(ErrorCode.CONFLICT);
            return view;
        });
    }

    private static LocalDateTime utc(Instant instant) {
        // MySQL DATETIME(6) 只有微秒，先统一精度使幂等比较不因纳秒尾数产生冲突。
        return LocalDateTime.ofInstant(instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
