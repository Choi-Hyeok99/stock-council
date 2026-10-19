package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.broker.KisExecutionSettings;

@Component
public class KisMarketDataClient {
    private static final String PAPER_BASE = "https://openapivts.koreainvestment.com:29443";
    private static final String LIVE_BASE = "https://openapi.koreainvestment.com:9443";
    private static final String TOKEN = "/oauth2/tokenP";
    private static final String TRADES = "/uapi/domestic-stock/v1/quotations/inquire-ccnl";
    private static final String MINUTES = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String RANKING = "/uapi/domestic-stock/v1/quotations/volume-rank";
    private static final String STOCK_HISTORY = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String INDEX_MINUTES = "/uapi/domestic-stock/v1/quotations/inquire-time-indexchartprice";
    private static final String INDEX_HISTORY = "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final String SECURITY = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String BALANCE = "/uapi/domestic-stock/v1/trading/inquire-balance";
    private static final Map<String, Set<String>> EXECUTION = Map.of(
            BALANCE, Set.of("VTTC8434R", "TTTC8434R"),
            "/uapi/domestic-stock/v1/trading/inquire-psbl-order", Set.of("VTTC8908R", "TTTC8908R"),
            "/uapi/domestic-stock/v1/trading/inquire-daily-ccld", Set.of("VTTC0081R", "TTTC0081R"),
            "/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl", Set.of("TTTC0084R"));
    private final ObjectMapper mapper;
    private final String key;
    private final String secret;
    private final ExecutionBrokerClient.Environment environment;
    private final String base;
    private final Clock clock;
    private final Transport transport;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;
    private Instant lastTokenAttempt = Instant.EPOCH;
    private long lastRequestNanos;
    private final KisRequestGate requestGate=new KisRequestGate();
    private <T>T exclusive(java.util.function.Supplier<T> action){return requestGate.run(false,action);}
    private <T>T executionExclusive(java.util.function.Supplier<T> action){return requestGate.run(true,action);}
    @FunctionalInterface
    interface Transport {
        JsonNode send(HttpRequest request) throws Exception;
        default Page sendPage(HttpRequest request) throws Exception { return new Page(send(request), ""); }
    }

    /** HTTP continuation is kept outside the broker body and never persisted with secrets. */
    public record Page(JsonNode body, String continuation) {
        @Override public String toString() { return "KisPage[REDACTED]"; }
    }

    @Autowired
    public KisMarketDataClient(ObjectMapper mapper, @Value("${kis.app-key:}") String key,
                               @Value("${kis.app-secret:}") String secret, Clock clock,
                               KisExecutionSettings settings, @Value("${execution.environment:PAPER}") String selectedEnvironment) {
        this(mapper, selectedKeys(key, secret, settings, selectedEnvironment), clock, productionTransport(mapper), selectedEnvironment,
                false);
    }

    KisMarketDataClient(ObjectMapper mapper, String key, String secret, Clock clock, Transport transport) {
        this(mapper, new String[]{key, secret}, clock, transport, "PAPER", false);
    }

    private KisMarketDataClient(ObjectMapper mapper, String[] keys, Clock clock, Transport transport, String selectedEnvironment, boolean liveEnabled) {
        this.mapper = mapper; this.key = keys[0]; this.secret = keys[1]; this.clock = clock; this.transport = transport;
        this.environment = parseEnvironment(selectedEnvironment);
        this.base = environment == ExecutionBrokerClient.Environment.PAPER ? PAPER_BASE : LIVE_BASE;
    }

    private static ExecutionBrokerClient.Environment parseEnvironment(String environment) {
        try { return ExecutionBrokerClient.Environment.valueOf(environment); }
        catch (RuntimeException ignored) { throw new IllegalStateException("주문 환경은 PAPER 또는 LIVE여야 합니다."); }
    }

    private static String[] selectedKeys(String paperKey, String paperSecret, KisExecutionSettings settings, String environment) {
        if (parseEnvironment(environment) == ExecutionBrokerClient.Environment.PAPER) return new String[]{paperKey, paperSecret};
        KisExecutionSettings.Credentials live = settings.credentials();
        return live == null ? new String[]{"", ""} : new String[]{live.key(), live.secret()};
    }

