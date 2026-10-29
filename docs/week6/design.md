# 6주차 설계: 주문 확정을 이벤트로 전달하고 구매 활동을 한 번만 남기기

이 문서는 `docs/week5/design.md` 를 기준으로 **6주차에 바뀌거나 추가된 것만** 적는다. 절 번호와 결정 번호(D-xx)는 이전 문서를 이어 쓴다. 이번 결정은 D-70부터다.

## 1. 범위

**PG로 확정된 주문을 `OrderConfirmed` 이벤트로 Kafka에 발행하고, commerce-streamer가 소비해 상품 구매 활동으로 저장한다.** 같은 이벤트가 다시 전달돼도 구매 활동은 한 번만 남는다. 구매 활동은 9주차 주간 랭킹의 입력이 된다.

```
검증된 PG SUCCESS (또는 0원 주문 확정)
→ 주문 최초 CONFIRMED + 예약 소비 commit
→ after-commit 에서 OrderConfirmed 발행
→ Kafka order-events
→ commerce-streamer
→ 처리 기록 + 상품 구매 활동 저장 (같은 TX)
```

| 대상 | 이번 변경 | 유지할 것 |
|---|---|---|
| 주문 확정 (commerce-api) | 확정 시각 `confirmedAt` 저장, commit 뒤 이벤트 발행 | 결제 · 예약 · 재고 · 포인트 규칙 (5주차 전체) |
| commerce-streamer | 구매 활동 consumer 신설 | demo consumer · 기존 batch factory |
| 공용 Kafka 설정 | 오타 · 주소 정정 (D-91) | 기존 batch factory 값 |
| 로컬 인프라 | topic 생성 단계 추가, broker 자동 topic 생성 끔 | broker 이미지 · 버전 |

- **consumer는 결제 · 재고 차감을 다시 하지 않는다.** 이미 일어난 확정 사실을 기록할 뿐이다.
- **범위 밖:** Outbox(7주차), 랭킹 API(9주차), DLT 재처리, 이전 POINT 확정 주문의 소급 발행, 고객이 이벤트를 발행하는 공개 endpoint.

### 왜 이벤트로 나누는가

주문 확정이 구매 활동 저장을 직접 호출하면, 구매 활동 저장이 느리거나 실패할 때 이미 성공한 결제를 어떻게 할지 주문 서비스가 판단해야 한다. `OrderConfirmed` 는 "주문이 확정됐다"는 과거 사실이라, 후속 소비가 실패해도 확정을 취소하지 않는다. 대신 실패가 사라지는 것이 아니라 **전달 지연 · 중복 · 누락을 다뤄야 하는 경계**로 옮겨진다. 이 문서의 나머지는 그 경계를 다룬다.

## 2. 이벤트 계약

| 항목 | 값 |
|---|---|
| topic | `order-events` (테스트는 고유 suffix로 격리) |
| key | 내부 `orderId` 의 문자열. payload 의 `orderId` 와 같아야 한다 |
| `eventType` / `schemaVersion` | `OrderConfirmed` / `1` |
| `eventId` | `order-confirmed:v1:{orderId}`. 최초 확정 사실에서 유도하며 재발행 · 재전달마다 새로 만들지 않는다 |
| `orderId` | 확정 주문 ID |
| `confirmedAt` | 최초 확정 시각. UTC `Z` ISO-8601, 마이크로초까지 (D-83). `orders.confirmed_at` 과 같은 값 |
| `items` | `productId` 별 `quantity`. 상품당 하나, 수량 양수 |

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:v1:42",
  "orderId": 42,
  "confirmedAt": "2026-09-07T01:00:00.123456Z",
  "items": [
    {"productId": 7, "quantity": 2},
    {"productId": 8, "quantity": 1}
  ]
}
```

- **카드 정보 · PG 비밀 · 사용자 개인정보 · 가격은 넣지 않는다.** 구매 활동에 필요하지 않다.
- **시각 형식은 이벤트 DTO에서 명시한다.** producer 의 `JsonSerializer` 는 Spring Boot 가 설정한 ObjectMapper 가 아니라 자체 ObjectMapper 를 쓰므로, 그 기본값에 계약을 맡기지 않는다.
- **`items` 는 주문 저장 품목에서 만든다.** 주문 생성 때 이미 상품별로 합산돼 있다(ORD-03). 소비 시점의 상품 조회로 과거 사실을 바꾸지 않는다.

## 3. 발행 (commerce-api)

### 3.1 발행 대상 (D-70)

| 확정 경로 | 위치 | 발행 |
|---|---|---|
| PG SUCCESS | `OrderPaymentSteps.settle()` 의 `ReservationChange.CONSUME` (TX B) | 발행 |
| 0원 주문 (`NONE`) | `OrderPaymentSteps.start()` 의 0원 분기 (TX A) | **발행** |
| 이전 POINT 주문 | 재현용 `confirmWithPoint`, 운영 경로 없음 | 소급 발행하지 않음 |

- **0원 주문도 발행한다.** 구매 활동은 "결제가 일어난 구매"가 아니라 "확정된 주문의 구매"다. 빠뜨리면 0원 주문(D-30 · D-68)의 구매가 랭킹에서 사라진다.
- **한 주문은 두 경로 중 한 곳에서만 확정된다.** 0원이면 TX A, 아니면 TX B다. 경로가 둘이어도 같은 주문의 이벤트가 두 번 생기지 않는다.
- **대기 · UNKNOWN · 실패 · 확정 rollback · 이미 종결된 시도의 반복 적용에서는 발행하지 않는다.** 반복 적용은 `ReservationChange.NONE` 이 되어 발행 조건에 걸리지 않는다.

### 3.2 확정 시각 (D-71, D-82)

- **`orders.confirmed_at` 컬럼을 새로 둔다.** `paidAt` 은 결제 시각이고, 결제 시각과 확정 시각은 PG 승인 시각 도입이나 결제 뒤 확정 단계 추가 같은 변화에서 갈라질 수 있다. 이벤트의 확정 시각 출처를 결제 정책 변화와 분리한다.
  - **버린 대안: `paidAt` 재사용.** 지금은 항상 같은 값이고 스키마 변경이 없지만, 위 변화가 오면 이벤트 출처가 함께 흔들린다.
- **세 확정 메서드(`confirmPaid` · `confirmWithoutPayment` · `confirmWithPoint`)가 `paidAt` 과 같은 `now` 로 넣는다.** `now()` 를 따로 부르지 않는다.
- **넣을 때 마이크로초로 자른다.** DB 는 `DATETIME(6)` 이고 `createdAt` · `updatedAt` 도 같은 규칙이다(`BaseEntity`). 자르지 않으면 메모리 값(나노초)과 DB 값(마이크로초 반올림)이 달라져, TX 안에서 만든 payload 가 DB 사실과 어긋난다. `paidAt` 도 같은 `now` 를 쓰므로 함께 잘린다.
  - **버린 대안: payload 만들 때만 자름.** 엔티티 메모리 값과 DB 값이 계속 어긋나고, DB 반올림과 payload 잘라내기가 1마이크로초 다를 수 있다.
- **확정 뒤에는 바뀌지 않는다.** 확정 메서드는 PAYMENT_PENDING(0원은 DRAFT)에서만 통과하므로 중복 callback 이 다시 쓰지 못한다.
- DRAFT · PAYMENT_PENDING · PAYMENT_FAILED 에서는 비어 있다.
- 로컬 · 테스트는 `ddl-auto: create` 라 기존 행이 없다. 운영은 컬럼 추가와 함께 `UPDATE orders SET confirmed_at = paid_at WHERE status = 'CONFIRMED'` 가 필요하다.

### 3.3 발행 위치 (D-72)

**`@TransactionalEventListener(phase = AFTER_COMMIT)` 에서 Kafka 로 보낸다.**

```
TX A 또는 TX B (Steps)
 → 최초 확정이 일어났으면 애플리케이션 이벤트 발행
     payload(eventId, orderId, confirmedAt, items)를 TX 안에서 record 로 복사
 commit
 ── AFTER_COMMIT listener ──
 → Kafka send (key = orderId)
 → future 완료 콜백에서 결과 로그 (3.4)
