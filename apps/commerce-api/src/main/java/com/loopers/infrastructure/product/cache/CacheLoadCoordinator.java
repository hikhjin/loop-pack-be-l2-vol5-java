package com.loopers.infrastructure.product.cache;

import com.loopers.config.ProductCacheRedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 캐시 miss 뒤 원본 조회를 데이터 키마다 한 번으로 모음 (4주차 stampede-design).
 * 앱 안에서는 진행 중인 요청을 기다리고, 서버 사이에서는 Redis 락을 잡은 한 곳만 원본을 읽음.
 * 기다리다 실패하거나 한도를 넘으면 각자 원본을 읽어, 캐시 쪽 문제로 조회가 실패하지 않게 함 (1, 3)
 */
@Slf4j
@Component
public class CacheLoadCoordinator {

    /** 락 만료 뒤 다른 서버가 새로 잡은 락을 지우지 않도록 내 토큰일 때만 지움 (3) */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
        Long.class);

    private final StringRedisTemplate master;
    private final Duration lockTtl;
    private final Duration waitLimit;
    private final Duration pollInterval;

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public CacheLoadCoordinator(
        @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
        @Value("${product-cache.load-lock.ttl-ms:3000}") long lockTtlMillis,
        @Value("${product-cache.load-lock.wait-ms:3000}") long waitLimitMillis,
        @Value("${product-cache.load-lock.poll-ms:10}") long pollIntervalMillis
    ) {
        this.master = master;
        this.lockTtl = Duration.ofMillis(lockTtlMillis);
        this.waitLimit = Duration.ofMillis(waitLimitMillis);
        this.pollInterval = Duration.ofMillis(pollIntervalMillis);
    }

    /**
     * @param dataKey 버전이 들어간 키라 락도 버전마다 따로임
     * @param store   저장 실패는 store 가 삼켜야 함
     */
    @SuppressWarnings("unchecked")
    public <T> T load(String dataKey, Supplier<Optional<T>> readStored, Supplier<T> load, Consumer<T> store) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(dataKey, mine);
        if (running != null) {
            return (T) awaitInFlight(running, load);
        }
        try {
            T value = loadOnceAcrossInstances(dataKey, readStored, load, store);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(dataKey, mine);
        }
    }

    /** 첫 요청이 실패했거나 한도를 넘으면 각자 원본을 읽고 저장하지 않음 (3) */
    private Object awaitInFlight(CompletableFuture<Object> running, Supplier<?> load) {
        try {
            return running.get(waitLimit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return load.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return load.get();
        }
    }

    private <T> T loadOnceAcrossInstances(String dataKey, Supplier<Optional<T>> readStored, Supplier<T> load, Consumer<T> store) {
        String lockKey = lockKey(dataKey);
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(master.opsForValue().setIfAbsent(lockKey, token, lockTtl));
        } catch (RuntimeException e) {
            log.warn("원본 조회 락을 잡지 못해 락 없이 원본을 읽음. key={}", lockKey, e);
            return loadAndStore(load, store);
        }
        if (locked) {
            try {
                return loadAndStore(load, store);
            } finally {
                release(lockKey, token);
            }
        }
        return awaitOtherInstance(readStored).orElseGet(() -> loadAndStore(load, store));
    }

    /** 한도를 넘거나 읽기에 실패하면 비어 있음 */
    private <T> Optional<T> awaitOtherInstance(Supplier<Optional<T>> readStored) {
        long deadline = System.nanoTime() + waitLimit.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            try {
                Optional<T> stored = readStored.get();
                if (stored.isPresent()) {
                    return stored;
                }
            } catch (RuntimeException e) {
                log.warn("다른 서버의 저장을 기다리다 캐시를 읽지 못해 원본을 읽음", e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static <T> T loadAndStore(Supplier<T> load, Consumer<T> store) {
        T value = load.get();
        store.accept(value);
        return value;
    }

    // 해제 실패는 락 만료가 정리함
    private void release(String lockKey, String token) {
        try {
            master.execute(RELEASE, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("원본 조회 락을 풀지 못함. 만료로 정리됨. key={}", lockKey, e);
        }
    }

    static String lockKey(String dataKey) {
        return "lock:" + dataKey;
    }
}
