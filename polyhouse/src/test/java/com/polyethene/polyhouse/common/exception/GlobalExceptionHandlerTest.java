package com.polyethene.polyhouse.common.exception;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.result.Result;
import com.polyethene.polyhouse.model.dto.ValidationProbeDTO;
import com.polyethene.polyhouse.model.vo.ValidationProbeVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String PRIVATE_DETAIL = "private-fixture-value-never-log";

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "FORBIDDEN", "CONFLICT"})
    void shouldUseBusinessHttpStatus(ErrorCode errorCode) throws Exception {
        mvc.perform(get("/__test/common/business").param("label", errorCode.name()).param("count", "1"))
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void shouldSerializeSuccessfulVoAndRole() throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"valid\",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void shouldRejectInvalidBodyWithoutEchoingInput(CapturedOutput output) throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"" + PRIVATE_DETAIL + "\",\"count\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_PARAMETER.getMessage()))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertThat(output.getAll()).doesNotContain(PRIVATE_DETAIL);
    }

    @Test
    void shouldHandleActualMvcMethodValidation() throws Exception {
        mvc.perform(post("/__test/common/method-validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"valid\",\"count\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.getCode()))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));
    }

    @Test
    void shouldTreatReturnValueValidationAsServerFailure() throws Exception {
        mvc.perform(get("/__test/common/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.getCode()));
    }

    @Test
    void shouldRejectInvalidFormBinding() throws Exception {
        mvc.perform(get("/__test/common/form").param("label", "valid").param("count", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.getCode()));
    }

    @Test
    void shouldRejectMalformedJsonWithoutEchoingInput() throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.APPLICATION_JSON).content(PRIVATE_DETAIL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_PARAMETER.getMessage()));
    }

    @Test
    void shouldRejectMissingRequestBody() throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.getCode()));
    }

    @Test
    void shouldPreserveNotAcceptableWithJsonErrorEnvelope() throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_PLAIN).content("{\"label\":\"valid\",\"count\":1}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_ACCEPTABLE.getCode()));
    }

    @Test
    void shouldPreserveMethodNotAllowedAndAllowHeader() throws Exception {
        mvc.perform(put("/__test/common/body").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
    }

    @Test
    void shouldPreserveUnsupportedMediaType() throws Exception {
        mvc.perform(post("/__test/common/body").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()));
    }

    @Test
    void shouldPreserveResourceNotFound() throws Exception {
        mvc.perform(get("/__test/common/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void shouldKeepFrameworkStatusWithoutExposingReason(CapturedOutput output) throws Exception {
        mvc.perform(get("/__test/common/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SERVICE_UNAVAILABLE.getMessage()));
        assertThat(output.getAll()).doesNotContain(PRIVATE_DETAIL);
    }

    @Test
    void shouldLogLocationWithoutExceptionMessageOrCause(CapturedOutput output) throws Exception {
        mvc.perform(get("/__test/common/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
        assertThat(output.getAll()).contains("type=java.lang.IllegalStateException", "location=",
                        "ProbeController.unexpected(")
                .doesNotContain(PRIVATE_DETAIL, "private-cause-value");
    }

    /** 仅存在于测试源码，覆盖真实 MVC 分发而不增加生产接口。 */
    @RestController
    @RequestMapping("/__test/common")
    public static class ProbeController {

        /** 使用 DTO 标签选择待验证的业务错误类型。 */
        @GetMapping("/business")
        public Result<ValidationProbeVO> business(@Valid @ModelAttribute ValidationProbeDTO input) {
            throw new BusinessException(ErrorCode.valueOf(input.label()));
        }

        /** 验证请求体校验和成功响应序列化。 */
        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        public Result<ValidationProbeVO> body(@Valid @RequestBody ValidationProbeDTO input) {
            return Result.success(new ValidationProbeVO(UserRoleEnum.USER));
        }

        /** 参数级约束会触发 Spring 6.1 的方法校验分支。 */
        @PostMapping("/method-validation")
        public Result<ValidationProbeVO> methodValidation(@NotNull @Valid @RequestBody ValidationProbeDTO input) {
            return Result.success(new ValidationProbeVO(UserRoleEnum.USER));
        }

        /** 模拟服务端返回值违反约束，不能误报为客户端参数错误。 */
        @NotNull
        @GetMapping("/invalid-return")
        public Result<ValidationProbeVO> invalidReturn() {
            return null;
        }

        /** 验证表单构造绑定和类型转换错误。 */
        @GetMapping("/form")
        public Result<ValidationProbeVO> form(@Valid @ModelAttribute ValidationProbeDTO input) {
            return Result.success(new ValidationProbeVO(UserRoleEnum.USER));
        }

        /** 模拟带私有原因的框架异常，只允许保留 HTTP 状态。 */
        @GetMapping("/unavailable")
        public Result<ValidationProbeVO> unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, PRIVATE_DETAIL);
        }

        /** 模拟异常及异常链携带私有值，验证响应和日志均不回显。 */
        @GetMapping("/unexpected")
        public Result<ValidationProbeVO> unexpected() {
            throw new IllegalStateException(PRIVATE_DETAIL, new RuntimeException("private-cause-value"));
        }
    }
}
