package com.loopers.config;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * 상품 캐시 전용 Redis 연결. 공통 모듈은 batch · streamer 도 써서 바꾸지 않고 여기서만 따로 둠 (4주차 cache-design 4.2).
 * 장애 때 조회가 막히지 않도록 명령 대기 한도를 짧게 두고, 끊긴 동안의 명령은 바로 실패시킴
 */
@Configuration
@EnableScheduling
public class ProductCacheRedisConfig {

    /** 복제 지연으로 옛 버전을 읽지 않도록 읽기 · 쓰기 모두 master (cache-design 4.1) */
    public static final String MASTER_TEMPLATE = "productCacheMasterRedisTemplate";

    private static final String MASTER_CONNECTION = "productCacheMasterRedisConnection";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(100);

    @Qualifier(MASTER_CONNECTION)
    @Bean
    public LettuceConnectionFactory productCacheMasterRedisConnection(RedisProperties properties) {
        return connectionFactory(properties);
    }

    @Qualifier(MASTER_TEMPLATE)
    @Bean
    public StringRedisTemplate productCacheMasterRedisTemplate(@Qualifier(MASTER_CONNECTION) LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private static LettuceConnectionFactory connectionFactory(RedisProperties properties) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.MASTER)
            .commandTimeout(COMMAND_TIMEOUT)
            .clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build())
            .build();
        RedisNodeInfo master = properties.master();
        RedisStaticMasterReplicaConfiguration serverConfig = new RedisStaticMasterReplicaConfiguration(master.host(), master.port());
        serverConfig.setDatabase(properties.database());
        properties.replicas().forEach(replica -> serverConfig.addNode(replica.host(), replica.port()));
        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }
}
