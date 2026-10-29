# 6주차 전달 실험 결과

6주차 설계 7장의 실험 기록이다. 숫자와 ID는 아래 실행일의 **한 번 실행한 관찰값**이며, 다시 실행하면 ID · offset · 시간은 달라진다.

- 실행일: 2026-10-29, 로컬 Docker. broker 는 Testcontainers `cp-kafka:7.5.3`(Apache Kafka 3.5, 복제 1) 또는 로컬 compose `bitnamilegacy/kafka:3.5.1`(복제 1)
- **단일 broker 한계:** 복제 · ISR · `acks=all` 의 장애 내구성, broker 장애 중 leader 이동, 네트워크 분할은 시험하지 않았다. 아래 "broker 정지"는 `docker pause`(응답 없음)이며 연결 거부 · 프로세스 종료와는 다른 장애다.

| 절 | 실험 코드 | 실행 |
|---|---|---|
| 7.1 배정 · key | `apps/commerce-streamer/.../interfaces/consumer/KafkaDeliveryTest` (`Assignment`) | 평소 `check` 에 포함 |
| 7.2 누락 구간 | `apps/commerce-api/.../application/order/listener/OrderConfirmedPublicationGapTest` | 평소 `check` 에 포함 |
| 7.2 정상 사례 (두 앱) | `apps/commerce-api/.../experiment/OrderConfirmedEndToEndExperiment` | 아래 절차, `TEST_LOCAL_INFRA=true` |
| 7.3 재전달 | `KafkaDeliveryTest` (`Redelivery`) | 평소 `check` 에 포함 |
| 7.4 listener 실패 경로 | `apps/commerce-streamer/.../interfaces/consumer/OrderConfirmedConsumerTest` | 평소 `check` 에 포함 |
| 측정 (D-75) | `apps/commerce-api/.../experiment/OrderEventPublishExperiment` | `RUN_EXPERIMENT=true` |

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest' --tests '*OrderConfirmedConsumerTest'
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublication*'
RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*OrderEventPublishExperiment'
```

## 7.1 partition · consumer group

partition 2개인 격리 topic, 같은 group. 배정은 broker 가 준 `assignment()` 가 0.1초 간격으로 10번 연속 같을 때 기록했다.

| 같은 group 의 consumer | c1 | c2 | c3 |
|---|---|---|---|
| 1개 | [0, 1] | — | — |
| 2개 | [0] | [1] | — |
| 3개 | [0] | [1] | **[] (배정 없음)** |

- 두 partition 에 명시적으로 보낸 record: partition 0 offset 0 → c1, partition 1 offset 0 → c2 가 읽음.
- key 매핑: partition 3개 topic 에 key `42` 로 5번 보내 producer 가 고른 partition 이 모두 `1`. 서로 다른 key 가 다른 partition 으로 간다고는 확인하지 않았다(그런 보장은 없다).
- consumer 를 partition 수보다 늘려도 처리 담당은 늘지 않는다. 구매 활동 listener 가 concurrency 1 로 시작하고 partition 을 3 으로 둔 이유(D-88, D-89)다.

## 7.2 commit 뒤 발행 누락 구간

실제 확정 · `TestPgServer` · PG 결과 적용(reconcile) 경로로 두 주문을 확정했다. 첫 주문만 발행 포트(`OrderEventPublisher`)에 들어온 순간 상태를 기록하고 실제 send 를 부르지 않았다. 통제된 hook 이며 OS 강제 종료가 아니다.

| | 누락 사례 | 정상 사례 |
|---|---|---|
| orderId | 1 | 2 |
| providerOrderId | LP-ORD-0000000001 | LP-ORD-0000000002 |
| transactionKey | TEST-TX-0001 | TEST-TX-0002 |
| eventId | order-confirmed:v1:1 | order-confirmed:v1:2 |
| items | 상품 1 × 2 | 상품 1 × 1 |
| hook 시점(이미 commit 됨) 주문 / 예약 | CONFIRMED / CONSUMED | — |
| 최종 주문 / 예약 | CONFIRMED / CONSUMED | CONFIRMED / CONSUMED |
| broker record | **0건** | 1건 (`order-events` partition 2 offset 0) |

- **확정 주문은 남는데 이벤트가 없다.** commit 뒤에 보내도 DB 와 broker 는 원자적으로 묶이지 않는다. 기록도 재전송도 없어 이 주문의 구매 활동은 만들어지지 않는다. 7주차 Outbox 의 출발점이다.
- 7주차에 같은 장면을 다시 만들 때는 위 ID 를 그대로 쓰지 않는다. 새 격리 실행에서 실제 생성된 주문의 ID 로 준비 → 복구를 확인한다.

### 정상 사례: 두 앱을 실제 broker 로 잇기

api 가 확정 · 발행하고, 따로 띄운 commerce-streamer 가 같은 broker 에서 소비해 구매 활동을 남기는지 같은 MySQL 에서 확인했다.

| 항목 | 값 |
|---|---|
| orderId / providerOrderId / transactionKey | 1 / LP-ORD-0000000001 / TEST-TX-0001 (E2E 전용 DB 라 ID 가 1 부터) |
| 주문 | CONFIRMED, confirmed_at 2026-10-29 09:34:40.563068 UTC |
| api 발행 로그 | `eventId=order-confirmed:v1:1 topic=order-events partition=0 offset=0` |
| streamer 소비 로그 | `result=RECORDED eventId=order-confirmed:v1:1 topic=order-events partition=0 offset=0` |
| 처리 기록 | event_id `order-confirmed:v1:1`, confirmed_at 주문과 같음 |
| 구매 활동 | (상품 1, 수량 2), (상품 2, 수량 1) |
| `purchase-activity` group offset | partition 0 committed 1, lag 0 |

**실행 절차.** 로컬 3306 을 이미 다른 MySQL 이 쓰고 있어서, compose 의 MySQL 대신 E2E 전용 임시 컨테이너를 3307 에 띄웠다. 두 앱 모두 `ddl-auto=update` 로 실행해 기존 데이터를 지우지 않고, 실험 쪽은 truncate 하지 않는다.

```bash
docker compose -f docker/infra-compose.yml up -d kafka kafka-init redis-master redis-readonly
docker run -d --name w6-e2e-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_USER=application -e MYSQL_PASSWORD=application -e MYSQL_DATABASE=loopers mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
./gradlew :apps:commerce-streamer:bootRun --args='--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/loopers --spring.jpa.hibernate.ddl-auto=update'
TEST_LOCAL_INFRA=true E2E_JDBC_URL=jdbc:mysql://localhost:3307/loopers ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedEndToEndExperiment'
docker rm -f w6-e2e-mysql
```

- `TEST_LOCAL_INFRA=true` 면 MySQL · Redis · Kafka test fixture 가 컨테이너를 띄우지 않고 test profile 의 localhost 주소(compose)를 쓴다.
- 실행 뒤 임시 MySQL 은 지웠다. 로컬 compose broker 의 `order-events` 에는 이 record 와 group offset 이 남아 있다.

## 7.3 DB commit 뒤 offset 미커밋 재전달

low-level `KafkaConsumer`(자동 commit 꺼짐) 로 실제 parser · `PurchaseActivityFacade` 를 호출하고, 첫 실행의 offset commit 만 보류했다. broker · topic · group · DB 는 유지하고 consumer 만 새로 만들었다.

| 순서 | 관찰 |
|---|---|
| 준비 | target 을 partition 2 offset 0 (`n = 0`)에 발행 (send metadata) |
| 시작 위치 | 첫 consumer 배정 뒤 0 을 commit → committed **0** |
| 첫 처리 | 2@0 을 받아 `RECORDED`, 구매 활동 2행, committed **0** |
| 종료 | 대기 중 commit · 종료 commit callback 없이 `close(10s)` → committed **0** |
| 재시작 | 새 consumer, 같은 group, seek · reset 없음 → 같은 2@0 을 다시 받음 |
| 두 번째 처리 | `ALREADY_RECORDED`(no-op) → 1 을 commit → committed **1** |

- 전달 2회, 같은 topic · partition · offset, 구매 활동 2 → 2 행, committed offset **0 → 0 → 0 → 1**.
- 판정은 poll position 이 아니라 broker 의 committed offset 과 DB 로 했다. 시작 위치를 정한 commit 은 준비이지 재전달이 아니다.
- 이 실험은 "DB 효과 뒤 offset 미커밋"에서의 재전달만 증명한다. 프로세스 강제 종료 · broker 장애는 시험하지 않았다.

## 7.4 실제 Spring listener 의 실패 경로

`OrderConfirmedConsumerTest` (이 클래스 전용 topic · group, 재시도 100ms → 최대 200ms, 상한 1.5s).

| 장면 | 관찰 |
|---|---|
| 정상 | 저장 뒤 n+1 commit |
| 같은 record 두 번 | 활동 1건, 두 record 를 모두 지나 commit |
| 형식 오류 | 같은 partition 의 `.DLT` 로 원래 바이트 · key · 원래 offset 헤더, 활동 없음, 원래 offset 진행 |
| 같은 eventId 다른 수량 | 두 번째만 DLT, 첫 사실(수량 2) 유지 |
| 일시적 오류 3번 뒤 회복 | 재시도 중 committed ≤ n, 시도 4번, 회복 뒤 저장 · n+1 |
| 일시적 오류가 상한을 넘김 | child 컨테이너 정지(`isInExpectedState=false`), committed ≤ n, 활동 0 → 원인 제거 · 재시작 뒤 같은 record 를 다시 받아 저장 · n+1 |

## 측정: `max.block.ms` (D-75)

`max.block.ms=1000`, 각 장면 5번. broker 정지는 Testcontainers broker 를 `docker pause`.

| 항목 | 관찰 (ms) |
|---|---|
| 새 producer 의 첫 `send()` 막힘 (metadata 없음) | 8, 7, 5, 5, 9 |
| 같은 producer 의 다음 `send()` 막힘 | 2, 0, 0, 0, 0 |

| 장면 | reconcile 응답 (ms) | 커넥션이 사용 중이던 시간 (ms) |
|---|---|---|
| broker 정상 | 193 (첫 요청), 19, 28, 17, 21 | 177, 17, 27, 10, 16 |
| broker 정지 · producer 의 metadata 캐시됨 | 22, 19, 16, 16, 32 | 21, 16, 10, 11, 28 |
| broker 정지 · producer 새로 만듦 (broker 가 죽은 채 재시작한 상황) | 1036, 1083, 1060, 1080, 1077 | 1032, 1072, 1049, 1074, 1066 |

(Hikari active 최댓값은 모든 장면에서 1. 동시 요청 없이 하나씩 측정했다.)

- **실행 중 broker 가 멈추면 응답은 늦어지지 않는다.** metadata 가 캐시돼 있어 `send()` 는 버퍼에 넣고 바로 돌아온다. 대신 결과는 나중에 콜백으로 온다(`delivery.timeout.ms` 안의 재시도 뒤 성공 또는 실패). 이 실험은 정지가 짧아 그 실패 시점까지는 관찰하지 않았다.
- **broker 가 없는 채 producer 가 새로 만들어지면 확정 응답이 `max.block.ms` 만큼 늘어난다.** 실패는 `Topic order-events not present in metadata after 1000 ms` 로 콜백 없이 send 호출에서 나며 ERROR 로그로 남았다(주문은 CONFIRMED 유지).
- **그 1초 동안 DB 커넥션을 쥐고 있다.** 커넥션 사용 시간이 응답 시간과 거의 같다. AFTER_COMMIT listener 는 트랜잭션 자원 정리(커넥션 반납) 전에 실행되기 때문이다. 설계 3.5 에서 "확인 필요"로 둔 가정이 맞았다. 동시에 확정이 몰리면 커넥션 풀(운영 40, 테스트 10)이 `max.block.ms` 동안 묶여, 확정과 무관한 DB 요청까지 커넥션을 기다리게 된다(3주차의 풀 고갈과 같은 모양).
- **하한 근거:** 정상 broker 에서 metadata 를 처음 받는 데 10ms 미만이었다. 1s 는 정상 첫 send 보다 100배 이상 크다.

**결정: `max.block.ms` 를 1000 → 500 으로 낮췄다 (D-75).** 장애 때 응답 · 커넥션이 묶이는 시간을 반으로 줄이면서, 로컬 측정이 대표하지 못하는 운영 네트워크의 첫 연결 지연을 감당할 여유(정상 첫 send 의 50배 이상)는 남겼다. 200ms 는 Outbox 전이라 넘기면 정상 이벤트를 잃을 위험이 커져 버렸다. 위 표는 1000 일 때의 측정이다.
