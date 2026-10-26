package com.polyethene.polyhouse.common.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 验证累计门槛、边界和 365 天目标，不通过线性公式替代已经确定的非线性曲线。 */
class UserGrowthRulesTest {

    @ParameterizedTest
    @CsvSource({"0,1", "29,1", "30,2", "199,2", "200,3", "579,3", "580,4",
            "1369,4", "1370,5", "3649,5", "3650,6", "3660,6", "9223372036854775807,6"})
    void shouldUseCumulativeThresholdsAndKeepMaximumLevel(long experience, int level) {
        assertThat(UserGrowthRules.levelForExperience(experience)).isEqualTo(level);
    }

    @Test
    void shouldScaleBilibiliCurveTo365RewardedDays() {
        long[] original = {0, 200, 1500, 4500, 10800, 28800};
        for (int level = 1; level <= UserGrowthRules.MAX_LEVEL; level++) {
            long days = (original[level - 1] * 365 + 28800 - 1) / 28800;
            assertThat(UserGrowthRules.experienceForLevel(level)).isEqualTo(days * UserGrowthRules.DAILY_LOGIN_EXPERIENCE);
        }
        assertThat(UserGrowthRules.levelForExperience(364L * UserGrowthRules.DAILY_LOGIN_EXPERIENCE)).isEqualTo(5);
        assertThat(UserGrowthRules.levelForExperience(365L * UserGrowthRules.DAILY_LOGIN_EXPERIENCE)).isEqualTo(6);
    }

    @Test
    void shouldRejectInvalidGrowthInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> UserGrowthRules.levelForExperience(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> UserGrowthRules.experienceForLevel(0));
        assertThatIllegalArgumentException().isThrownBy(() -> UserGrowthRules.experienceForLevel(7));
    }
}
