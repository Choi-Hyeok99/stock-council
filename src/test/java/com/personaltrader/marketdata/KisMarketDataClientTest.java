package com.personaltrader.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class KisMarketDataClientTest {
    @Test void cachesTokenInMemoryAndOnlyUsesAllowlistedReadOnlyPaths() {
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T05:32:36Z"), ZoneOffset.UTC);
        var requests = new ArrayList<HttpRequest>();
        var client = new KisMarketDataClient(mapper, "test-key", "test-secret", clock, request -> {
            requests.add(request);
            return switch (request.uri().getPath()) {
                case "/oauth2/tokenP" -> mapper.readTree("{\"access_token\":\"test-token\",\"expires_in\":86400}");
                case "/uapi/domestic-stock/v1/quotations/inquire-ccnl" -> mapper.readTree("{\"rt_cd\":\"0\",\"output\":[{\"stck_cntg_hour\":\"143232\",\"stck_prpr\":\"274750\"}]}");
                case "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice" -> mapper.readTree("{\"rt_cd\":\"0\",\"output2\":[{\"stck_bsop_date\":\"20261002\",\"stck_cntg_hour\":\"143200\",\"stck_prpr\":\"274500\"}]}");
                default -> throw new AssertionError("Unexpected path");
            };
        });
        client.collect("005930"); client.collect("005930");
        assertThat(requests).hasSize(5);
        assertThat(requests.stream().filter(r -> r.method().equals("POST")).count()).isEqualTo(1);
        assertThat(requests.stream().filter(r -> r.method().equals("GET")).count()).isEqualTo(4);
        assertThat(requests).allSatisfy(r -> assertThat(r.uri().getHost()).isEqualTo("openapivts.koreainvestment.com"));
        assertThat(requests.get(2).uri().getQuery()).contains("FID_INPUT_HOUR_1=143236", "FID_PW_DATA_INCU_YN=Y");
    }
    @Test void rejectsUnconfiguredAndInvalidSymbolBeforeNetworkRequest() {
        var mapper = new ObjectMapper();
        var blank = new KisMarketDataClient(mapper, "", "", Clock.systemUTC(), request -> { throw new AssertionError(); });
        assertThatThrownBy(() -> blank.collect("005930")).hasMessageContaining("키 설정");
        var configured = new KisMarketDataClient(mapper, "test", "test", Clock.systemUTC(), request -> { throw new AssertionError(); });
        assertThatThrownBy(() -> configured.collect("005930&other=1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsApiErrorsWithoutExposingRawResponseOrTransportDetails() {
        var mapper = new ObjectMapper();
        var client = new KisMarketDataClient(mapper, "test", "test", Clock.systemUTC(), request -> {
            if (request.method().equals("POST")) return mapper.readTree("{\"access_token\":\"test-token\",\"expires_in\":86400}");
            throw new IllegalStateException("private transport details");
        });
        assertThatThrownBy(() -> client.collect("005930")).hasMessageContaining("조회에 실패").hasMessageNotContaining("private transport details");
    }
}
