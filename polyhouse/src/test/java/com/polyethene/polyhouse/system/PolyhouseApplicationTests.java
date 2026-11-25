package com.polyethene.polyhouse.system;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class PolyhouseApplicationTests {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    /** 验证完整 Spring 上下文正常装配，且 MyBatis 显式禁用了 SQL 及绑定参数日志，防止凭据与令牌入日志。 */
    @Test
    void shouldDisableSqlAndBoundParameterLogging() {
        assertEquals(NoLoggingImpl.class, sqlSessionFactory.getConfiguration().getLogImpl());
    }

}
