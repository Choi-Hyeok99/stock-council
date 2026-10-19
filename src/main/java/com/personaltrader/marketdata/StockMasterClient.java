package com.personaltrader.marketdata;

import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.Charset;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;

@Component
public class StockMasterClient {
    // KIS official stocks_info field specifications. ASCII tail excludes the line terminator.
    private static final int[] KOSPI = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1,2,1,1,1,2,2,2,3,1,3,12,12,8,15,21,2,7,1,1,1,1,1,9,9,9,5,9,8,9,3,1,1,1};
    private static final int[] KOSDAQ = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1,2,1,1,1,2,2,2,3,1,3,12,12,8,15,21,2,7,1,1,1,1,9,9,9,5,9,8,9,3,1,1,1};
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private Map<String, MasterStock> cache = Map.of();
    private LocalDate fetchedDate;
    public StockMasterClient(Clock clock) { this.clock = clock; }

    public synchronized Map<String, MasterStock> load() {
        LocalDate today = clock.instant().atZone(TimedMarketQuote.SEOUL).toLocalDate();
        if (today.equals(fetchedDate)) return cache;
        try {
            Map<String, MasterStock> combined = new HashMap<>();
            combined.putAll(download("kospi", KOSPI)); combined.putAll(download("kosdaq", KOSDAQ));
            if (combined.size() < 1000) throw new IllegalArgumentException("종목 마스터 부족");
            cache = Map.copyOf(combined); fetchedDate = today; return cache;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("종목 마스터 수집 중단");
        } catch (Exception exception) {
            throw new IllegalStateException("KIS 공식 종목 마스터 확인 실패");
        }
    }

    private Map<String, MasterStock> download(String market, int[] widths) throws Exception {
        URI uri = URI.create("https://new.real.download.dws.co.kr/common/master/" + market + "_code.mst.zip");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 4_000_000) throw new IllegalArgumentException();
        byte[] body = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ((market + "_code.mst").equals(entry.getName())) {
                    body = zip.readNBytes(8_000_001);
                    if (body.length > 8_000_000) throw new IllegalArgumentException();
                    break;
                }
            }
        }
        if (body == null) throw new IllegalArgumentException();
        return parse(new String(body, Charset.forName("MS949")), market, widths);
    }

    static Map<String, MasterStock> parse(String contents, String market, int[] widths) {
        int tailWidth = Arrays.stream(widths).sum();
        Map<String, MasterStock> result = new HashMap<>();
        for (String line : contents.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            if (line.length() < tailWidth + 22) throw new IllegalArgumentException("종목 마스터 길이 변경");
            String code = line.substring(0, 9).strip();
            if (!code.matches("[0-9]{6}")) continue;
            String name = line.substring(21, line.length() - tailWidth).strip();
            String tail = line.substring(line.length() - tailWidth);
            List<String> flags = new ArrayList<>(); int position = 0;
            for (int width : widths) { flags.add(tail.substring(position, position + width).strip()); position += width; }
            String reason = exclusion(flags, market);
            if (name.isBlank() || result.putIfAbsent(code, new MasterStock(code, name,
                    market.equals("kospi") ? "KOSPI" : "KOSDAQ", reason)) != null) throw new IllegalArgumentException("종목 마스터 식별 오류");
        }
        return result;
    }

    private static String exclusion(List<String> f, String market) {
        boolean kospi = market.equals("kospi");
        if (!f.get(0).equals("ST")) return "보통주 대상 밖(ETF·ETN 등)";
        if (!f.get(kospi ? 54 : 49).equals("0")) return "우선주 또는 구분 미확인";
        if (!f.get(kospi ? 19 : 14).equals("N")) return "SPAC 또는 구분 미확인";
        if (!f.get(kospi ? 12 : 8).isBlank() && !f.get(kospi ? 12 : 8).equals("0")) return "ETP 상품 제외";
        if (!f.get(6).equals("N")) return "저유동성 또는 상태 미확인";
        if (!f.get(kospi ? 22 : 17).equals("0")) return "단기 과열 또는 상태 미확인";
        if (!kospi && !f.get(20).equals("N")) return "투자주의 환기 또는 상태 미확인";
        for (int index : kospi ? new int[]{34,35,36,38,39,40,56} : new int[]{29,30,31,33,34,35,51})
            if (!f.get(index).equals("N")) return "거래정지·관리·경고예고 등 위험 상태 또는 미확인";
        if (!f.get(kospi ? 37 : 32).equals("00")) return "시장경고 또는 상태 미확인";
        return null;
    }
    public record MasterStock(String code, String name, String market, String exclusionReason) {
        public boolean allowed() { return exclusionReason == null; }
    }
}
