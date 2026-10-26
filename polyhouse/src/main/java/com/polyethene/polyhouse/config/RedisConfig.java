package com.polyethene.polyhouse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 统一 Redis 文本存储约定，连接工厂及 Lettuce 生命周期由 Spring Boot 根据配置自动管理。
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * 为登录白名单等文本数据提供模板，避免默认 JDK 序列化造成键和值不可读。
     *
     * @param connectionFactory Spring Boot 自动配置的连接工厂
     * @return 使用 UTF-8 字符串序列化键、值及 Hash 字段的模板
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.string());
        // 禁止未显式指定的序列化行为回退到 Java 对象反序列化。
        template.setEnableDefaultSerializer(false);
        return template;
    }
}
