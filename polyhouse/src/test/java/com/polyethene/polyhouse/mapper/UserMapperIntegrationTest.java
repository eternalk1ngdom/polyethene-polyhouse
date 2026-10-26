package com.polyethene.polyhouse.mapper;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显式启用的真实 MySQL 验证；只写随机测试账号，所有写入由测试事务回滚，不执行建表或清库。
 * 普通测试关闭此类，因此不要求开发机器的数据库始终在线。
 */
@EnabledIfSystemProperty(named = "polyhouse.mysql.integration", matches = "true")
@SpringBootTest
@Transactional
class UserMapperIntegrationTest {

    @Autowired
    private UserMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldMatchBlueprintTablesUserColumnsAndUniqueIndex() {
        List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", String.class);
        assertThat(tables).contains("sys_user", "tool_replace_template", "tool_targetkb_preset", "tool_user_preset");
        List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' ORDER BY ORDINAL_POSITION", String.class);
        assertThat(columns).containsExactly("id", "username", "password_hash", "nickname", "avatar", "role",
                "status", "last_login_ip", "last_login_time", "ext_json", "deleted", "create_time", "update_time");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' "
                + "AND INDEX_NAME = 'uk_username' AND NON_UNIQUE = 0 ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("username");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'ext_json' AND DATA_TYPE = 'json' "
                + "AND TABLE_NAME IN ('sys_user','tool_replace_template','tool_targetkb_preset','tool_user_preset')",
                Integer.class)).isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(UserRoleEnum.class)
    void shouldRoundTripEachSemanticRoleAndNestedJson(UserRoleEnum role) {
        User user = newUser();
        user.setRole(role);
        user.setLastLoginIp("192.0.2.1");
        user.setLastLoginTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        user.setExtJson(Map.of("theme", "深色", "preferences", Map.of("enabled", true), "tools", List.of("妙替", "妙压")));
        assertThat(mapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).isPositive();
        User loaded = mapper.selectById(user.getId());
        assertThat(loaded.getRole()).isEqualTo(role);
        assertThat(loaded.getExtJson()).isEqualTo(user.getExtJson());
        assertThat(loaded.getLastLoginTime()).isEqualTo(user.getLastLoginTime());
        assertThat(loaded.getLastLoginIp()).isEqualTo(user.getLastLoginIp());
        assertThat(jdbc.queryForObject("SELECT role FROM sys_user WHERE id = ?", String.class, user.getId()))
                .isEqualTo(role.getCode());
    }

    @Test
    void shouldUseDatabaseDefaultsAndKeepAuditTimesDatabaseOwned() {
        User user = newUser();
        LocalDateTime staleTime = LocalDateTime.of(2000, 1, 1, 0, 0);
        user.setCreateTime(staleTime);
        user.setUpdateTime(staleTime);
        assertThat(mapper.insert(user)).isEqualTo(1);
        User loaded = mapper.selectById(user.getId());
        assertThat(loaded.getRole()).isEqualTo(UserRoleEnum.USER);
        assertThat(loaded.getStatus()).isEqualTo(1);
        assertThat(loaded.getDeleted()).isZero();
        assertThat(loaded.getExtJson()).isNull();
        assertThat(loaded.getLastLoginTime()).isNull();
        assertThat(loaded.getAvatar()).isNotBlank();
        assertThat(loaded.getCreateTime()).isAfter(staleTime);
        LocalDateTime created = loaded.getCreateTime();
        loaded.setNickname("事务内修改昵称");
        loaded.setCreateTime(staleTime);
        loaded.setUpdateTime(staleTime);
        assertThat(mapper.updateById(loaded)).isEqualTo(1);
        User updated = mapper.selectById(user.getId());
        assertThat(updated.getNickname()).isEqualTo("事务内修改昵称");
        assertThat(updated.getCreateTime()).isEqualTo(created);
        assertThat(updated.getUpdateTime()).isAfterOrEqualTo(created);
    }

    @Test
    void shouldEnforceUniqueAccountAndHideLogicallyDeletedUsers() {
        User user = newUser();
        assertThat(mapper.insert(user)).isEqualTo(1);
        User duplicate = newUser();
        duplicate.setUsername(user.getUsername());
        assertThatThrownBy(() -> mapper.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);

        // deleteById 应产生软删除；事务结束时整个测试回滚，不留下永久删除标记。
        assertThat(mapper.deleteById(user.getId())).isEqualTo(1);
        assertThat(mapper.selectById(user.getId())).isNull();
        assertThat(mapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername()))).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted FROM sys_user WHERE id = ?", Integer.class, user.getId())).isEqualTo(1);
    }

    private User newUser() {
        User user = new User();
        user.setUsername("it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        user.setPasswordHash(BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt(10)));
        user.setNickname("事务回滚验证");
        return user;
    }
}
