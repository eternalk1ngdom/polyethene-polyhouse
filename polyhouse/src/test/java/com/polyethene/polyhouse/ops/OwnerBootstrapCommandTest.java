package com.polyethene.polyhouse.ops;

import com.polyethene.polyhouse.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不启动数据库就拒绝未知 Spring 参数、重复键和可能被 Shell 重新解释的账号输入。 */
class OwnerBootstrapCommandTest {
    @Test
    void shouldRequireExplicitApplyAndKeepLargeIdsExact() {
        var preview = OwnerBootstrapCommand.parse(new String[]{"--user-id=9223372036854775807", "--username=owner_account"});
        assertThat(preview.userId()).isEqualTo(Long.MAX_VALUE);
        assertThat(preview.apply()).isFalse();
        var apply = OwnerBootstrapCommand.parse(new String[]{"--username=owner_account", "--apply", "--user-id=42"});
        assertThat(apply.apply()).isTrue();
    }

    @Test
    void shouldCreateOnlyWithExplicitApplyAndWithoutExistingUserId() {
        var request = OwnerBootstrapCommand.parse(new String[]{"--create", "--username=root", "--apply"});
        assertThat(request.create()).isTrue();
        assertThat(request.passwordStdin()).isFalse();
        assertThat(OwnerBootstrapCommand.parse(new String[]{"--create", "--username=root", "--apply",
                "--password-stdin"}).passwordStdin()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRejectMissingPasswordBeforeAskingForConfirmation(String input) {
        assertThatThrownBy(() -> OwnerBootstrapCommand.readConfirmedPassword(prompt -> {
            assertThat(prompt).isEqualTo("Owner password: ");
            return input == null ? null : input.toCharArray();
        })).isInstanceOf(BusinessException.class).hasMessageContaining("cannot be empty");
    }

    @Test
    void shouldRejectDifferentConfirmationAndClearBothBuffers() {
        char[] first = "example-password".toCharArray();
        char[] second = "mistyped-password".toCharArray();
        assertThatThrownBy(() -> OwnerBootstrapCommand.readConfirmedPassword(prompt ->
                prompt.equals("Owner password: ") ? first : second))
                .isInstanceOf(BusinessException.class).hasMessageContaining("do not match");
        assertThat(first).containsOnly('\0');
        assertThat(second).containsOnly('\0');
    }

    @Test
    void shouldReturnExactConfirmedPasswordWithoutTrimming() throws Exception {
        String input = " example-password ";
        assertThat(OwnerBootstrapCommand.readConfirmedPassword(prompt -> input.toCharArray())).isEqualTo(input);
    }

    @ParameterizedTest
    @MethodSource("badArguments")
    void shouldRejectMalformedOrUnexpectedArguments(String[] args) {
        assertThatThrownBy(() -> OwnerBootstrapCommand.parse(args)).isInstanceOf(IllegalArgumentException.class);
        assertThat(OwnerBootstrapCommand.execute(args)).isEqualTo(2);
    }

    static Stream<Arguments> badArguments() {
        return Stream.of(new String[]{}, new String[]{"--user-id=1"},
                new String[]{"--user-id=0", "--username=owner_account"},
                new String[]{"--user-id=01", "--username=owner_account"},
                new String[]{"--user-id=9223372036854775808", "--username=owner_account"},
                new String[]{"--user-id=1", "--username=OWNER"},
                new String[]{"--user-id=1", "--username=bad;name"},
                new String[]{"--user-id=1", "--username=owner_account", "--username=another"},
                new String[]{"--user-id=1", "--user-id=2", "--username=owner_account"},
                new String[]{"--user-id=1", "--username=owner_account", "--apply", "--apply"},
                new String[]{"--create", "--username=root"},
                new String[]{"--create", "--username=root", "--apply", "--user-id=1"},
                new String[]{"--user-id=1", "--username=root", "--apply", "--password-stdin"},
                new String[]{"--user-id=1", "--username=owner_account", "--spring.datasource.url=external"})
                .map(args -> Arguments.of((Object) args));
    }
}
