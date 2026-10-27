package com.polyethene.polyhouse.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

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
                new String[]{"--user-id=1", "--username=owner_account", "--spring.datasource.url=external"})
                .map(args -> Arguments.of((Object) args));
    }
}
