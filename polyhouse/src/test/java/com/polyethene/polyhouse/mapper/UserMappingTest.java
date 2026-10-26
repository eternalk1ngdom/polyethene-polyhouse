package com.polyethene.polyhouse.mapper;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.entity.User;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 离线检查实际生成的 MyBatis 语句及类型处理器，不要求本机 MySQL 在线。 */
@SpringBootTest
class UserMappingTest {

    @Autowired
    private SqlSessionFactory factory;

    @Autowired
    private UserMapper mapper;

    @Test
    void shouldRegisterMapperAndFilterDeletedAccountsInGeneratedSelect() {
        assertThat(mapper).isNotNull();
        BoundSql sql = factory.getConfiguration().getMappedStatement(UserMapper.class.getName() + ".selectById")
                .getBoundSql(Map.of("id", 1L));
        assertThat(sql.getSql()).contains("sys_user", "deleted=0").doesNotContain("${");
        assertThat(sql.getParameterMappings()).hasSize(1);
    }

    @Test
    void shouldMapJsonAndSemanticRolesInActualResultMap() {
        TableInfo table = TableInfoHelper.getTableInfo(User.class);
        assertThat(table.getTableName()).isEqualTo("sys_user");
        assertThat(table.getLogicDeleteFieldInfo().getColumn()).isEqualTo("deleted");
        assertThat(table.getIdType().name()).isEqualTo("ASSIGN_ID");
        var mappings = factory.getConfiguration().getResultMap(table.getResultMap()).getResultMappings();
        ResultMapping json = mappings.stream().filter(mapping -> mapping.getProperty().equals("extJson"))
                .findFirst().orElseThrow();
        assertThat(json.getTypeHandler()).isInstanceOf(JacksonTypeHandler.class);
        ResultMapping role = mappings.stream().filter(mapping -> mapping.getProperty().equals("role"))
                .findFirst().orElseThrow();
        assertThat(role.getJavaType()).isEqualTo(UserRoleEnum.class);
    }

    @Test
    void shouldBindJsonAndKeepDatabaseOwnedAuditColumnsOutOfWrites() {
        User user = new User();
        user.setUsername("mapping_user");
        user.setPasswordHash("mapping-only-hash");
        user.setNickname("居民");
        user.setRole(UserRoleEnum.USER);
        user.setExtJson(Map.of("theme", "深色"));
        user.setCreateTime(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        user.setUpdateTime(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        BoundSql insert = factory.getConfiguration().getMappedStatement(UserMapper.class.getName() + ".insert")
                .getBoundSql(user);
        BoundSql update = factory.getConfiguration().getMappedStatement(UserMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", user));
        assertThat(insert.getSql()).doesNotContain("create_time", "update_time", "深色", "mapping-only-hash");
        assertThat(update.getSql()).doesNotContain("create_time", "update_time", "深色", "mapping-only-hash");
        assertThat(insert.getParameterMappings()).anyMatch(mapping -> mapping.getProperty().equals("extJson")
                && mapping.getTypeHandler() instanceof JacksonTypeHandler);
    }
}