    private static Transport productionTransport(ObjectMapper mapper) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new Transport() {
            @Override public JsonNode send(HttpRequest request) throws Exception { return sendPage(request).body(); }
            @Override public Page sendPage(HttpRequest request) throws Exception {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new TransportStatusException(response.statusCode());
                return new Page(mapper.readTree(response.body()), response.headers().firstValue("tr_cont").orElse(""));
            }
        };
    }

    public boolean configured() { return !key.isBlank() && !secret.isBlank(); }
    public ExecutionBrokerClient.Environment executionEnvironment() { return environment; }
    public String sourcePrefix() { return environment == ExecutionBrokerClient.Environment.PAPER ? "KIS_VTS" : "KIS_LIVE"; }
    public String domainLabel() { return environment == ExecutionBrokerClient.Environment.PAPER ? "KIS 모의 도메인" : "KIS 실전 도메인"; }

    /** Reuses the selected environment's collectors, token and global pacing. No GET or POST is retried here. */
    public Page executionQuery(String path, String transactionId, Map<String, String> params, String continuation) {
        return executionQuery(ExecutionBrokerClient.Environment.PAPER, path, transactionId, params, continuation);
    }

    public Page executionQuery(ExecutionBrokerClient.Environment requestedEnvironment, String path, String transactionId,
            Map<String, String> params, String continuation) {
        return executionExclusive(() -> executionRequest(requestedEnvironment, path, transactionId, params, continuation));
    }

    /** Only the manually confirmed PAPER sale/cancel adapter uses this single non-retried POST. */
    public Page executionPost(ExecutionBrokerClient.Environment requestedEnvironment,String path,String transactionId,Map<String,String> values){
        return executionExclusive(()->{
            if(requestedEnvironment!=ExecutionBrokerClient.Environment.PAPER||environment!=requestedEnvironment||!configured()
              ||!(path.equals("/uapi/domestic-stock/v1/trading/order-cash")&&transactionId.equals("VTTC0011U")
              ||path.equals("/uapi/domestic-stock/v1/trading/order-rvsecncl")&&transactionId.equals("VTTC0013U")))throw new ExecutionBrokerClient.NotSubmitted();
            final boolean[] sent={false};
            try{
                String access=accessToken();
                HttpRequest request=requestBuilder(path).header("authorization","Bearer "+access).header("appkey",key).header("appsecret",secret)
                    .header("tr_id",transactionId).header("custtype","P").header("Content-Type","application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(values))).build();
                Page page=sendPage(request,()->{com.personaltrader.orders.OrderLatencyContext.dispatch();sent[0]=true;});
                String result=page.body().path("rt_cd").asText("");
                if(result.matches("[1-9][0-9]?")){String code=page.body().path("msg_cd").asText("");throw new ExecutionBrokerClient.Rejected(code.matches("[A-Z]{2,8}[0-9]{2,8}")?code:"BROKER_REJECTED");}
                if(!result.equals("0"))throw new IllegalStateException();return page;
            }catch(ExecutionBrokerClient.Rejected rejected){throw rejected;}
            catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();if(!sent[0])throw new ExecutionBrokerClient.NotSubmitted();throw new IllegalStateException("주문 접수 응답 미확정 · 재전송 없음");}
        });
    }
    private Page executionRequest(ExecutionBrokerClient.Environment requestedEnvironment, String path, String transactionId, Map<String, String> values, String continuation) {
        if (!configured() || requestedEnvironment != environment || !EXECUTION.getOrDefault(path, Set.of()).contains(transactionId)
                || !transactionId.startsWith(environment == ExecutionBrokerClient.Environment.PAPER ? "V" : "T")
                || !(continuation.isEmpty() || continuation.equals("N"))
) {
            throw new IllegalStateException("선택한 주문 환경·요청 설정 확인 필요");
        }
        try {
            String accessToken = accessToken();
            String query = "?" + values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
            HttpRequest.Builder builder = requestBuilder(path + query).header("authorization", "Bearer " + accessToken)
                    .header("appkey", key).header("appsecret", secret).header("tr_id", transactionId)
                    .header("custtype", "P").header("tr_cont", continuation).header("Content-Type", "application/json; charset=utf-8");
            HttpRequest request=builder.GET().build();Page page=sendPage(request);
            String result = page.body().path("rt_cd").asText("");
            if (result.matches("[1-9][0-9]?")) {
                String code = page.body().path("msg_cd").asText("");
                if (Set.of("EGW00123", "EGW00121").contains(code)) { token = null; tokenExpiresAt = Instant.EPOCH; }
                throw new ExecutionBrokerClient.Rejected(code.matches("[A-Z]{2,8}[0-9]{2,8}") ? code : "BROKER_REJECTED");
            }
            if (!result.equals("0")) throw new IllegalStateException("응답 상태 미확인");
            return page;
        } catch (ExecutionBrokerClient.Rejected rejected) { throw rejected;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            diagnoseExecutionRead(path,failure);
            throw new IllegalStateException("조회 응답 검증 실패");
        }
    }

    public TimedMarketQuote collect(String code) { return exclusive(() -> collectLocked(code)); }

    private TimedMarketQuote collectLocked(String code) {
        if (!configured()) throw new IllegalStateException("KIS 키 설정이 필요합니다.");
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("조회 종목 코드가 올바르지 않습니다.");
        try {
            String accessToken = accessToken();
            // Sequential calls respect the mock API's conservative one-request-per-second pacing.
            JsonNode trades = query(TRADES, "FHKST01010300", code, accessToken, "");
            String hour = clock.instant().atZone(TimedMarketQuote.SEOUL).format(DateTimeFormatter.ofPattern("HHmmss"));
            JsonNode minutes = query(MINUTES, "FHKST03010200", code, accessToken,
                    "&FID_INPUT_HOUR_1=" + hour + "&FID_PW_DATA_INCU_YN=Y&FID_ETC_CLS_CODE=");
            return TimedMarketQuote.parse(code, trades.path("output"), minutes.path("output2"), clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("시세 수집이 중단됐습니다.");
        } catch (IllegalArgumentException exception) {
            // Only validation messages generated locally are exposed by the collector.
            throw new IllegalStateException("가격 또는 기준 날짜·시각 검증에 실패했습니다.");
        } catch (Exception exception) {
            // Do not propagate transport exceptions, request headers, credentials, or API response bodies.
            throw new IllegalStateException("KIS 시세 조회에 실패했습니다. 다음 수집 주기에 재시도합니다.");
        }
    }

    public List<RankedStock> ranking(BigDecimal maximumPrice) { return exclusive(() -> rankingLocked(maximumPrice)); }

    private List<RankedStock> rankingLocked(BigDecimal maximumPrice) {
        try {
            Map<String, String> params = Map.ofEntries(
                    Map.entry("FID_COND_MRKT_DIV_CODE", "J"), Map.entry("FID_COND_SCR_DIV_CODE", "20171"),
                    Map.entry("FID_INPUT_ISCD", "0000"), Map.entry("FID_DIV_CLS_CODE", "1"),
                    Map.entry("FID_BLNG_CLS_CODE", "3"), Map.entry("FID_TRGT_CLS_CODE", "111111111"),
                    Map.entry("FID_TRGT_EXLS_CLS_CODE", "1111111111"), Map.entry("FID_INPUT_PRICE_1", "1000"),
                    Map.entry("FID_INPUT_PRICE_2", maximumPrice.toPlainString()), Map.entry("FID_VOL_CNT", ""),
                    Map.entry("FID_INPUT_DATE_1", ""));
            return RankedStock.parse(marketQuery(RANKING, "FHPST01710000", params).path("output"));
        } catch (Exception exception) { throw safeFailure(exception, "거래대금 순위 조회·검증 실패"); }
    }

    private Instant lastExecutionReadDiagnostic;
    private void diagnoseExecutionRead(String path,Exception failure){
        Instant now=clock.instant();
        if(lastExecutionReadDiagnostic!=null&&Duration.between(lastExecutionReadDiagnostic,now).getSeconds()<60)return;
        lastExecutionReadDiagnostic=now;Throwable cause=failure;
        for(int i=0;i<8&&cause.getCause()!=null&&cause.getCause()!=cause;i++)cause=cause.getCause();
        var frames=cause.getStackTrace();String stage=path.substring(path.lastIndexOf('/')+1);
        if(!stage.matches("[A-Za-z0-9_-]{1,50}"))stage="READ";
        org.slf4j.LoggerFactory.getLogger(KisMarketDataClient.class).warn("Broker read diagnostic stage={} type={} site={}",stage,cause.getClass().getSimpleName(),
            frames.length==0?"unavailable":frames[0].getClassName()+"."+frames[0].getMethodName()+":"+frames[0].getLineNumber());
    }

    public MarketHistory history(String code, boolean index) { return exclusive(() -> historyLocked(code, index)); }

    private MarketHistory historyLocked(String code, boolean index) {
        if (!code.matches(index ? "(0001|1001)" : "[0-9]{6}")) throw new IllegalArgumentException("조회 코드 오류");
        try {
            LocalDate today = clock.instant().atZone(TimedMarketQuote.SEOUL).toLocalDate();
            Map<String, String> params = new java.util.HashMap<>(Map.of(
                    "FID_COND_MRKT_DIV_CODE", index ? "U" : "J", "FID_INPUT_ISCD", code,
                    "FID_INPUT_DATE_1", today.minusDays(70).format(DateTimeFormatter.BASIC_ISO_DATE),
                    "FID_INPUT_DATE_2", today.format(DateTimeFormatter.BASIC_ISO_DATE), "FID_PERIOD_DIV_CODE", "D"));
            if (!index) params.put("FID_ORG_ADJ_PRC", "0");
            JsonNode data = marketQuery(index ? INDEX_HISTORY : STOCK_HISTORY,
                    index ? "FHKUP03500100" : "FHKST03010100", params);
            return MarketHistory.parse(data.path("output2"), clock.instant(), index);
        } catch (Exception exception) { throw safeFailure(exception, "확정 일봉 조회·검증 실패"); }
    }

    public SecurityCheck securityCheck(String code) { return exclusive(() -> securityCheckLocked(code)); }

    private SecurityCheck securityCheckLocked(String code) {
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("조회 코드 오류");
        try {
            JsonNode data = marketQuery(SECURITY, "FHKST01010100",
                    Map.of("FID_COND_MRKT_DIV_CODE", "J", "FID_INPUT_ISCD", code)).path("output");
            if (!code.equals(data.path("stck_shrn_iscd").asText())) return new SecurityCheck(false, "종목 식별 미확인");
            for (String flag : List.of("temp_stop_yn", "invt_caful_yn", "short_over_yn", "sltr_yn", "mang_issu_cls_code"))
                if (!"N".equals(data.path(flag).asText())) return new SecurityCheck(false, "거래정지·유의·과열 등 위험 상태 또는 미확인");
            if (!"00".equals(data.path("mrkt_warn_cls_code").asText())) return new SecurityCheck(false, "시장경고 또는 미확인");
            return new SecurityCheck(true, "조회 시점 종목 위험 상태 확인");
        } catch (Exception exception) { throw safeFailure(exception, "종목 위험 상태 조회 실패"); }
    }

    public record SecurityCheck(boolean allowed, String reason) {}

    public MarketIndexQuote indexMinutes(String code) {
        return exclusive(() -> {
            if (!code.matches("(0001|1001)")) throw new IllegalArgumentException("지수 코드 오류");
            try {
                JsonNode response = marketQuery(INDEX_MINUTES, "FHKUP03500200", Map.of(
                        "FID_COND_MRKT_DIV_CODE", "U", "FID_INPUT_ISCD", code,
                        "FID_ETC_CLS_CODE", "1", "FID_INPUT_HOUR_1", "60", "FID_PW_DATA_INCU_YN", "Y"));
                return MarketIndexQuote.minutes(code, response, clock.instant(), sourcePrefix() + "_INDEX_MINUTE");
            } catch (Exception exception) { throw safeFailure(exception, "지수 분봉 조회·날짜/시각 검증 실패"); }
        });
    }

    public MarketIndexQuote indexLatestDay(String code) {
        return exclusive(() -> {
            if (!code.matches("(0001|1001)")) throw new IllegalArgumentException("지수 코드 오류");
            try {
                LocalDate today = clock.instant().atZone(TimedMarketQuote.SEOUL).toLocalDate();
                JsonNode response = marketQuery(INDEX_HISTORY, "FHKUP03500100", Map.of(
                        "FID_COND_MRKT_DIV_CODE", "U", "FID_INPUT_ISCD", code,
                        "FID_INPUT_DATE_1", today.minusDays(14).format(DateTimeFormatter.BASIC_ISO_DATE),
                        "FID_INPUT_DATE_2", today.format(DateTimeFormatter.BASIC_ISO_DATE), "FID_PERIOD_DIV_CODE", "D"));
                return MarketIndexQuote.daily(code, response, clock.instant(), sourcePrefix() + "_INDEX_DAILY");
            } catch (Exception exception) { throw safeFailure(exception, "최신 지수 일봉 조회·날짜 검증 실패"); }
        });
    }

    private IllegalStateException safeFailure(Exception exception, String message) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        return new IllegalStateException(message);
    }

    private JsonNode marketQuery(String path, String transactionId, Map<String, String> params) throws Exception {
        if (!configured()) throw new IllegalStateException("KIS 키 설정 필요");
        String accessToken = accessToken();
        String query = params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        return checkedResponse(send(requestBuilder(path + "?" + query).header("authorization", "Bearer " + accessToken)
                .header("appkey", key).header("appsecret", secret).header("tr_id", transactionId)
                .header("custtype", "P").GET().build()));
    }

    private JsonNode send(HttpRequest request) throws Exception {
        return sendPage(request).body();
    }

    private Page sendPage(HttpRequest request) throws Exception {
        return sendPage(request, () -> { });
    }

    private Page sendPage(HttpRequest request, Runnable beforeDispatch) throws Exception {
        // One shared lane and 1.1 second spacing for all endpoints.
        if(!request.uri().getPath().equals(TOKEN))requestGate.yieldToPriority();
        if (lastRequestNanos != 0) {
            long remaining = 1_100_000_000L - (System.nanoTime() - lastRequestNanos);
            if (remaining > 0) {
                if(remaining>=com.personaltrader.orders.OrderLatencyContext.remainingNanos())throw new com.personaltrader.orders.OrderLatencyContext.DispatchDeadline();
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            }
        }
        beforeDispatch.run();
        lastRequestNanos = System.nanoTime();
        try { return transport.sendPage(request); }
        catch (TransportStatusException exception) {
            if (exception.status == 401) { token = null; tokenExpiresAt = Instant.EPOCH; }
            throw exception;
        }
    }

    private static final class TransportStatusException extends Exception {
        private final int status;
        private TransportStatusException(int status) { super("KIS HTTP failure"); this.status = status; }
    }

    private String accessToken() throws Exception {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) return token;
        if (Duration.between(lastTokenAttempt, now).compareTo(Duration.ofSeconds(60)) < 0)
            throw new IllegalStateException("토큰 재발급 대기 중입니다.");
        lastTokenAttempt = now;
        HttpRequest request = requestBuilder(TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "grant_type", "client_credentials", "appkey", key, "appsecret", secret)))).build();
        JsonNode result = send(request);
        String value = result.path("access_token").asText("");
        long expiry = result.path("expires_in").asLong(0);
        if (value.isBlank() || expiry <= 60) throw new IllegalStateException("토큰 응답 검증 실패");
        token = value;
        tokenExpiresAt = now.plusSeconds(expiry - 60);
        return token;
    }

    private JsonNode query(String path, String transactionId, String code, String accessToken, String extra) throws Exception {
        HttpRequest request = requestBuilder(path + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + code + extra)
                .header("authorization", "Bearer " + accessToken).header("appkey", key).header("appsecret", secret)
                .header("tr_id", transactionId).header("custtype", "P").GET().build();
        return checkedResponse(send(request));
    }

    private JsonNode checkedResponse(JsonNode result) {
        if (!"0".equals(result.path("rt_cd").asText())) {
            // Invalid/expired-token responses clear memory without issuing a second token in this cycle.
            if (Set.of("EGW00123", "EGW00121").contains(result.path("msg_cd").asText())) {
                token = null; tokenExpiresAt = Instant.EPOCH;
            }
            throw new IllegalStateException("시세 응답 검증 실패");
        }
        return result;
    }

    private HttpRequest.Builder requestBuilder(String pathAndQuery) {
        URI uri = URI.create(base + pathAndQuery);
        if (!"https".equals(uri.getScheme())
                || !uri.getHost().equals(environment == ExecutionBrokerClient.Environment.PAPER ? "openapivts.koreainvestment.com" : "openapi.koreainvestment.com")
                || uri.getPort() != (environment == ExecutionBrokerClient.Environment.PAPER ? 29443 : 9443)
                || !(Set.of(TOKEN, TRADES, MINUTES, RANKING, STOCK_HISTORY, INDEX_HISTORY, INDEX_MINUTES, SECURITY).contains(uri.getPath())
                    || EXECUTION.containsKey(uri.getPath())))
            throw new IllegalArgumentException("허용되지 않은 API 경로");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
    }
}
