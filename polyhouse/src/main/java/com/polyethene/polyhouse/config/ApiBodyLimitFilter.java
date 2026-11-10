package com.polyethene.polyhouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.result.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 在进入 JSON 解析器前限定读取量，防止没有 Content-Length 的请求绕过上限。 */
final class ApiBodyLimitFilter extends OncePerRequestFilter {

    // 最大 DTO 字段为 60,023 字符。每字符最多占 6 字节 JSON 转义，另留字段名及其他字段空间。
    static final int MAX_BODY_BYTES = 384 * 1024;
    private final ObjectMapper mapper;

    ApiBodyLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        if (declaredLength == 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Result.failure(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    /** 生产 API 使用同步 MVC 读取，将已检查的字节原样交给下游解析。 */
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final ServletInputStream input;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            var bytes = new ByteArrayInputStream(body);
            input = new ServletInputStream() {
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] buffer, int offset, int length) {
                    return bytes.read(buffer, offset, length);
                }
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("API body replay supports synchronous reads");
                }
            };
        }

        @Override
        public ServletInputStream getInputStream() {
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(input,
                    encoding != null ? encoding : StandardCharsets.UTF_8.name()));
        }
    }
}
