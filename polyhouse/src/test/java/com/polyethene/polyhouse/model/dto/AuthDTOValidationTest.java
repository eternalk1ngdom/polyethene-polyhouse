package com.polyethene.polyhouse.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证真正的输入边界及日志脱敏，不依赖尚未实现的注册登录接口。 */
class AuthDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void prepareValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void shouldAcceptBoundaryLengthsAndChineseNickname() {
        assertThat(validator.validate(new RegisterDTO("u_01", "a".repeat(8), "聚妙屋居民"))).isEmpty();
        assertThat(validator.validate(new RegisterDTO("u".repeat(32), "a".repeat(72), "名".repeat(32)))).isEmpty();
        assertThat(validator.validate(new LoginDTO("u_01", "密".repeat(24)))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "contains space", "居民账号", "name-01", "line\nbreak"})
    void shouldRejectInvalidAccountsAtBothBoundaries(String username) {
        assertThat(validator.validate(new RegisterDTO(username, "a".repeat(8), "居民"))).isNotEmpty();
        assertThat(validator.validate(new LoginDTO(username, "a".repeat(8)))).isNotEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"        ", "short"})
    void shouldRejectMissingOrShortPasswords(String password) {
        assertThat(validator.validate(new RegisterDTO("u_01", password, "居民"))).isNotEmpty();
        assertThat(validator.validate(new LoginDTO("u_01", password))).isNotEmpty();
    }

    @Test
    void shouldRejectOverlongFieldsAndMultibytePasswords() {
        assertThat(validator.validate(new RegisterDTO("u".repeat(33), "a".repeat(8), "居民"))).isNotEmpty();
        assertThat(validator.validate(new LoginDTO("u".repeat(33), "a".repeat(8)))).isNotEmpty();
        assertThat(validator.validate(new RegisterDTO("u_01", "a".repeat(73), "居民"))).isNotEmpty();
        assertThat(validator.validate(new LoginDTO("u_01", "a".repeat(73)))).isNotEmpty();
        // 25 个汉字只有 25 个字符，却占 75 个 UTF-8 字节，不能交给 BCrypt 静默截断。
        assertThat(validator.validate(new RegisterDTO("u_01", "密".repeat(25), "居民")))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("passwordWithinBcryptLimit"));
        assertThat(validator.validate(new LoginDTO("u_01", "密".repeat(25)))).isNotEmpty();
        assertThat(validator.validate(new RegisterDTO("u_01", "a".repeat(8), "名".repeat(33)))).isNotEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRequireDisplayNickname(String nickname) {
        assertThat(validator.validate(new RegisterDTO("u_01", "a".repeat(8), nickname))).isNotEmpty();
    }

    @Test
    void shouldDeserializeCredentialsWithoutRewritingThemOrAcceptingAdminFields() throws Exception {
        String password = " input-kept-verbatim ";
        RegisterDTO register = objectMapper.readValue(objectMapper.writeValueAsString(
                java.util.Map.of("username", "u_01", "password", password, "nickname", "居民")), RegisterDTO.class);
        LoginDTO login = objectMapper.readValue(objectMapper.writeValueAsString(
                java.util.Map.of("username", "u_01", "password", password)), LoginDTO.class);
        assertThat(register.password()).isEqualTo(password);
        assertThat(login.password()).isEqualTo(password);
        assertThat(validator.validate(register)).isEmpty();
        assertThat(validator.validate(login)).isEmpty();
        assertThat(RegisterDTO.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("username", "password", "nickname");
    }

    @Test
    void shouldAllowOnlyKnownClientTypesAndKeepOmittedClientCompatible() {
        assertThat(validator.validate(new LoginDTO("u_01", "a".repeat(8)))).isEmpty();
        for (String client : new String[]{"PC", "H5", "PLUGIN"}) {
            assertThat(validator.validate(new LoginDTO("u_01", "a".repeat(8), client))).isEmpty();
        }
        for (String client : new String[]{"", " ", "pc", "MOBILE"}) {
            assertThat(validator.validate(new LoginDTO("u_01", "a".repeat(8), client))).isNotEmpty();
        }
    }

    @Test
    void shouldNeverExposePasswordsInJsonOrDefaultDiagnosticText() throws Exception {
        String password = "sensitive-fixture-only";
        RegisterDTO register = new RegisterDTO("u_01", password, "居民");
        LoginDTO login = new LoginDTO("u_01", password);
        assertThat(register.toString()).doesNotContain(password, "u_01");
        assertThat(login.toString()).doesNotContain(password, "u_01");
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(register)).has("password")).isFalse();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(login)).has("password")).isFalse();
        assertThat(objectMapper.writeValueAsString(register)).doesNotContain("passwordWithinBcryptLimit");
    }
}
