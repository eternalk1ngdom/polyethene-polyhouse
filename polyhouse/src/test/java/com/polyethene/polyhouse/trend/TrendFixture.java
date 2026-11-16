package com.polyethene.polyhouse.trend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.model.entity.TrendCollection;
import com.polyethene.polyhouse.model.entity.User;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.vo.TrendReportVO;
import com.polyethene.polyhouse.service.trend.GithubTrendingParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 仅供技术雷达测试共用的公开假资料，不依赖开发库或真实令牌。 */
final class TrendFixture {
    private TrendFixture() { }
    static ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
    /** 只构造内存身份用于模拟验证，从不向数据库插入OWNER。 */
    static User owner() {
        var user = new User(); user.setId(42L); user.setRole(UserRoleEnum.OWNER); user.setStatus(1);
        user.setDeleted(0); user.setCredentialVersion(0L); user.setPasswordChangeRequired(false); return user;
    }
    static String html() {
        try (var input = Objects.requireNonNull(TrendFixture.class.getResourceAsStream("/trend/daily.html"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    static TrendCollection collection(String capturedAt) {
        return new TrendCollection(Instant.parse(capturedAt), new GithubTrendingParser().parse(html()).stream()
                .map(item -> new TrendCollection.Project(item, "a".repeat(40), "private internal material")).toList());
    }
    static TrendReportVO report() {
        var collection = collection("2026-11-15T22:30:00Z");
        return new TrendReportVO(UUID.randomUUID().toString(), "DAILY", java.time.LocalDate.of(2026, 11, 16),
                collection.capturedAt(), Instant.parse("2026-11-15T22:31:00Z"), GithubTrendingParser.SOURCE, false,
                collection.projects().stream().map(TrendCollection.Project::snapshot).toList());
    }
}
