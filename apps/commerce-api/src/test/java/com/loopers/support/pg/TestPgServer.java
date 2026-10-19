package com.loopers.support.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로컬 테스트 PG 계약(5주차 설계 3.1)을 따르는 작은 HTTP 서버. 127.0.0.1 의 빈 포트에서 뜸.
 * 거래 저장소 · create 수신 횟수 · 받은 요청을 관찰할 수 있고, 생성 · 조회 응답 방식을 테스트마다 고정함.
 * 무작위 지연 · 성공 확률은 넣지 않음 (5주차 설계 10). 테스트가 끝나면 close 로 정리함
 */
public final class TestPgServer implements AutoCloseable {

    public static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    /** 생성 요청에 대한 응답 방식 */
    public enum CreateBehavior {
        /** 거래를 PENDING 으로 만들고 202 */
        ACCEPT,
        /** 거래를 만든 뒤 응답 없이 연결을 끊음 */
        ACCEPT_THEN_DROP,
        /** 거래를 만든 뒤 정한 시간만큼 늦게 202 */
        ACCEPT_THEN_DELAY,
        /** 거래를 만들지 않고 422 확정 거절 */
        REJECT_BEFORE_CREATE,
        /** 거래를 만들지 않고 정한 상태 코드 · 본문으로 응답 */
        RESPOND
    }

    /** 조회 요청에 대한 응답 방식 */
    public enum LookupBehavior {
        /** 저장소 그대로 응답 */
        NORMAL,
        /** 정한 시간만큼 늦게 저장소 그대로 응답 */
        DELAY,
        /** 정한 상태 코드 · 본문으로 응답 */
        RESPOND
    }

    /** 서버가 받은 요청 한 건. query 는 디코딩한 값, rawQuery 는 받은 그대로 */
    public record RecordedRequest(String method, String path, String rawQuery, Map<String, List<String>> headers, String body) {
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
        }