```

- **발행 책임을 상태 전이 옆에 둔다.** 확정 지점이 두 곳(TX A · TX B)이고 Facade 경로는 세 곳(confirm · reconcile · callback)이다. Facade 에 두면 발행이 경로마다 흩어지고, Steps 를 다른 곳에서 부르면 빠진다.
- **payload 는 TX 안에서 복사한다.** `Order.items` 는 LAZY 라 commit 뒤 영속성 컨텍스트가 닫힌 상태에서 읽으면 실패할 수 있다.
- **commit 전에 보내지 않는다.** 보낸 뒤 rollback 되면 확정되지 않은 주문의 이벤트가 남는다. 같은 메서드의 `@Transactional` 코드 끝에서 send 해도 아직 commit 된 것이 아니다.
- **트랜잭션 없이 부르면 listener 가 실행되지 않는다.** 발행은 `@Transactional` Steps 안에서만 일어나므로 지금은 문제가 없고, 이 전제를 테스트로 고정한다.

| 버린 대안 | 얻을 수 있던 것 | 포기한 이유 |
|---|---|---|
| Facade 가 Steps 반환 뒤 직접 send | 흐름이 코드에 보이고, 캐시 무효화와 같은 패턴 | 발행 책임이 Facade 세 경로에 흩어지고, Steps 를 다른 곳에서 부르면 발행이 빠진다 |

### 3.4 send 결과와 실패 (D-73, D-74)

- **send 결과는 기다리지 않고 `whenComplete` 콜백에서 로그로 남긴다.**
  - 성공: eventId · orderId · topic · partition · offset 을 INFO 로 남긴다.
  - 실패: eventId · orderId · 원인을 ERROR 로 남긴다.
  - 콜백은 producer I/O 스레드에서 실행되므로 로그 외의 무거운 일을 하지 않는다.
- **send 실패는 주문을 되돌리지 않는다.** 주문 상태는 저장된 결과로 답하고, PG create 도 다시 부르지 않는다. 발행 실패는 API 응답이 아니라 로그로 드러낸다.
- **발행 실패를 DB 에 기록하거나 다시 보내지 않는다.** 그 일은 7주차 Outbox 가 맡는다.
- AFTER_COMMIT listener 의 예외는 호출자에게 전파되지 않는다. 이미 commit 된 확정에 500 을 주지 않으므로 이번 정책과 맞는다. 실제 동작은 실패 주입 테스트로 확인한다.

| 버린 대안 | 얻을 수 있던 것 | 포기한 이유 |
|---|---|---|
| `future.get(timeout)` 으로 기다림 | 응답 전에 결과가 남고 테스트가 단순 | broker 장애 때 confirm · reconcile · PG callback 응답이 timeout 만큼 늦어진다. timeout 은 실패가 아니라 "미확인"이라 최종 결과도 모른다 |
| 실패를 DB 에 기록 | 미발행 주문을 조회할 근거 | 7주차 Outbox 와 겹치고, AFTER_COMMIT 안에서 `REQUIRES_NEW` 가 필요하며 commit 과 기록 사이 공백도 남는다 |

### 3.5 producer 설정 (D-75, D-93, D-94)

| 설정 | 값 | 위치 | 이유 |
|---|---|---|---|
| `max.block.ms` | **1000 (잠정)** | commerce-api | `send()` 는 비동기지만 metadata 를 못 받으면 이 시간만큼 호출 스레드가 막힌다. AFTER_COMMIT 은 요청 스레드에서 실행되므로 이 값이 broker 장애 때 API 에 더해지는 최대 지연이다. 기본 60s 는 Tomcat 스레드를 묶어 다른 API 까지 멈춘다. 핵심 경로 외부 호출 중 가장 짧은 PG lookup read timeout(1s)을 넘지 않게 잡았다 |
| `retries` | 3 (유지) | 공용 `kafka.yml` | 짧은 재시도 안에 결과를 확정해 실패 로그가 빨리 남는다. idempotent producer 라 재시도로 broker 에 중복이 생기지 않는다 |
| `acks` | 클라이언트 기본값 `all` | — | 단일 broker(복제 1)라 복제 장애 내구성은 확인하지 못한다 (8장) |

- **commerce-api 에만 해당하는 값은 commerce-api 설정에 둔다.** 1s 의 근거가 commerce-api 의 응답 시간 예산이기 때문이다. 직렬화기처럼 모든 producer 에 공통인 값은 공용에 남긴다.
- **설정 우선순위 주의:** `spring.config.import` 로 불러온 `kafka.yml` 의 값이 commerce-api `application.yml` 의 값보다 우선한다. `kafka.yml` 에 이미 있는 키는 commerce-api 에서 덮어쓸 수 없다. 실제 producer 에 적용된 값을 테스트로 확인한다.
- **`max.block.ms` 는 구현 뒤 다시 정한다.** 측정할 것: 앱 시작 직후 첫 send 의 막힘 시간(하한 근거), broker 를 멈춘 상태의 confirm 응답 시간, 그 동안 Hikari active 커넥션 수(AFTER_COMMIT 에서 커넥션을 쥐고 있는지).

### 3.6 남는 누락 구간

```
주문 확정 DB commit
  ↓   ← 여기서 멈추거나 send 가 실패하면
