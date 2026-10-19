package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayRequest;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.ProviderTransaction;
import com.loopers.domain.payment.ProviderTransactionStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 로컬 테스트 PG 의 HTTP 계약을 관찰 결과로 바꾸는 adapter (5주차 설계 3.2, 3.3).
 * 숨은 재시도가 없는 JDK HttpClient 를 쓰고, 생성 요청은 결과와 관계없이 한 번만 보냄 (D-60, D-61).
 * 계약과 정확히 맞지 않는 응답은 효과 0건의 증거로 보지 않고 결과 미확인 · 조회 불가로 돌려줌
 */
@Component
public class TestPgPaymentGateway implements PaymentGateway {

    static final String PROVIDER_USER_HEADER = "X-USER-ID";
    static final String PAYMENTS_PATH = "/api/v1/payments";
    static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";
    private static final int ACCEPTED = 202;
    private static final int UNPROCESSABLE = 422;
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;

    private final RestClient createClient;
    private final RestClient lookupClient;
    private final String callbackUrl;
    private final ObjectMapper objectMapper;

    public TestPgPaymentGateway(TestPgProperties properties, ObjectMapper objectMapper) {
        // HTTP/2 업그레이드 시도 없이 HTTP/1.1 로 고정함. JDK HttpClient 는 기본 설정에서 POST 를 다시 보내지 않음
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
        this.createClient = restClient(properties, httpClient, properties.createReadTimeout());
        this.lookupClient = restClient(properties, httpClient, properties.lookupReadTimeout());
        this.callbackUrl = properties.callbackUrl();
        this.objectMapper = objectMapper;
    }

    private static RestClient restClient(TestPgProperties properties, HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader(PROVIDER_USER_HEADER, properties.providerUserId())
            .build();
    }

    /** 생성 응답을 E1 · E2 · E3 로 나눔. 연결 실패 · timeout · 해석 불가는 모두 E3 (5주차 설계 3.3) */
    @Override
    public PaymentCreateResult create(PaymentGatewayRequest request) {
        try {
            return createClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(createBody(request))
                .exchange((httpRequest, response) -> interpretCreate(request, response.getStatusCode().value(), readTree(response)));
        } catch (RuntimeException e) {
            return new PaymentCreateResult.Unknown();
        }
    }

    /** 주문 단위 거래 목록. 빈 목록도 정상 결과이며, 200 배열이 아니면 조회 불가 (5주차 설계 3.3) */
    @Override
    public PaymentLookupResult findByProviderOrderId(String providerOrderId) {
        try {
            return lookupClient.get()
                // 값은 URI 변수로 넘겨 예약 문자까지 인코딩함
                .uri(builder -> builder.path(PAYMENTS_PATH).queryParam("orderId", "{orderId}").build(providerOrderId))
                .accept(MediaType.APPLICATION_JSON)
                .exchange((httpRequest, response) -> interpretList(response.getStatusCode().value(), readTree(response)));
        } catch (RuntimeException e) {
            return new PaymentLookupResult.Unavailable();
        }
    }

    /** 거래 한 건 조회. 업무 흐름은 주문 단위 조회만 쓰고(D-66), 이 경로는 계약 확인용으로 둠. 404 는 빈 목록 */
    public PaymentLookupResult findByTransactionKey(String transactionKey) {
        try {
            return lookupClient.get()
                .uri(PAYMENTS_PATH + "/{transactionKey}", transactionKey)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((httpRequest, response) -> interpretSingle(response.getStatusCode().value(), readTree(response)));
        } catch (RuntimeException e) {
            return new PaymentLookupResult.Unavailable();
        }
    }

    private String createBody(PaymentGatewayRequest request) {
        ObjectNode body = objectMapper.createObjectNode()
            .put("orderId", request.providerOrderId())
            .put("cardType", request.cardType())
            .put("cardNo", request.cardNo())
            .put("amount", request.amount())
            .put("callbackUrl", callbackUrl);
        return body.toString();
    }

    private PaymentCreateResult interpretCreate(PaymentGatewayRequest request, int status, JsonNode body) {
        if (status == ACCEPTED && body.isObject() && matchesRequest(request, body)
            && "PENDING".equals(textOrNull(body, "status"))) {
            String transactionKey = textOrNull(body, "transactionKey");
            if (transactionKey != null && !transactionKey.isBlank()) {
                return new PaymentCreateResult.Accepted(transactionKey);
            }
        }
        // 효과 0건의 증거는 code · transactionCreated=false · 주문 · 금액이 모두 맞을 때만 인정함 (5주차 설계 3.3 E2)
        if (status == UNPROCESSABLE && body.isObject() && matchesRequest(request, body)
            && REJECTED_BEFORE_CREATE.equals(textOrNull(body, "code"))
            && body.path("transactionCreated").isBoolean() && !body.path("transactionCreated").booleanValue()) {
            return new PaymentCreateResult.RejectedWithoutEffect();
        }
        return new PaymentCreateResult.Unknown();
    }

    private PaymentLookupResult interpretList(int status, JsonNode body) {
        if (status != OK || !body.isArray()) {
            return new PaymentLookupResult.Unavailable();
        }
        List<ProviderTransaction> transactions = new ArrayList<>();
        for (JsonNode element : body) {
            Optional<ProviderTransaction> transaction = toTransaction(element);
            if (transaction.isEmpty()) {
                return new PaymentLookupResult.Unavailable();
            }
            transactions.add(transaction.get());
        }
        return new PaymentLookupResult.Found(transactions);
    }

    private PaymentLookupResult interpretSingle(int status, JsonNode body) {
        if (status == NOT_FOUND) {
            return new PaymentLookupResult.Found(List.of());
        }
        if (status != OK) {
            return new PaymentLookupResult.Unavailable();
        }
        return toTransaction(body)
            .<PaymentLookupResult>map(transaction -> new PaymentLookupResult.Found(List.of(transaction)))
            .orElseGet(PaymentLookupResult.Unavailable::new);
    }

    /** 필드가 빠졌거나 타입 · 상태 값이 계약과 다르면 해석 불가로 봄 */
    private static Optional<ProviderTransaction> toTransaction(JsonNode node) {
        String transactionKey = textOrNull(node, "transactionKey");
        String orderId = textOrNull(node, "orderId");
        JsonNode amount = node.path("amount");
        Optional<ProviderTransactionStatus> status = statusOf(textOrNull(node, "status"));
        if (transactionKey == null || orderId == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProviderTransaction(transactionKey, orderId, amount.longValue(), status.get()));
    }

    private static Optional<ProviderTransactionStatus> statusOf(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ProviderTransactionStatus status : ProviderTransactionStatus.values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesRequest(PaymentGatewayRequest request, JsonNode body) {
        JsonNode amount = body.path("amount");
        return request.providerOrderId().equals(textOrNull(body, "orderId"))
            && amount.isIntegralNumber() && amount.canConvertToLong() && amount.longValue() == request.amount();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    /** 본문이 없거나 JSON 이 아니면 빈 노드. 호출 쪽 조건이 모두 거짓이 되어 E3 · 조회 불가로 이어짐 */
    private JsonNode readTree(ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readAllBytes();
        if (bytes.length == 0) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException e) {
            return objectMapper.missingNode();
        }
    }
}