        public Optional<String> queryParam(String name) {
            if (rawQuery == null) {
                return Optional.empty();
            }
            for (String pair : rawQuery.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                if (decode(key).equals(name)) {
                    return Optional.of(separator < 0 ? "" : decode(pair.substring(separator + 1)));
                }
            }
            return Optional.empty();
        }
    }

    /** 저장된 외부 거래 */
    public record Transaction(String transactionKey, String orderId, long amount, String status) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger lookupCount = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile CreateBehavior createBehavior = CreateBehavior.ACCEPT;
    private volatile LookupBehavior lookupBehavior = LookupBehavior.NORMAL;
    private volatile Duration delay = Duration.ZERO;
    private volatile int fixedCreateStatus;
    private volatile String fixedCreateBody;
    private volatile int fixedLookupStatus;
    private volatile String fixedLookupBody;

    private TestPgServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static TestPgServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            TestPgServer pg = new TestPgServer(server, executor);
            server.createContext(PAYMENTS_PATH, pg::handle);
            server.setExecutor(executor);
            server.start();
            return pg;
        } catch (IOException e) {
            throw new IllegalStateException("테스트 PG 서버를 띄우지 못함", e);
        }
    }

    /**
     * Spring 테스트가 함께 쓰는 서버. 설정값(base URL)이 테스트마다 같아야 Spring 컨텍스트 캐시가 유지되므로 한 번만 띄움.
     * 테스트마다 reset 으로 거래 · 횟수 · 응답 방식을 비움. JVM 이 끝날 때 정리함
     */
    public static synchronized TestPgServer shared() {
        if (shared == null) {
            shared = start();
            Runtime.getRuntime().addShutdownHook(new Thread(shared::close));
        }
        return shared;
    }

    private static TestPgServer shared;

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 거래 저장소 · 받은 요청 · 횟수를 비우고 응답 방식을 정상(수락 · 그대로 조회)으로 되돌림 */
    public void reset() {
        transactions.clear();
        requests.clear();
        createCount.set(0);
        lookupCount.set(0);
        createBehavior = CreateBehavior.ACCEPT;
        lookupBehavior = LookupBehavior.NORMAL;
        delay = Duration.ZERO;
    }

    // 응답 방식

    public void willAccept() {
        this.createBehavior = CreateBehavior.ACCEPT;
    }

    public void willDropResponseAfterCreate() {
        this.createBehavior = CreateBehavior.ACCEPT_THEN_DROP;
    }

    public void willDelayResponseAfterCreate(Duration delay) {
        this.delay = delay;
        this.createBehavior = CreateBehavior.ACCEPT_THEN_DELAY;
    }

    public void willRejectBeforeCreate() {
        this.createBehavior = CreateBehavior.REJECT_BEFORE_CREATE;
    }

    public void willRespondToCreate(int status, String body) {
        this.fixedCreateStatus = status;
        this.fixedCreateBody = body;
        this.createBehavior = CreateBehavior.RESPOND;
    }

    public void lookupNormally() {
        this.lookupBehavior = LookupBehavior.NORMAL;
    }

    public void lookupWillDelay(Duration delay) {
        this.delay = delay;
        this.lookupBehavior = LookupBehavior.DELAY;
    }

    public void lookupWillRespond(int status, String body) {
        this.fixedLookupStatus = status;
        this.fixedLookupBody = body;
        this.lookupBehavior = LookupBehavior.RESPOND;
    }

    // 거래 저장소

    /** 주문의 거래를 직접 추가함. 복수 거래 · 불일치 장면을 만들 때 씀 */
    public String addTransaction(String orderId, long amount, String status) {
        String transactionKey = nextTransactionKey();
        transactions.add(new Transaction(transactionKey, orderId, amount, status));
        return transactionKey;
    }

    /** 처리 중인 거래를 SUCCESS · FAILED 로 바꿈 */
    public void complete(String transactionKey, String status) {
        Transaction current = transaction(transactionKey)
            .orElseThrow(() -> new IllegalArgumentException("없는 거래: " + transactionKey));
        transactions.replaceAll(transaction -> transaction == current
            ? new Transaction(current.transactionKey(), current.orderId(), current.amount(), status)
            : transaction);
    }

    public List<Transaction> transactions() {
        return List.copyOf(transactions);
    }

    public List<Transaction> transactionsOf(String orderId) {
        return transactions.stream().filter(transaction -> transaction.orderId().equals(orderId)).toList();
    }

    public Optional<Transaction> transaction(String transactionKey) {
        return transactions.stream().filter(transaction -> transaction.transactionKey().equals(transactionKey)).findFirst();
    }

    // 관찰

    public int createCount() {
        return createCount.get();
    }

    public int lookupCount() {
        return lookupCount.get();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("받은 요청이 없음");
        }
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // 처리

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getRawPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(), path, rawQuery, Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(request);
        try {
            if ("POST".equals(request.method()) && PAYMENTS_PATH.equals(path)) {
                handleCreate(exchange, body);
            } else if ("GET".equals(request.method()) && PAYMENTS_PATH.equals(path)) {
                handleList(exchange, request.queryParam("orderId").orElse(""));
            } else if ("GET".equals(request.method()) && path.startsWith(PAYMENTS_PATH + "/")) {
                handleSingle(exchange, decode(path.substring(PAYMENTS_PATH.length() + 1)));
            } else {
                respond(exchange, 404, "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private void handleCreate(HttpExchange exchange, String body) throws IOException, InterruptedException {
        createCount.incrementAndGet();
        JsonNode request = objectMapper.readTree(body);
        String orderId = request.path("orderId").asText();
        long amount = request.path("amount").asLong();
        switch (createBehavior) {
            case ACCEPT -> respond(exchange, 202, transactionJson(createPending(orderId, amount)).toString());
            case ACCEPT_THEN_DROP -> {
                createPending(orderId, amount);
                // 응답 헤더를 보내지 않고 연결을 닫아, 호출자가 거래 생성 여부를 알 수 없게 함
                exchange.close();
            }
            case ACCEPT_THEN_DELAY -> {
                Transaction transaction = createPending(orderId, amount);
                Thread.sleep(delay.toMillis());
                respond(exchange, 202, transactionJson(transaction).toString());
            }
            case REJECT_BEFORE_CREATE -> {
                ObjectNode rejected = objectMapper.createObjectNode()
                    .put("code", REJECTED_BEFORE_CREATE)
                    .put("orderId", orderId)
                    .put("amount", amount)
                    .put("transactionCreated", false);
                respond(exchange, 422, rejected.toString());
            }
            case RESPOND -> respond(exchange, fixedCreateStatus, fixedCreateBody);
        }
    }

    private void handleList(HttpExchange exchange, String orderId) throws IOException, InterruptedException {
        lookupCount.incrementAndGet();
        switch (lookupBehavior) {
            case NORMAL -> respond(exchange, 200, listJson(orderId));
            case DELAY -> {
                Thread.sleep(delay.toMillis());
                respond(exchange, 200, listJson(orderId));
            }
            case RESPOND -> respond(exchange, fixedLookupStatus, fixedLookupBody);
        }
    }

    private void handleSingle(HttpExchange exchange, String transactionKey) throws IOException {
        lookupCount.incrementAndGet();
        Optional<Transaction> transaction = transaction(transactionKey);
        if (transaction.isEmpty()) {
            respond(exchange, 404, "{}");
            return;
        }
        respond(exchange, 200, transactionJson(transaction.get()).toString());
    }

    private Transaction createPending(String orderId, long amount) {
        Transaction transaction = new Transaction(nextTransactionKey(), orderId, amount, "PENDING");
        transactions.add(transaction);
        return transaction;
    }

    private String nextTransactionKey() {
        return "TEST-TX-%04d".formatted(sequence.incrementAndGet());
    }

    private String listJson(String orderId) {
        ArrayNode array = objectMapper.createArrayNode();
        List<Transaction> matched = new ArrayList<>(transactionsOf(orderId));
        matched.forEach(transaction -> array.add(transactionJson(transaction)));
        return array.toString();
    }

    private ObjectNode transactionJson(Transaction transaction) {
        return objectMapper.createObjectNode()
            .put("transactionKey", transaction.transactionKey())
            .put("orderId", transaction.orderId())
            .put("amount", transaction.amount())
            .put("status", transaction.status());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        // 본문이 없으면 길이 -1 로 보내 빈 응답임을 알림
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
