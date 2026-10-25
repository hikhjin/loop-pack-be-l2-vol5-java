package com.loopers.infrastructure.product.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 버전 키를 읽고 그 버전의 데이터 키를 같은 호출 안에서 읽는 스크립트 (4주차 docs/week4/detail-cache-design.md 3.1, 4).
 * hit 의 Redis 왕복을 2회(버전 → 데이터)에서 1회로 줄인다. 데이터 키는 "접두사 + 버전 + 접미사"로 스크립트 안에서 만들므로,
 * 버전 뒤에 정렬이 붙는 목록 키(:sort=…)와 상세 키를 같은 스크립트로 읽는다 (cache-design.md 4.1).
 * <ul>
 *   <li>버전 키가 없으면 넘겨받은 현재 시각(ms)으로 만든다. 0 · 1 로 다시 세면 예전 데이터 키와 번호가 겹친다 (cache-design.md 3.5)</li>
 *   <li>버전과 데이터를 모두 master 에서 읽는다. 버전을 replica 에서 읽으면 복제 지연 동안 옛 버전을 읽는다 (cache-design.md 4.1)</li>
 *   <li>스크립트가 쓰는 데이터 키를 KEYS 로 넘기지 않으므로 단일 노드 · master-replica 구성에서만 쓴다.
 *       Redis Cluster 로 옮기면 두 키를 같은 슬롯에 두는 해시 태그가 필요하다 (detail-cache-design.md 9)</li>
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

    /** 읽은 버전과 그 버전의 데이터. 데이터가 없으면(miss) null */
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