Kafka 로 OrderConfirmed 전송
```

**주문은 CONFIRMED 인데 broker record 와 구매 활동이 없는 상태가 남을 수 있다.** commit 뒤 발행해도 DB 와 broker 는 원자적으로 묶이지 않는다. send 콜백이 오기 전에 프로세스가 끝나도 같은 구간이다. 이번 주는 이 구간을 재현해 기록하고, 7주차 Outbox 의 출발점으로 넘긴다(7.2).

## 4. 소비 (commerce-streamer)

### 4.1 테이블 (D-76, D-77, D-78)

```
processed_order_confirmation   주문 확정 1건 = 1행
  event_id      UNIQUE
  order_id      UNIQUE
  confirmed_at  DATETIME(6), UTC

product_purchase_activity      품목 1개 = 1행
  order_id, product_id   UNIQUE (order_id, product_id)
  quantity
```

- **eventId 와 orderId 에 각각 유일 제약을 건다.** eventId 는 메시지의 신원이고, orderId 는 "주문 확정은 한 번"이라는 업무의 자연 키다. producer 가 둘을 짝으로 만든다는 약속에 기대지 않는다. 버그나 스키마 버전 변경으로 같은 주문이 다른 eventId 로 와도 구매가 두 번 기록되지 않는다.
- **처리 기록은 주문 확정 이벤트 전용 테이블로 둔다.** 범용 처리 기록 테이블(eventId 만)에는 orderId 유일 제약을 둘 곳이 없어 주문 단위 테이블이 하나 더 필요해진다. 두 번째 consumer 가 생길 때 다시 판단한다.
- **`confirmed_at` 은 처리 기록에만 둔다.** 구매 활동은 `order_id` 로 처리 기록과 join 해 확정 시각을 얻는다. 현재 규모에서는 join 비용이 작아 비정규화하지 않는다. 과제의 "구매 활동의 quantity · confirmedAt 보존"은 활동 행에 직접 저장하라는 뜻이 아니라 활동이라는 사실 안에서 확정 시각을 잃지 않는다는 뜻으로 읽었다.
- 9주차 랭킹 조회는 "확정 시각이 이번 주인 처리 기록 → 활동 join → 상품별 수량 합"이 된다. `(order_id, product_id)` 유일 인덱스가 `order_id` 조회에도 쓰인다.
- 과거 주문의 `productId` 를 사실의 식별자로 보존한다. 현재 상품 행의 존재 · 가격에 의존하지 않고, 삭제 · 비노출 상품을 다시 노출하지 않는다.
- 테이블 이름은 과제 예시를 따랐다. 구현 때 기존 이름 규칙과 맞춰 확정한다.

| 버린 대안 | 포기한 이유 |
|---|---|
| 구매 활동 테이블만 (행마다 eventId) | 행이 품목마다 여러 개라 orderId 하나에 유일 제약을 걸 수 없다. 같은 주문이 상품 구성이 다른 새 eventId 로 오면 막지 못한다 |
| 범용 처리 기록(eventId) + 구매 활동 | orderId 유일성을 위해 주문 단위 테이블이 또 필요하다. 지금은 쓰는 consumer 가 하나뿐이다 |

### 4.2 파싱 검증 (D-80, D-84)

DB 에 들어가기 전에 아래를 거절한다. 모두 계약 위반이며 영구 오류다(5.2).

- 필수 필드 누락, 형식 오류
- `eventType` 이 `OrderConfirmed` 가 아님, 지원하지 않는 `schemaVersion`
- Kafka key 와 payload `orderId` 불일치
- `items` 가 비어 있음, `quantity` 가 0 이하
- **같은 `productId` 가 두 번 이상 나옴.** 계약상 producer 가 합산해 보낸다. 합산해 받아 주면 producer 버그를 숨긴다.
- **`confirmedAt` 이 마이크로초보다 정밀함.** 잘라서 받으면 다른 값이 조용히 같은 값으로 맞춰진다.

### 4.3 같은 사실 판정 (D-79, D-81)

**비교 대상은 `orderId` · `eventId` · `confirmedAt` · 상품별로 정규화한 `items` 다.** 업무 효과를 정하는 값이다. `schemaVersion` · `eventType` · key 는 4.2 에서 검증하고 비교하지 않는다. topic · partition · offset · timestamp 는 전달 경로 정보라 로그에만 남긴다. 전송 정보까지 비교하면 정상 재발행(7주차)을 충돌로 오판한다.

| eventId 로 찾은 기록 | orderId 로 찾은 기록 | 비교 | 처리 |
|---|---|---|---|
| 없음 | 없음 | — | 처리 기록 + 모든 품목 저장 |
| 행 X | 같은 행 X | confirmedAt · items 같음 | **no-op** (정상 완료, ack) |
| 행 X | 같은 행 X | confirmedAt 또는 items 다름 | **충돌** (같은 ID, 다른 내용) |
| 없음 | 행 Y | — | **충돌** (같은 주문, 다른 eventId). 내용이 같아도 충돌 |
| 행 X | 없음 또는 다른 행 | — | **충돌** (같은 eventId, 다른 주문) |

- **처리 기록 행과 그 주문의 활동 행을 직접 읽어 필드끼리 비교한다.** fingerprint 를 두지 않는다. 비교할 사실이 이미 처리 기록과 구매 활동에 그대로 보존돼 있어 해시는 요약본 중복일 뿐이다. 충돌이 나면 어느 필드가 달랐는지 로그에 바로 남긴다.
  - **버린 대안: fingerprint 저장.** 비교가 한 줄이지만, 정규화 규칙(정렬 · 시각 형식 · 숫자 표현)이 조금만 달라져도 같은 사실을 충돌로 판정하고, 무엇이 달랐는지 알 수 없다.
- **`confirmedAt` 은 `Instant` 로 마이크로초 단위 비교한다.**
- **충돌은 `INSERT IGNORE` 로 숨기지 않는다.** 추가 구매를 만들지 않고 오류로 드러낸다(5.2 영구 오류).

### 4.4 트랜잭션 경계와 경합 (D-85)

```
consume(event):
  4.2 검증
  DB TX:
    처리 기록 조회 (eventId, orderId)
    → 없음: 처리 기록 INSERT + 모든 품목 INSERT
    → 있음: 4.3 판정 → no-op 또는 충돌 예외
  commit 성공 뒤 반환 → listener 가 ack
