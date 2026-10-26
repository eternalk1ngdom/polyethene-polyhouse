package com.polyethene.polyhouse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 为奖励业务提供服务端时钟，测试可以模拟跨日而不修改系统时间。 */
@Configuration
public class UserGrowthConfig {

    /** 生产读取真实时间，自然日归属由成长规则统一转换到上海时区。 */
    @Bean
    public Clock userGrowthClock() {
        return Clock.systemUTC();
    }
}
