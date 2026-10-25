package com.loopers.infrastructure.product.cache;

import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.support.redis.ProductCacheConnection;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 4주차 detail-cache-design 3.1 */
@SpringBootTest
class VersionedCacheScriptTest {

    private static final String VERSION_KEY = "test:version";
    private static final String PREFIX = "test:data:ver=";
    private static final long NOW = 1_760_000_000_000L;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private ProductCacheConnection productCacheConnection;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeEach
    void connectCache() {
        productCacheConnection.connect();
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("버전 키가 없으면, 넘겨받은 현재 시각으로 만들고 그 버전과 빈 데이터를 돌려준다.")
    @Test
    void createsVersionFromNow_andReturnsNoData_whenVersionIsMissing() {
        // act
        VersionedCacheScript.Result result = VersionedCacheScript.read(redis, VERSION_KEY, PREFIX, "", NOW);

        // assert
        assertAll(
            () -> assertThat(result.version()).isEqualTo(NOW),
            () -> assertThat(result.data()).isNull(),
            () -> assertThat(redis.opsForValue().get(VERSION_KEY)).isEqualTo(String.valueOf(NOW))
        );
    }

    @DisplayName("버전 키가 있으면, 그 버전을 그대로 쓰고 현재 시각으로 덮어쓰지 않는다.")
    @Test
    void keepsExistingVersion() {
        // arrange
        redis.opsForValue().set(VERSION_KEY, "42");

        // act
        VersionedCacheScript.Result result = VersionedCacheScript.read(redis, VERSION_KEY, PREFIX, "", NOW);

        // assert
        assertAll(
            () -> assertThat(result.version()).isEqualTo(42L),
            () -> assertThat(redis.opsForValue().get(VERSION_KEY)).isEqualTo("42")
        );
    }

    @DisplayName("그 버전의 데이터 키(접두사 + 버전 + 접미사)가 있으면, 데이터를 함께 돌려준다. 다른 버전 · 접미사의 키는 읽지 않는다.")
    @Test
    void returnsDataOfThatVersionAndSuffix_only() {
        // arrange
        redis.opsForValue().set(VERSION_KEY, "42");
        redis.opsForValue().set(PREFIX + "42:sort=LATEST", "최신순");
        redis.opsForValue().set(PREFIX + "42:sort=PRICE_ASC", "가격순");
        redis.opsForValue().set(PREFIX + "41:sort=LATEST", "옛 버전");

        // act
        VersionedCacheScript.Result result = VersionedCacheScript.read(redis, VERSION_KEY, PREFIX, ":sort=LATEST", NOW);

        // assert
        assertAll(
            () -> assertThat(result.version()).isEqualTo(42L),
            () -> assertThat(result.data()).isEqualTo("최신순")
        );
    }
}
