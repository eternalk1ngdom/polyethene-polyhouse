package com.polyethene.polyhouse;

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

    @Test
    void contextLoads() {
    }

    @Test
    void shouldDisableSqlAndBoundParameterLogging() {
        assertEquals(NoLoggingImpl.class, sqlSessionFactory.getConfiguration().getLogImpl());
    }

}
