package com.loopers.infrastructure.product.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * hit 의 Redis 왕복을 1회로 줄이려 버전과 그 버전의 데이터를 한 스크립트로 읽음 (4주차 detail-cache-design 3.1).
 * <ul>
 *   <li>버전 키가 없으면 현재 시각(ms)으로 만듦. 0부터 다시 세면 예전 데이터 키와 겹침 (cache-design 3.5)</li>
 *   <li>데이터 키를 KEYS 로 넘기지 않아 Redis Cluster 에서는 해시 태그가 필요함 (detail-cache-design 9)</li>
 * </ul>
 */
final class VersionedCacheScript {

    /** KEYS[1] = 버전 키, ARGV = [데이터 키 접두사, 데이터 키 접미사, 현재 시각(ms)]. 돌려주는 값: {버전, 데이터} (데이터가 없으면 버전만) */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> READ = new DefaultRedisScript<>(
        "local v = redis.call('GET', KEYS[1]) "
            + "if not v then redis.call('SET', KEYS[1], ARGV[3], 'NX') v = redis.call('GET', KEYS[1]) end "
            + "return { v, redis.call('GET', ARGV[1] .. v .. ARGV[2]) }",
        List.class);

    /** miss 면 data 는 null */
    record Result(long version, String data) {}

    private VersionedCacheScript() {
    }

    static Result read(StringRedisTemplate master, String versionKey, String dataKeyPrefix, String dataKeySuffix, long nowMillis) {
        List<?> result = master.execute(READ, List.of(versionKey), dataKeyPrefix, dataKeySuffix, String.valueOf(nowMillis));
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("버전 읽기 스크립트가 버전을 돌려주지 않음. key=" + versionKey);
        }
        String data = result.size() > 1 && result.get(1) != null ? String.valueOf(result.get(1)) : null;
        return new Result(Long.parseLong(String.valueOf(result.get(0))), data);
    }
}
