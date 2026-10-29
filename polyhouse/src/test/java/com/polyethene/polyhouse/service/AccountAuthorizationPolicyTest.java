package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.security.AccountAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.polyethene.polyhouse.common.constant.UserRoleEnum.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 独立列出批准矩阵的期望值，不以角色数字或待测方法自身计算期望权限。 */
class AccountAuthorizationPolicyTest {
    @Test
    void shouldKeepAdministrationIndependentFromCostlyFeatureAccess() {
        Map<UserRoleEnum, Set<UserRoleEnum>> bans = Map.of(USER, Set.of(), INSIDER, Set.of(),
                ADMIN, Set.of(USER), OWNER, Set.of(USER, INSIDER, ADMIN));
        for (UserRoleEnum actor : values()) {
            for (UserRoleEnum target : values()) {
                assertThat(AccountAuthorizationPolicy.canBan(actor, target)).isEqualTo(bans.get(actor).contains(target));
                assertThat(AccountAuthorizationPolicy.canCreate(actor, target))
                        .isEqualTo(actor == OWNER && Set.of(USER, ADMIN).contains(target));
                assertThat(AccountAuthorizationPolicy.canRecoverAccount(actor, target))
                        .isEqualTo(actor == OWNER && target != OWNER);
            }
            assertThat(actor.hasPermission(ADMIN)).isEqualTo(Set.of(ADMIN, OWNER).contains(actor));
            assertThat(actor.hasPermission(INSIDER)).isEqualTo(Set.of(INSIDER, OWNER).contains(actor));
            assertThat(AccountAuthorizationPolicy.canReview(actor)).isEqualTo(actor == OWNER);
        }
        assertThat(AccountAuthorizationPolicy.canManageAccounts(INSIDER)).isFalse();
        assertThat(AccountAuthorizationPolicy.canBan(OWNER, null)).isFalse();
        assertThat(AccountAuthorizationPolicy.canManageAccounts(null)).isFalse();
    }
}
