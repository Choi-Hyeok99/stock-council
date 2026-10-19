package com.personaltrader.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.marketdata.KisMarketDataClient;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** KRX cash limit orders. Network acknowledgement, fills and account snapshots remain separate facts. */
@Component
public class KisExecutionBrokerClient implements ExecutionBrokerClient {
    private static final String ROOT = "/uapi/domestic-stock/v1/trading/";
    private static final String BALANCE = ROOT + "inquire-balance";
    private static final String BUYING_POWER = ROOT + "inquire-psbl-order";
    private static final String ORDERS = ROOT + "inquire-daily-ccld";
    private static final String PLACE = ROOT + "order-cash";
    private static final String CANCEL = ROOT + "order-rvsecncl";
    private static final String CANCELABLE = ROOT + "inquire-psbl-rvsecncl";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGES = 100;
    private final KisMarketDataClient paperClient;
    private final KisPaperAccountSettings paperSettings;
    private final KisExecutionSettings settings;
    private final Clock clock;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc){this.jdbc=jdbc;}

    public KisExecutionBrokerClient(KisMarketDataClient paperClient, KisPaperAccountSettings paperSettings,
            KisExecutionSettings settings, ObjectMapper mapper, Clock clock) {
        this.paperClient = paperClient; this.paperSettings = paperSettings; this.settings = settings; this.clock = clock;
    }

    @Override public boolean configured(Environment environment) {
        requireEnvironment(environment);
        return settings.identityAvailable() && paperClient.executionEnvironment() == environment && paperClient.configured()
                && (environment == Environment.PAPER ? paperSettings.configured() : settings.configured());
    }

    @Override public boolean liveArmed() { return false; }

    @Override public String accountKey(Environment environment) {
        requireConfigured(environment);
        AccountCredentials credentials = credentials(environment);
        return settings.accountFingerprint(environment, credentials.number, credentials.product);
    }

    @Override public Account account(Environment environment) {
        AccountCredentials account = credentials(environment);
        Map<String, String> params = accountParams(account);
        params.putAll(Map.of("AFHR_FLPR_YN", "N", "OFL_YN", "", "INQR_DVSN", "02", "UNPR_DVSN", "01",
                "FUND_STTL_ICLD_YN", "N", "FNCG_AMT_AUTO_RDPT_YN", "N", "PRCS_DVSN", "00"));
        List<JsonNode> pages = pages(environment, BALANCE, transaction(environment, "8434R"), params);
        Map<String, Holding> holdings = new LinkedHashMap<>();
        BigDecimal cash = null, assets = null;
        for (JsonNode page : pages) {
            JsonNode totals = page.path("output2");
            if (!totals.isArray() || totals.size() != 1) throw invalid("잔고 합계 검증 실패");
            BigDecimal pageCash = amount(totals.get(0), "dnca_tot_amt"), pageAssets = amount(totals.get(0), "tot_evlu_amt");
            if (cash != null && (cash.compareTo(pageCash) != 0 || assets.compareTo(pageAssets) != 0))
                throw invalid("잔고 조회 중 계좌 값이 변경됐습니다. 다시 조회해야 합니다.");
            cash = pageCash; assets = pageAssets;
            JsonNode rows = array(page, "output1");
            for (JsonNode row : rows) {
                int quantity = quantity(row, "hldg_qty");
                if (quantity == 0) continue; // KIS retains some completely sold holdings for two business days.
                String code = code(text(row, "pdno"));
                int available = quantity(row, "ord_psbl_qty");
                if (available > quantity) throw invalid("보유·매도 가능 수량 검증 실패");
                String name = text(row, "prdt_name");
                if (name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl))
                    throw invalid("보유 종목명 검증 실패");
                Holding holding = new Holding(code, name, quantity, available, amount(row, "pchs_avg_pric"), amount(row, "prpr"));
                if (holding.averagePrice().signum() <= 0) throw invalid("보유 매입 단가 검증 실패");
                Holding previous = holdings.putIfAbsent(code, holding);
                if (previous != null && !previous.equals(holding)) throw invalid("잔고 연속 조회 값 충돌");
            }
        }
        if (cash == null || assets == null) throw invalid("잔고 합계 미확인");
        return new Account(cash, assets, List.copyOf(holdings.values()), clock.instant());
    }

    @Override public BuyingPower buyingPower(Environment environment, String instrument, BigDecimal limitPrice) {
        code(instrument); price(limitPrice);
        Map<String, String> params = accountParams(credentials(environment));
        // Official documentation requires market type when checking quantities without margin financing.
        params.putAll(Map.of("PDNO", instrument, "ORD_UNPR", limitPrice.toBigIntegerExact().toString(), "ORD_DVSN", "01",
                "CMA_EVLU_AMT_ICLD_YN", "N", "OVRS_ICLD_YN", "N"));
        JsonNode output = query(environment, BUYING_POWER, transaction(environment, "8908R"), params, "").body().path("output");
        if (!output.isObject()) throw invalid("매수 가능 응답 미확인");
        BigDecimal cash = amount(output, "nrcvb_buy_amt");
        int brokerQuantity = quantity(output, "nrcvb_buy_qty");
        int cashQuantity = cash.divide(limitPrice, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValueExact();
        return new BuyingPower(cash, Math.min(brokerQuantity, cashQuantity));
    }

    @Override public Acknowledgement place(Environment environment, OrderRequest request){
        throw new NotSubmitted(); // Automatic/generic order entry is permanently retired.
    }
    @Override public Acknowledgement placeConfirmedPaperSale(long orderId,UUID intentId,OrderRequest request){
        try{
            if(jdbc==null||intentId==null||request==null||request.side()!=Side.SELL)throw new NotSubmitted();
            String scope=accountKey(Environment.PAPER);
            boolean approved=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM manual_sell_intent m JOIN execution_order o ON o.id=m.order_id
                WHERE m.id=? AND o.id=? AND m.scope_id=? AND o.scope_id=m.scope_id AND o.status='SUBMITTING'
                AND o.side='SELL' AND o.strategy_policy='MANUAL_PAPER_SELL_V1' AND o.exit_purpose='USER_REQUEST'
                AND o.decision_key=('MANUAL_SELL:' || m.id::text) AND m.stock_code=o.stock_code
                AND m.stock_code=? AND m.quantity=o.quantity AND m.quantity=? AND m.limit_price=o.limit_price AND m.limit_price=?
                AND m.consumed_at IS NOT NULL AND m.created_at<=? AND m.expires_at>?)
                """,Boolean.class,intentId,orderId,scope,request.code(),request.quantity(),request.limitPrice(),clock.instant().atOffset(ZoneOffset.UTC),clock.instant().atOffset(ZoneOffset.UTC)));
            if(!approved)throw new NotSubmitted();
        }catch(RuntimeException beforeDispatch){throw new NotSubmitted();}
        return placePaperSale(request);
    }
    private Acknowledgement placePaperSale(OrderRequest request){
        Map<String, String> body;
        try {
            requireWriteEnabled(Environment.PAPER);
            if(request==null||request.side()!=Side.SELL)throw invalid("직접 모의 매도 확인 필요");
            code(request.code());price(request.limitPrice());positiveQuantity(request.quantity());
            body=accountParams(credentials(Environment.PAPER));
            body.putAll(Map.of("PDNO",request.code(),"ORD_DVSN","00","ORD_QTY",String.valueOf(request.quantity()),
                "ORD_UNPR",request.limitPrice().toBigIntegerExact().toString(),"EXCG_ID_DVSN_CD","KRX",
                "SLL_TYPE","01","CNDT_PRIC",""));
        }catch(RuntimeException beforeDispatch){throw new NotSubmitted();}
        // Deliberately a single POST. Ambiguous results are reconciled, never resent.
        return acknowledgement(post(Environment.PAPER,PLACE,transaction(Environment.PAPER,"0011U"),body));
    }

    @Override public Acknowledgement cancel(Environment environment, Acknowledgement original, String instrument,
            int remainingQuantity, BigDecimal limitPrice) {
        Map<String, String> body;
        try {
            requireWriteEnabled(environment);
            if (original == null || original.date() == null || !original.date().equals(today()))
                throw invalid("당일 원주문 확인 필요");
            orderIdentifier(original.orderNumber(), false); identifier(original.organization(), false);
            code(instrument); positiveQuantity(remainingQuantity); price(limitPrice);
            if (environment == Environment.LIVE) requireCancelable(original, instrument, remainingQuantity);
            else {
                // The official cancelable-order query exposes a LIVE transaction only. PAPER is checked via current orders.
                List<BrokerOrder> matches = orders(environment, original.date(), original.date()).stream()
                        .filter(order -> numericEquals(order.orderNumber(), original.orderNumber()) && order.code().equals(instrument)).toList();
                if (matches.size() != 1) throw invalid("모의 원주문 조회 확인 필요");
                BrokerOrder order = matches.get(0);
                if (order.canceled() || order.rejected() || remainingQuantity > order.quantity() - order.filledQuantity())
                    throw invalid("모의 취소 가능 잔량 확인 필요");
            }
            body = accountParams(credentials(environment));
            body.putAll(Map.of("KRX_FWDG_ORD_ORGNO", original.organization(), "ORGN_ODNO", original.orderNumber(),
                    "ORD_DVSN", "00", "RVSE_CNCL_DVSN_CD", "02", "ORD_QTY", String.valueOf(remainingQuantity),
                    "ORD_UNPR", "0", "QTY_ALL_ORD_YN", "N", "EXCG_ID_DVSN_CD", "KRX"));
        } catch (RuntimeException beforeDispatch) { throw new NotSubmitted(); }
        return acknowledgement(post(environment, CANCEL, transaction(environment, "0013U"), body));
    }

    @Override public List<BrokerOrder> orders(Environment environment, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || to.isAfter(today()) || from.isBefore(today().minusMonths(3).plusDays(1)))
            throw invalid("최근 3개월 이내 주문 조회 기간 확인 필요");
        Map<String, String> params = accountParams(credentials(environment));
        params.putAll(Map.ofEntries(Map.entry("INQR_STRT_DT", from.format(DateTimeFormatter.BASIC_ISO_DATE)),
                Map.entry("INQR_END_DT", to.format(DateTimeFormatter.BASIC_ISO_DATE)), Map.entry("SLL_BUY_DVSN_CD", "00"),
                Map.entry("PDNO", ""), Map.entry("CCLD_DVSN", "00"), Map.entry("INQR_DVSN", "01"),
                Map.entry("INQR_DVSN_3", "00"), Map.entry("ORD_GNO_BRNO", ""), Map.entry("ODNO", ""),
                Map.entry("INQR_DVSN_1", ""), Map.entry("EXCG_ID_DVSN_CD", environment == Environment.PAPER ? "KRX" : "ALL")));
        Map<String, BrokerOrder> result = new LinkedHashMap<>();
        for (JsonNode page : pages(environment, ORDERS, transaction(environment, "0081R"), params)) {
            for (JsonNode row : array(page, "output1")) {
                BrokerOrder order = parseOrder(row);
                if (order.date().isBefore(from) || order.date().isAfter(to)) throw invalid("주문 기준일 검증 실패");
                String identity = order.date() + ":" + order.orderNumber();
                BrokerOrder previous = result.putIfAbsent(identity, order);
                if (previous != null && !previous.equals(order)) throw invalid("주문 연속 조회 값 충돌");
            }
        }
        return List.copyOf(result.values());
    }

    BrokerOrder parseOrder(JsonNode row) {
        String number = orderIdentifier(text(row, "odno"), false);
        String original = orderIdentifier(text(row, "orgn_odno"), true);
        String organization = text(row, "ord_orgno");
        if (organization.isBlank() || organization.matches("0+")) organization = text(row, "ord_gno_brno");
        identifier(organization, false);
        String sideValue = text(row, "sll_buy_dvsn_cd");
        Side side = switch (sideValue) { case "01" -> Side.SELL; case "02" -> Side.BUY; default -> throw invalid("매도·매수 구분 검증 실패"); };
        int quantity = quantity(row, "ord_qty"), filled = quantity(row, "tot_ccld_qty"), remaining = quantity(row, "rmn_qty");
        int canceled = confirmedCancellationQuantity(row), rejected = quantity(row, "rjct_qty");
        BigDecimal filledAmount = amount(row, "tot_ccld_amt"), limit = amount(row, "ord_unpr");
        if (quantity < 1 || filled > quantity || remaining > quantity - filled || canceled > quantity || rejected > quantity
                || (long) filled + remaining + canceled + rejected > quantity
                || filled == 0 && filledAmount.signum() != 0 || filled > 0 && filledAmount.signum() <= 0)
            throw invalid("주문·체결 수량과 금액 검증 실패");
        String flag = text(row, "cncl_yn");
        if (!Set.of("Y", "N").contains(flag)) throw invalid("취소 상태 미확인");
        boolean terminal = remaining == 0 && filled + canceled + rejected == quantity;
        boolean canceledTerminal = terminal && canceled > 0 && flag.equals("Y");
        boolean rejectedTerminal = terminal && rejected > 0;
        return new BrokerOrder(number, original, organization, code(text(row, "pdno")), side, quantity, filled,
                filledAmount, limit, canceledTerminal, rejectedTerminal, date(text(row, "ord_dt")), time(text(row, "ord_tmd")));
    }

    private static int confirmedCancellationQuantity(JsonNode row) {
        // Today's PAPER payload uses cncl_cfrm_qty; the official sample mapping uses
        // cnc_cfrm_qty. Accept either explicitly, but never invent zero or accept conflicts.
        boolean current=row.hasNonNull("cncl_cfrm_qty"), documented=row.hasNonNull("cnc_cfrm_qty");
        if(!current&&!documented)throw invalid("취소 확인 수량 응답 미확인");
        int value=quantity(row,current?"cncl_cfrm_qty":"cnc_cfrm_qty");
        if(current&&documented&&value!=quantity(row,"cnc_cfrm_qty"))
            throw invalid("취소 확인 수량 응답 충돌");
        return value;
    }

    private void requireCancelable(Acknowledgement original, String instrument, int requested) {
        Map<String, String> params = accountParams(credentials(Environment.LIVE));
        params.putAll(Map.of("INQR_DVSN_1", "0", "INQR_DVSN_2", "0"));
        JsonNode found = null;
        for (JsonNode page : pages(Environment.LIVE, CANCELABLE, "TTTC0084R", params)) {
            for (JsonNode row : array(page, "output")) {
                if (numericEquals(original.orderNumber(), text(row, "odno")) && instrument.equals(text(row, "pdno"))) {
                    if (found != null) throw invalid("취소 가능 주문 조회 중복");
                    found = row;
                }
            }
        }
        if (found == null || quantity(found, "psbl_qty") < requested) throw invalid("증권사 취소 가능 수량 확인 필요");
    }

    private List<JsonNode> pages(Environment environment, String path, String transactionId, Map<String, String> base) {
        List<JsonNode> result = new ArrayList<>();
        Map<String, String> params = new HashMap<>(base);
        params.put("CTX_AREA_FK100", ""); params.put("CTX_AREA_NK100", "");
        String continuation = "";
        Set<String> cursors = new HashSet<>();
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            KisMarketDataClient.Page page = query(environment, path, transactionId, params, continuation);
            result.add(page.body());
            String status = page.continuation().strip();
            // Missing cursors are malformed, even on the last page; never silently accept a truncated body.
            String fk = cursor(page.body(), "ctx_area_fk100"), nk = cursor(page.body(), "ctx_area_nk100");
            if (Set.of("D", "E").contains(status)) return List.copyOf(result);
            if (status.isEmpty()) {
                if (!nk.isBlank()) throw invalid("연속 조회 헤더 미확인");
                return List.copyOf(result);
            }
            if (!Set.of("M", "F").contains(status)) throw invalid("연속 조회 상태 검증 실패");
            // KIS cursors are opaque fixed-width strings; preserve padding rather than normalizing them.
            if (fk.length() > 100 || nk.length() > 100 || nk.isBlank() || !cursors.add(fk + "\u0000" + nk))
                throw invalid("연속 조회 커서 검증 실패");
            params.put("CTX_AREA_FK100", fk); params.put("CTX_AREA_NK100", nk); continuation = "N";
        }
        // Never return a truncated list as though it were a complete reconciliation snapshot.
        throw invalid("주문·잔고 연속 조회 한도 초과: 전체 확인 필요");
    }

    private KisMarketDataClient.Page query(Environment environment, String path, String transactionId, Map<String, String> params, String continuation) {
        requireConfigured(environment);
        return paperClient.executionQuery(environment, path, transactionId, params, continuation);
    }
    private JsonNode post(Environment environment, String path, String transactionId, Map<String, String> params) {
        try { requireWriteEnabled(environment); } catch (RuntimeException beforeDispatch) { throw new NotSubmitted(); }
        return paperClient.executionPost(environment, path, transactionId, params).body();
    }
    private Acknowledgement acknowledgement(JsonNode response) {
        JsonNode output = response.path("output");
        if (!output.isObject()) throw invalid("주문 접수 결과 미확인: 주문 조회가 필요합니다.");
        return new Acknowledgement(orderIdentifier(text(output, "ODNO"), false), identifier(text(output, "KRX_FWDG_ORD_ORGNO"), false),
                today(), time(text(output, "ORD_TMD")));
    }
    private AccountCredentials credentials(Environment environment) {
        requireConfigured(environment);
        if (environment == Environment.PAPER) {
            KisPaperAccountSettings.Credentials value = paperSettings.credentials();
            if (value == null) throw invalid("모의계좌 설정 필요");
            return new AccountCredentials(value.number(), value.product());
        }
        KisExecutionSettings.Credentials value = settings.credentials();
        return new AccountCredentials(value.number(), value.product());
    }
    private record AccountCredentials(String number, String product) {
        @Override public String toString() { return "ExecutionAccount[REDACTED]"; }
    }
    private Map<String, String> accountParams(AccountCredentials credentials) {
        Map<String, String> params = new HashMap<>(); params.put("CANO", credentials.number); params.put("ACNT_PRDT_CD", credentials.product); return params;
    }
    private String transaction(Environment environment, String suffix) { return (environment == Environment.PAPER ? "VTTC" : "TTTC") + suffix; }
    private LocalDate today() { return clock.instant().atZone(SEOUL).toLocalDate(); }
    private void requireEnvironment(Environment environment) { if (environment == null) throw invalid("주문 환경 확인 필요"); }
    private void requireConfigured(Environment environment) { if (!configured(environment)) throw invalid("선택한 주문 환경의 API·계좌 설정 필요"); }
    private void requireWriteEnabled(Environment environment) {
        requireConfigured(environment);
        if (environment == Environment.LIVE && !liveArmed()) throw invalid("실전 주문은 명시적으로 활성화되지 않았습니다.");
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
    private static String text(JsonNode node, String field) { return node.path(field).asText("").strip(); }
    private static String cursor(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw invalid("연속 조회 커서 미확인");
        String text = value.asText();
        if (text.length() > 100 || text.chars().anyMatch(character -> character < 32 || character > 126))
            throw invalid("연속 조회 커서 검증 실패");
        return text;
    }
    private static JsonNode array(JsonNode node, String field) { JsonNode rows = node.path(field); if (!rows.isArray()) throw invalid("증권사 목록 응답 미확인"); return rows; }
    private static String code(String value) { if (value == null || !value.matches("[0-9]{6}")) throw invalid("종목 코드 확인 필요"); return value; }
    private static String orderIdentifier(String value, boolean optional) {
        String result = identifier(value, optional);
        if (result.length() > 10) throw invalid("주문번호 형식 검증 실패");
        return result;
    }
    private static String identifier(String value, boolean optional) {
        if (optional && (value.isEmpty() || value.matches("0+"))) return "";
        if (!value.matches("[0-9]{1,20}") || value.matches("0+")) throw invalid("증권사 주문 식별 응답 미확인"); return value;
    }
    private static boolean numericEquals(String left, String right) {
        return left != null && right != null && left.matches("[0-9]{1,20}") && right.matches("[0-9]{1,20}")
                && new java.math.BigInteger(left).equals(new java.math.BigInteger(right));
    }
    private static void positiveQuantity(int value) { if (value < 1 || value > 10_000_000) throw invalid("주문 수량 확인 필요"); }
    private static int quantity(JsonNode node, String field) {
        String value = text(node, field); if (!value.matches("[0-9]{1,9}")) throw invalid("증권사 수량 응답 미확인");
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { throw invalid("증권사 수량 범위 초과"); }
    }
    private static BigDecimal amount(JsonNode node, String field) {
        String value = text(node, field); if (!value.matches("[0-9]{1,18}(\\.[0-9]{1,8})?")) throw invalid("증권사 금액 응답 미확인");
        return new BigDecimal(value);
    }
    private static void price(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(1_000_000_000)) > 0
                || value.stripTrailingZeros().scale() > 0) throw invalid("KRX 지정가 주문 가격 확인 필요");
    }
    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{8}")) throw invalid("증권사 주문 날짜 미확인");
        try { return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE); } catch (DateTimeException ignored) { throw invalid("증권사 주문 날짜 검증 실패"); }
    }
    private static LocalTime time(String value) {
        if (!value.matches("[0-9]{6}")) throw invalid("증권사 주문 시각 미확인");
        try { return LocalTime.parse(value, DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)); }
        catch (DateTimeException ignored) { throw invalid("증권사 주문 시각 검증 실패"); }
    }

}
