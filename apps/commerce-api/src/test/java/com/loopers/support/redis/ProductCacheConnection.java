package com.loopers.support.redis;

import com.loopers.config.ProductCacheRedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 첫 연결이 명령 한도(100ms)를 넘겨 원본으로 우회하면 원본 호출 수가 어긋나므로 테스트 전에 연결을 맺어 둠 */
@Component
public class ProductCacheConnection {

    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_WAIT_MILLIS = 100;

    private final StringRedisTemplate redis;

    public ProductCacheConnection(@Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void connect() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                redis.execute((RedisCallback<String>) RedisConnection::ping);
                return;
            } catch (RuntimeException e) {
                last = e;
                sleep();
            }
        }
        throw new IllegalStateException("상품 캐시 Redis 에 연결하지 못함", last);
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
