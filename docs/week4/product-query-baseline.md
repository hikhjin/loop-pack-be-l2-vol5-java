# 상품 목록 조회 기준선 (캐시 · 인덱스 변경 전)

인덱스 · 쿼리 · 캐시를 바꾸기 전의 측정 결과다. 이후 변경은 같은 조건으로 다시 재서 이 표와 비교한다.

- 실험 코드: `apps/commerce-api/src/test/java/com/loopers/experiment/ProductQueryExperiment.java`
- 실행: `RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductQueryExperiment'` (평소 `check` 에서는 건너뜀)
- 원본 출력: `apps/commerce-api/build/experiment/product-query.md` (SQL 원문, EXPLAIN 표, EXPLAIN ANALYZE 트리)
- 실행일: 2026-10-15, 로컬 Docker의 MySQL 8.0.46 Testcontainers, **한 번 실행한 결과**

## 1. 조건

| 항목 | 값 |
|---|---|
| 데이터 | `product-query-seed.md` 의 seed. 실험 시작 시 `ANALYZE TABLE` 로 통계를 갱신 |
| 요청 | `GET /api/v1/products?brandId=7&sort={latest, price_asc, likes_desc}&page=1&size=20` (기존 계약 그대로) |
| 결과 확인 | 응답의 id · 가격 · 좋아요 수 · 품절 여부와 페이지 정보를 seed 규칙으로 따로 계산한 기대 결과와 비교. 세 정렬 모두 일치 |
| SQL 수 · 원문 | Hibernate `StatementInspector` 로 요청 한 번에 준비된 SQL 을 모음. 실행 계획에는 이 원문에 같은 인자를 넣어 실행 |
| 지연 | MockMvc 로 한 스레드에서 차례로 요청. 준비 50회 뒤 200회 측정. 네트워크 · 톰캣은 포함하지 않음 |
| 그 밖 | 테스트 커넥션 풀 10, SQL 출력(show-sql)은 측정에 섞이지 않도록 끔 |

## 2. 결과

| 정렬 | 요청당 SQL | 평균(ms) | p50(ms) | p95(ms) | p99(ms) | 최대(ms) | 목록 SQL actual time(ms) | 건수 SQL actual time(ms) |
|---|---|---|---|---|---|---|---|---|
| latest | 2 | 29.99 | 28.25 | 35.83 | 58.11 | 126.66 | 25.8 | 0.06 |
| price_asc | 2 | 31.13 | 29.34 | 43.71 | 66.19 | 69.54 | 23.9 | 0.12 |
| likes_desc | 2 | 29.61 | 29.32 | 35.12 | 44.95 | 51.70 | 22.9 | 0.09 |

세 정렬의 실행 계획은 정렬 단계만 다르고 나머지는 같다.

```
-> Limit: 20 row(s)                                              (actual time=23.9 rows=20)
    -> Sort: p1_0.price, p1_0.id DESC, limit input to 20 row(s)  (actual time=23.9 rows=20)
        -> Table scan on <temporary>                             (actual time=23.8 rows=94)
            -> Aggregate using temporary table                   (actual time=23.8 rows=94)
                -> Left hash join (l1_0.product_id = p1_0.id)    (actual time=17.6..23.4 rows=959)
                    -> Filter: (p1_0.deleted_at is null)         (rows=94)
                        -> Index lookup on p1_0 using FK(brand_id=7)  (rows=100)
                    -> Hash
                        -> Covering index scan on l1_0 using uk_likes_user_product  (actual time=0.229..9.29 rows=100163)
```
(price_asc, 시간 표기는 줄임)

## 3. 관찰

1. **요청당 SQL은 2개이며 N+1은 없다.** 목록(상품 + 브랜드 + 좋아요 집계를 한 문장으로)과 건수다. 2주차에 집계를 한 쿼리로 묶어 두었기 때문이다.
2. **목록 SQL 시간의 대부분은 좋아요 쪽이다.** 브랜드 7의 상품은 FK 인덱스로 100행만 읽는다(0.1ms 안팎). 반면 좋아요는 실제로 필요한 행이 959개인데 **`likes` 전체 100,163행**을 유니크 키 `(user_id, product_id)` 로 전부 훑어 해시 테이블을 만든다. 유니크 키의 첫 열이 `user_id` 라서 `product_id` 로 찾는 조인에 쓰이지 못한다.
3. **정렬 비용은 작다.** 집계 후 남는 행이 94개라 `Using temporary; Using filesort` 가 있어도 정렬 단계의 시간은 측정되지 않을 만큼 작다. 상품 쪽 `(brand_id, price)` 같은 정렬용 인덱스는 이 데이터에서는 줄일 시간이 거의 없을 것으로 보이며, 바꾼 뒤 측정으로 확인해야 한다.
4. **브랜드 조건은 `brand.id = ?` 로 나가지만 상품의 FK 인덱스를 쓴다.** Querydsl이 `PRODUCT.brand.id` 를 조인한 brand 쪽 식별자로 바꾸지만, 옵티마이저가 조인 조건으로 상수를 넘겨 `product.brand_id = 7` 로 탐색했다.
5. **세 정렬의 지연이 거의 같다.** 비용이 정렬이 아니라 공통 단계(좋아요 전체 스캔과 집계)에 있다는 2번과 맞는다.
6. **HTTP 지연(p50 약 29ms)의 대부분이 목록 SQL(약 24ms)이다.** 건수 SQL은 0.1ms 안팎이다.

## 4. 한계

- 한 번 실행한 결과이며, 동시 요청이 없는 상태의 지연이다.
- 노트북 Docker 위의 MySQL이라 절대값보다 변경 전후의 상대 비교에 쓴다.
- 측정 대상은 브랜드 7(살아 있는 상품 94개)이다. 브랜드 필터 없는 전체 목록이나 깊은 페이지는 재지 않았다. 이런 경우에는 상품 쪽 정렬 비용이 커질 수 있다.
- `likes` 를 전부 훑는 비용은 브랜드 크기와 무관하게 좋아요 총량에 비례하므로, 데이터가 늘수록 커질 것으로 예상한다(미측정).
