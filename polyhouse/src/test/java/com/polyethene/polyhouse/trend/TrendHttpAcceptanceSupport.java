package com.polyethene.polyhouse.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.RedisConstants;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** G4免费集成和真实小样本共用HTTP夹具，令牌只在内存中，不进入断言错误或日志。 */
abstract class TrendHttpAcceptanceSupport {
    static final String PASSWORD = "Trend-isolated-fixture-2026!";
    static final String CACHE_KEY = "polyhouse:trend:view:latest";
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    long actor(UserRoleEnum role, boolean required) {
        String name = "g4_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long id = Long.parseLong(auth.register(new RegisterDTO(name, PASSWORD, "隔离验收账号")).id());
        jdbc.update("UPDATE sys_user SET role=?,password_change_required=? WHERE id=?", role.getCode(), required, id);
        return id;
    }

    String login(long id) throws Exception {
        return login(id, PASSWORD);
    }

    String login(long id, String password) throws Exception {
        String name = jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, id);
        var response = call("POST", "/api/auth/login", null, Map.of("username", name, "password", password, "clientType", "PC"));
        assertThat(response.statusCode()).isEqualTo(200);
        String token = data(response).path("accessToken").asText();
        assertThat(token.isBlank()).isFalse();
        return token;
    }

    HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var request = builder.build();
        for (int attempt = 0; ; attempt++) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // 多个真实角色共用生产限额，不篡改过滤器；只按服务端短限流提示等待，业务冷却不重试。
            String retry = response.headers().firstValue("Retry-After").orElse("");
            if (response.statusCode() != 429 || !retry.matches("[1-5]") || attempt >= 5) return response;
            Thread.sleep(Long.parseLong(retry) * 1000 + 100);
        }
    }

    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }

    String submit(String token) throws Exception {
        String id = UUID.randomUUID().toString();
        var reply = call("POST", "/api/manage/trend/jobs", token, Map.of("requestId", id));
        assertThat(reply.statusCode()).isEqualTo(202);
        assertThat(data(reply).path("accepted").asBoolean()).isTrue();
        return id;
    }

    String awaitTerminal(String id) throws Exception {
        return awaitTerminal(id, Duration.ofSeconds(30));
    }

    String awaitTerminal(String id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String state = jdbc.queryForObject("SELECT state FROM tool_trend_job WHERE id=?", String.class, id);
            if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "INTERRUPTED").contains(state)) return state;
            Thread.sleep(100);
        }
        throw new java.util.concurrent.TimeoutException("异步任务未在验收等待期限内完成");
    }

    void expireCooldown() {
        // 仅测试随机/专属验收库中的时间夹具，避免为验证版本更新真实等待一分钟。
        jdbc.update("UPDATE tool_trend_job_slot SET last_accepted_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 61 SECOND)");
    }

    void clearOwnedRedisKeys() {
        for (long id : jdbc.queryForList("SELECT id FROM sys_user", Long.class)) {
            redis.delete(List.of("PC", "H5", "PLUGIN").stream().map(client -> RedisConstants.buildAuthTokenKey(id, client)).toList());
        }
        redis.delete(CACHE_KEY);
    }
}