```

- **처리 기록과 모든 품목을 같은 TX 에서 commit 한다.** 처리 기록만 먼저 저장하면 활동 저장 전에 멈췄을 때 재전달을 "이미 처리함"으로 건너뛰어 업무가 빠진다. 활동을 먼저 저장하고 기록을 따로 저장하면 그 사이에 멈췄을 때 중복 효과가 생긴다.
- **중간 품목 저장이 실패하면 처리 기록과 앞서 저장한 품목도 rollback 된다.**
- **동시 소비는 조회만으로 막지 않고 DB 유일 제약으로 막는다.** rebalance 직후 이전 담당자와 새 담당자가 겹치는 경우가 이에 해당한다.
- **유일 제약 위반이 나면 그 TX 를 rollback 하고, 별도 TX 에서 기록을 다시 읽어 4.3 규칙으로 판정한다.**
  - 유일 제약 위반이 난 TX 는 rollback-only 라 그 안에서 읽고 정상 commit 할 수 없다. 새 TX 는 경합 상대의 commit 뒤에 시작하므로 그 기록을 본다.
  - 어떤 제약이 위반됐는지는 구분하지 않는다. 다시 읽은 결과로 판정한다.
  - **다시 읽었는데 기록이 없으면 유일 제약 경합이 아닌 다른 오류다.** 로그를 남기고 예외를 그대로 던진다(5.2 일시적 · 미분류 경로). 다른 오류를 "중복"으로 바꾸지 않는다.
- 재판정 TX 는 self-invocation 으로 트랜잭션이 열리지 않는 문제를 피하도록 bean 을 나눈다(5주차 Facade · Steps 와 같은 이유).

## 5. listener 설정

### 5.1 factory (D-88, D-92)

**구매 활동 전용 record listener factory 를 새로 둔다.** 기존 batch factory(`BATCH_LISTENER`, demo 용)는 바꾸지 않는다. 테스트도 이 factory 를 그대로 쓰고, 환경에 따라 달라야 하는 값만 test profile 로 바꾼다. 실제 listener 의 ack · error handler 를 검증하기 위해서다.

| 항목 | 값 | 이유 |
|---|---|---|
| listener 형태 | record (batch 아님) | record 마다 DB TX 와 ack 경계를 둔다 |
| ack mode | `MANUAL_IMMEDIATE` | handler 의 DB commit 뒤 consumer 스레드에서 `acknowledge()` 를 부르는 즉시 offset 을 commit 한다. DB commit → offset commit 순서(at-least-once)를 record 단위로 맞춘다 |
| `enable.auto.commit` | `false` (실제 `ConsumerConfig` 값으로 확인) | 받은 시점에 offset 이 넘어가면 DB 저장 전에 멈췄을 때 누락된다 |
| concurrency | **1** | record 단위 DB 경계 · ack · 실패 흐름을 관찰하기 쉽다. 처리량이 부족하면 partition 수(3) 안에서 늘린다 |
| group | `purchase-activity` (예시, 구현 때 확정) | |
| `auto.offset.reset` | **`earliest`** (이 factory 에만) | committed offset 이 없을 때만 쓰인다(첫 배포, offset 만료, 보존 범위 이탈). `latest` 면 그때 구매 사실이 조용히 빠진다. 이미 처리한 record 는 no-op 이라 다시 읽어도 안전하다. 공용 `kafka.yml` 의 `latest` 는 그대로 둔다 |
| fetch | Kafka 기본값 | batch factory 의 `fetch.min.bytes=1MiB` · `fetch.max.wait.ms=5s` 는 그 factory 코드에만 있어 물려받지 않는다. 건별 처리라 모을 이유가 없고, 테스트에서 메시지가 늦게 보이는 문제도 생기지 않는다 |
| value 역직렬화 | `ByteArrayDeserializer` + 직접 파싱 | 4.2 검증을 handler 가 한다 |

- **일반 재시작은 `auto.offset.reset` 과 관계없다.** committed offset 부터 이어 읽는다. `earliest` 의 비용은 위 드문 경우에 보존 기간(기본 7일)만큼 따라잡는 시간과 DB 조회다.
- **실험의 consumer 수(1 → 2 → 3)는 이 설정과 별개다.** 배정 실험은 격리 topic 에서 테스트가 consumer 를 직접 만든다(7.1).

### 5.2 실패 처리 (D-86, D-87)

**실패를 영구 오류와 일시적 오류로 나눈다.**

| 종류 | 예 | 처리 | offset |
|---|---|---|---|
| **영구 오류** | 4.2 검증 실패, 4.3 충돌 | 재시도 없이 `order-events.DLT` 로 보내고 ERROR 로그 | DLT 발행 성공 뒤 진행 |
| **일시적 · 미분류 오류** | DB 연결 끊김, 잠금 대기 timeout, 4.4 재판정 뒤 기록 없음, 처음 보는 예외 | partition 을 pause 한 채 지수 backoff 재시도. 총 시간 상한을 넘으면 컨테이너 중단 + ERROR 로그 | **실패 record 에 머묾** |

- **영구 오류는 우리가 정의한 예외 타입으로 명시적으로 나열하고, 나머지는 모두 일시적 오류로 본다.** 모르는 오류가 DLT 로 가서 활동이 빠지는 것보다 멈춰서 사람이 보는 편이 안전하다.
- **DLT 로 옮긴 것은 업무 완료가 아니다.** 그 주문의 구매 활동은 빠진 상태이며, 이번 주에는 DLT 재처리를 만들지 않는다. 사람이 확인할 대상으로 남는다(8장).
- **일시적 오류 재시도 (잠정):** backoff 1s 에서 2배씩, 간격 최대 30s, 총 상한 5분. 테스트에서는 상한을 짧게 넣도록 설정값으로 둔다.
  - 재시도마다 WARN 로그(횟수 · 원인 · eventId · topic · partition · offset)를 남긴다.
  - **pause 동안에도 poll 을 계속해 group membership 이 유지된다.** rebalance 가 일어나지 않고, partition 이 다른 인스턴스로 넘어가지 않는다. 긴 대기도 `max.poll.interval.ms` 제약을 받지 않는다.
  - 상한 5분은 DB failover · 연결 끊김이 보통 수십 초에서 몇 분이고, 구매 활동은 몇 분 늦어도 업무 영향이 작다는 판단이다. 로컬 MySQL 재시작 시간을 참고값으로 잰다.
- **상한을 넘기면 중단하는 이유:** 일시적 오류로 잘못 분류된 버그나 사람이 고쳐야 하는 환경 문제(테이블 없음, 권한)는 기다려도 성공하지 않는다. 무제한이면 컨테이너는 살아 있고 lag 만 쌓이는 조용한 정지가 된다. 중단해도 offset 은 머물러 있어 잃는 것이 없다.
- **과제 문장과의 관계:** 과제의 error handler 확인 항목은 "실패 record 를 skip 하지 않고 중단 · 원인 표시 · 복구 후 재시작 가능"이다. 일시적 오류는 skip 하지 않고, 짧게는 스스로 회복하며, 상한 뒤에는 중단 → 원인 수정 → 재시작으로 같은 record 부터 다시 처리한다. 영구 오류의 DLT 는 중단형이 아니므로 재시도 소진 · recover(DLT 발행) · 그 뒤 offset 진행(`ackAfterHandle`)을 설명하고 테스트로 보인다.
- **구현 위험:** Spring Kafka 에는 "재시도 소진 뒤 컨테이너 중단"을 바로 지원하는 handler 가 없다. 예외별 handler 분기(`CommonDelegatingErrorHandler` 등)와, 소진 시 commit 없이 컨테이너를 멈추는 부분을 조립해야 할 수 있다. spring-kafka 3.3 문서로 확인하고, 복잡하면 다시 판단한다.

| 버린 대안 | 포기한 이유 |
|---|---|
| 모든 실패에 바로 중단 (`CommonContainerStoppingErrorHandler`) | 순간적인 DB 오류에도 멈추고 사람이 재시작해야 한다. 멈춘 consumer 의 partition 이 다른 인스턴스로 넘어가 같은 record 에서 연달아 멈출 수 있다 |
| 기본 `DefaultErrorHandler` | 재시도 소진 뒤 record 를 건너뛰고 offset 이 진행할 수 있다. DB 에 활동이 없는데 처리 완료가 된다 |
| 일시적 오류도 재시도 소진 뒤 DLT | DB 장애가 재시도 시간보다 길면 그동안의 확정 주문이 DLT 로 넘어가 빠진다. 과제 6장의 "offset 진행 안 함 → 재시작 → 재전달" 실험이 성립하지 않고, 성립시키려면 DLT 재처리 consumer 가 필요하다 |
| 일시적 오류 무제한 재시도 | 잘못 분류된 버그가 그 partition 을 끝없이 막는 조용한 정지가 된다 |
| 재시도 topic (`@RetryableTopic`) | offset 이 진행해 DLT 안과 같은 문제가 생긴다 |

## 6. topic · 인프라 · 공용 설정

### 6.1 topic (D-89, D-90, D-95)

| topic | partition | 만드는 곳 |
|---|---|---|
| `order-events` | 3 | 로컬: compose init 단계 / 테스트: fixture (고유 suffix) |
| `order-events.DLT` | 3 | 〃 |
| `demo.internal.topic-v1` | 기존과 같게 | 로컬: compose init 단계 |

- **partition 3:** concurrency 를 3 까지 늘릴 여유를 둔다. 나중에 늘리면 key → partition 매핑이 바뀌고 줄일 수는 없으므로 처음에 여유를 둔다. DLT 는 원래 record 와 같은 partition 번호로 보내므로 `order-events` 이상이어야 한다.
  - 인스턴스를 2대 이상 띄워 고르게 나누는 것이 중요해지면 6 을 다시 검토한다.
- **topic 은 인프라가 만들고, 앱은 topic 이 있다는 전제만 갖는다.** partition 수 같은 인프라 결정을 애플리케이션 코드(`NewTopic`)에 두지 않는다.
- **broker 자동 topic 생성을 끈다.** 켜 두면 init 보다 consumer 구독이나 첫 send 가 먼저 와서 broker 기본값(partition 1)으로 topic 이 생길 수 있고, topic 이름 오타도 조용히 새 topic 이 된다. 끄면 topic 이 없을 때 send 가 `max.block.ms` 뒤 실패해 바로 드러난다.
  - 클라이언트 설정의 `auto.create.topics.enable` 은 broker 설정이라 효과가 없다.

### 6.2 공용 설정 정정 (D-91)

지금 `modules:kafka` 와 `kafka.yml` 을 쓰는 앱은 commerce-streamer 뿐이고, 그 안의 소비자는 demo 하나다. 아래는 의도와 다르게 적힌 것을 고치는 것이라 원래 의도한 동작으로 돌아갈 뿐이다.

| 위치 | 현재 | 고침 | 영향 |
|---|---|---|---|
| `kafka.yml` consumer | `value-serializer: ByteArrayDeserializer` | `value-deserializer` | value deserializer 가 설정되지 않아 기본 String 이 쓰였다 |
| `kafka.yml` consumer | `properties.enable-auto-commit: false` | `enable.auto.commit: false` | 하이픈 키는 Kafka 가 모르는 설정이라 무시되고 기본값 true 가 된다. Spring 컨테이너는 스스로 false 로 보정하지만 `consumerFactory` 로 만든 low-level consumer 는 auto commit 이 켜져 재전달 실험이 성립하지 않는다 |
| `kafka.yml` local · test | admin `bootstrap.servers: kafka:9092` | override 제거 (`localhost:19092` 사용) | 컨테이너 내부 주소라 호스트에서 실행하면 admin 이 연결하지 못한다 |
| streamer `application.yml` | `spring.application.name: commerce-api` | `commerce-streamer` | streamer 의 client-id 가 commerce-api 로 찍힌다 |

고친 뒤 실제 `ConsumerConfig` · `ProducerConfig` 값을 테스트로 확인한다.

## 7. 실험

공통: 실제 broker(로컬 compose 또는 Testcontainers)를 쓴다. topic · group 은 실행마다 고유하게 만든다. 대기는 유한한 deadline 을 둔다. 자신이 시작한 consumer · 컨테이너만 정리한다. offset 값은 send metadata 에서 얻고 임의로 고정하지 않는다.

### 7.1 partition · consumer group

- partition 2개인 격리 topic 에서 같은 group 의 consumer 를 1 → 2 → 3 으로 바꾸며, 실제 assignment 가 안정된 뒤의 배정을 기록한다. 3개일 때 적어도 하나는 배정이 없다.
- 두 partition 에 명시적으로 record 를 보내 읽히는지 확인한다. 실험 record 는 업무 topic 과 분리해 가짜 구매 활동을 만들지 않는다.
- key 매핑: producer 가 partition 을 고르게 하고, 같은 key 의 send metadata 가 같은 partition 인지 확인한다. 서로 다른 key 가 다른 partition 으로 간다고 assert 하지 않는다.
- acks 선택(클라이언트 기본 `all`)과 단일 broker 로 확인하지 못하는 복제 장애 보장을 설명한다.

### 7.2 commit 뒤 발행 누락 구간

- 실제 주문 API · 로컬 PG fixture · PG 결과 적용 경로로 두 주문을 확정한다.
- **첫 주문:** 확정 commit 뒤 send 직전에서 통제된 hook 으로 멈추거나 전송 실패를 주입한다. 주문 CONFIRMED · 예약 CONSUMED 는 남고 해당 eventId 의 broker record · 구매 활동은 없음을 확인한다. OS 강제 종료라고 표현하지 않는다.
- **둘째 주문:** 실제 broker · consumer 까지 처리해 구매 활동을 확인한다.
- 두 사례의 재현 코드와 관찰값(orderId · providerOrderId · transactionKey · eventId · confirmedAt · items · 주문 · 예약 · broker · 구매 활동 상태)을 남기고 임시 DB 는 정리한다. 카드 값 · 비밀정보는 제외한다.

### 7.3 DB commit 뒤 offset 미커밋 재전달

low-level `KafkaConsumer` 로 실제 handler 를 호출하되 첫 실행의 offset commit 만 보류한다. 같은 broker · topic · group · DB 를 유지하고 consumer 만 새로 만든다.

| 순서 | 행동 | 확인할 사실 |
|---|---|---|
| 준비 | 격리 topic 에 target 발행 | send metadata 의 partition · offset `n` |
| 시작 위치 | 첫 consumer 가 배정된 뒤 n 을 commit | committed offset `n` |
| 첫 처리 | poll → handler DB commit | 구매 활동 · 처리 기록 존재, committed offset 여전히 `n` |
| 종료 | auto commit false, 대기 중 commit · 종료 시 commit callback 없이 close | committed offset `n` |
| 재시작 | 새 consumer, 같은 group, seek · offset reset 없음 | 같은 topic · partition · offset `n` 재전달 |
| 두 번째 처리 | handler no-op → n+1 commit | 활동 행 수 · 수량 그대로, committed offset `n+1` |

- 시작 위치 지정과 재전달을 혼동하지 않는다. 두 번째 consumer 는 저장된 group offset 에서 실제로 다시 읽어야 한다.
- 성공 판정은 poll position 이나 로그가 아니라 committed offset 과 DB 결과로 한다.

### 7.4 실제 Spring listener 실패 경로

- 별도 격리 topic · group 에서 DB commit 전 오류(일시적 오류)를 주입한다.
- 활동 · 처리 기록 전체 rollback, 실패 record 이후로 offset 이 진행하지 않음, pause · WARN 로그, 상한 뒤 중단 · ERROR 로그를 확인한다.
- 오류를 제거하고 같은 group 으로 컨테이너를 재시작해 재전달 · 정상 저장 · ack 를 확인한다.
- 영구 오류(충돌 payload)를 보내 DLT record(원래 위치 헤더 포함), 활동 없음, 원래 offset 진행을 확인한다.
- handler 를 두 번 직접 호출하는 단위 테스트로 7.3 · 7.4 를 대체하지 않는다.

## 8. 알려진 한계

- **commit 뒤 발행 누락:** 확정 commit 과 send 사이, 또는 send 콜백 전에 멈추면 이벤트가 사라진다. 기록도 재전송도 없다. 7주차 Outbox 로 개선한다. Outbox 의 재전송은 at-least-once 라 중복 발행이 생기며, stable eventId 와 consumer 멱등 처리가 그 전제다.
- **DLT 의 구매 활동 누락:** 영구 오류로 DLT 에 간 주문은 구매 활동이 없고 9주차 랭킹에서 빠진다. 재처리 경로는 범위 밖이다.
- **소비 중단:** 일시적 오류가 상한을 넘으면 이 앱의 구매 활동 소비가 멈춘다(concurrency 1). lag 알림은 범위 밖이라 로그로만 드러난다.
- **단일 broker:** 복제 수 1 이라 `acks=all` · ISR · broker 장애 내구성을 증명하지 못한다. 실험은 통제된 종료이며 OS 장애 · 네트워크 분할을 시험하지 않는다.
- **순서:** 주문당 이벤트가 1건이라 같은 key 의 순서 문제는 아직 없다. 한 주문에 이벤트가 여러 건 생기면 DB 쪽 순서 확인이 필요하다.

## 9. 기존 코드 · 설정 변경

| 대상 | 변경 |
|---|---|
| `Order` | `confirmedAt` 추가. 세 확정 메서드가 마이크로초로 자른 같은 `now` 로 `paidAt` 과 함께 넣음 |
| `OrderPaymentSteps` | 최초 확정(0원 분기 · `CONSUME`)에서 애플리케이션 이벤트 발행. payload 는 TX 안에서 복사 |
| commerce-api `build.gradle.kts` | `modules:kafka` 의존성, 테스트에 `testFixtures(project(":modules:kafka"))` |
| commerce-api 설정 | `kafka.yml` import, producer `max.block.ms` |
| `modules:kafka` `kafka.yml` | 6.2 정정 |
| `modules:kafka` testFixtures | `KafkaTestContainersConfig`, topic 도우미 신설 (10장) |
| commerce-streamer | 구매 활동 listener · factory · error handler · handler · 엔티티 · 저장소. `application.name` 정정. 테스트 기반 신설 |
| `docker/infra-compose.yml` | broker 자동 topic 생성 끔, topic 생성 init 단계 |

## 10. 테스트

### 10.1 fixture (D-96, D-97)

- **`modules:kafka` 의 testFixtures 에 둔다.** jpa · redis 와 같은 패턴이다. commerce-api(발행 테스트)와 commerce-streamer(소비 테스트)가 같은 fixture 를 쓴다.
- `KafkaTestContainersConfig`: static 으로 JVM 당 한 번 띄우고 `System.setProperty("spring.kafka.bootstrap-servers", …)` 로 주소를 넣는다. 컨테이너 내부 주소와 호스트 주소를 섞지 않는다. broker 자동 topic 생성을 끈다.
- **topic 도우미:** 기존 `DatabaseCleanUp` 처럼 전부 지우지 않는다. Kafka topic 삭제는 비동기라 지운 직후 같은 이름으로 다시 만들면 실패할 수 있다. 고유 suffix 이름을 만들고, partition 수를 지정해 topic 을 만들고, 그 테스트가 만든 topic 만 지운다.
- **테스트 broker 는 로컬 compose 와 같은 Kafka 3.5 대로 맞춘다.** 앱이 실제로 붙고 PR 실험을 기록하는 broker 가 3.5.1 이다. 클라이언트(kafka-clients 3.8.1)는 API 버전 협상으로 더 오래된 broker 와 동작한다. 공식 `apache/kafka` 이미지는 3.7.0 부터라 `confluentinc/cp-kafka:7.5.x`(Kafka 3.5 포함)를 후보로 두고, 구현 때 실제 버전을 확인한다.

### 10.2 장면

| 장면 | 핵심 기대 결과 | 위치 (가칭) |
|---|---|---|
| PG 확정 → Kafka → streamer | 저장된 품목 · 수량 · confirmedAt 과 같은 구매 활동 | 통합 (api + 실제 broker) · `PurchaseActivityIntegrationTest` |
| 0원 주문 확정 | 이벤트 발행, 구매 활동 저장 | `OrderConfirmedPublicationTest` |
| 대기 · UNKNOWN · 실패 · 확정 rollback | `OrderConfirmed` 발행 없음 | 〃 |
| 중복 callback · reconcile | 새 이벤트 · 추가 재고 차감 없음 | 〃 |
| confirmedAt 저장 | 마이크로초로 잘림, payload = DB 값, 중복 callback 이 바꾸지 못함 | 〃 · 단위 |
| send 실패 주입 | 주문 상태 그대로, ERROR 로그 | 〃 |
| 같은 record 재전달 | 구매 효과 한 번 | `KafkaDeliveryTest` (7.3) |
| 같은 ID 의 payload 충돌 · 같은 주문 다른 eventId | 추가 효과 없이 충돌, DLT | `PurchaseActivityIntegrationTest` · 7.4 |
| 파싱 검증 (4.2 각 항목) | 거절, DLT | 단위 · 통합 |
| 동시 중복 소비 | 유일 제약으로 한 번만 저장, 재판정으로 no-op | `PurchaseActivityIntegrationTest` |
| 품목 저장 중 실패 | 처리 기록 · 앞선 품목 전체 rollback | 〃 |
| listener 실패 경로 | 7.4 | `KafkaDeliveryTest` |
| partition · group 배정 | 7.1 | 〃 |
| commit 후 발행 공백 | 확정 주문은 남고 활동 누락 (7.2) | `OrderConfirmedPublicationTest` |
| 유효 설정 | `enable.auto.commit=false`, deserializer, `max.block.ms`, `auto.offset.reset=earliest` 가 실제 config 에 적용됨 | 각 앱 |
| 기존 기능 | PG · 예약 · 이전 POINT 사실 · 소유권 · 삭제 비노출 보존 | 기존 테스트 |

- 테스트 0건 · skip · broker 연결 실패는 성공이 아니다.

## 11. 구현 제안 때 정할 것

- 애플리케이션 이벤트를 누가 낼지(Steps 또는 도메인 서비스), Kafka 발행 포트를 어느 계층 · 패키지에 둘지. application 은 infrastructure 에 의존하지 않으므로 포트 인터페이스가 필요하다.
- 확정 시각을 자르는 위치: `Order` 확정 메서드 안 또는 `OrderService`.
- 7.2 의 "commit 뒤 send 직전" hook 을 꽂을 자리.
- 4.4 재판정 TX 의 bean 분리, 영구 오류 예외 타입.
- 5.2 error handler 조립 방식(구현 위험 참고).
- 테이블 · group · 테스트 클래스 이름.
- compose init 의 구체 방식(broker healthy 뒤 `kafka-topics.sh --create --if-not-exists`).
- streamer 의 패키지 구조와 ArchUnit 적용 여부.

## 12. 결정 요약

| 번호 | 결정 |
|---|---|
| D-70 | PG SUCCESS 와 0원 주문 확정을 `OrderConfirmed` 로 발행한다. 이전 POINT 확정 주문은 소급 발행하지 않는다 |
| D-71 | `orders.confirmed_at` 컬럼을 새로 둔다. 결제 시각(`paidAt`)과 확정 시각을 분리한다 |
| D-72 | 발행은 `@TransactionalEventListener(AFTER_COMMIT)` 에서 한다. payload 는 TX 안에서 복사하고, 최초 확정이 일어난 실행만 발행한다 |
| D-73 | 발행 실패는 ERROR 로그로만 드러낸다. 기록 · 재전송은 7주차 Outbox 가 맡는다 |
| D-74 | send 결과는 기다리지 않고 `whenComplete` 콜백에서 로그로 남긴다 |
| D-75 | `max.block.ms` 잠정값 1s. 구현 뒤 측정해 다시 정한다 |
| D-76 | 처리 기록의 eventId 와 orderId 에 각각 유일 제약을 건다. 구매 활동은 `(order_id, product_id)` 유일 |
| D-77 | 처리 기록은 주문 확정 이벤트 전용 테이블로 둔다 |
| D-78 | `confirmed_at` 은 처리 기록에만 두고 구매 활동은 join 으로 얻는다 |
| D-79 | 같은 사실 판정은 orderId · eventId · confirmedAt · 정규화한 items 로 한다. schemaVersion · eventType · key 는 파싱 때 검증한다 |
| D-80 | payload 의 중복 productId 는 계약 위반으로 거절한다 |
| D-81 | 같은 사실은 필드를 직접 비교해 판정하고 fingerprint 는 두지 않는다 |
| D-82 | 확정 시각은 확정 메서드에서 넣을 때 마이크로초로 자른다 |
| D-83 | payload 의 `confirmedAt` 은 UTC `Z` ISO-8601(마이크로초)로 쓰고 이벤트 DTO 에서 형식을 명시한다 |
| D-84 | 마이크로초보다 정밀한 `confirmedAt` 은 계약 위반으로 거절한다 |
| D-85 | 유일 제약 위반은 rollback 뒤 별도 TX 에서 다시 읽어 판정한다. 기록이 없으면 다른 오류로 던진다 |
| D-86 | 영구 오류(검증 실패 · 충돌)는 재시도 없이 DLT 로 보낸다. 영구 오류는 명시적으로 나열하고 나머지는 일시적 오류로 본다 |
| D-87 | 일시적 · 미분류 오류는 partition 을 pause 한 채 지수 backoff 로 재시도하고, 총 상한(잠정 5분)을 넘으면 컨테이너를 중단한다 |
| D-88 | 구매 활동 전용 record listener factory, `MANUAL_IMMEDIATE`, concurrency 1 |
| D-89 | `order-events` 와 `order-events.DLT` 는 partition 3 |
| D-90 | topic 은 인프라(compose init · 테스트 fixture)가 만든다 |
| D-91 | 공용 설정의 오타 · 주소 오류 4건(deserializer, auto commit 키, admin 주소, streamer 이름)을 고친다 |
| D-92 | 구매 활동 group 의 `auto.offset.reset` 은 `earliest`(새 factory 에만) |
| D-93 | commerce-api 에만 해당하는 producer 설정은 commerce-api 설정에 둔다 |
| D-94 | producer `retries: 3` 은 유지한다 |
| D-95 | 로컬 · 테스트 broker 의 자동 topic 생성을 끈다. demo topic 도 init 에서 만든다 |
| D-96 | Kafka 테스트 fixture 는 `modules:kafka` 의 testFixtures 에 둔다 |
| D-97 | 테스트 broker 는 로컬 compose 와 같은 Kafka 3.5 대로 맞춘다 |
