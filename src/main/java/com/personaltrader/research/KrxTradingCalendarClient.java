package com.personaltrader.research;

import org.springframework.stereotype.Component;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Public KRX KIND stock trading calendar. Never sends broker credentials or executes page scripts. */
@Component
public class KrxTradingCalendarClient {
    public static final String SOURCE = "https://kind.krx.co.kr/common/stockschedule.do?method=StockScheduleMain&index=13";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public CalendarMonth fetch(YearMonth month) {
        try {
            URI uri = URI.create("https://kind.krx.co.kr/common/stockschedule.do?method=searchTraidingCalendar"
                + "&forward=searchtraidingcalendar&selYear=" + month.getYear() + "&selMonth="
                + String.format(Locale.ROOT, "%02d", month.getMonthValue()) + "&searchMenu=13&periodType=M");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "PersonalTrader/0.1 (private calendar lookup)")
                .header("Accept", "text/html").GET().build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var body = response.body()) {
                if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").contains("text/html"))
                    throw new IllegalStateException("공식 달력 응답 형식 확인 필요");
                bytes = body.readNBytes(300_001);
            }
            if (bytes.length == 0 || bytes.length > 300_000) throw new IllegalStateException("공식 달력 응답 크기 확인 필요");
            String html = new String(bytes, StandardCharsets.UTF_8);
            List<Day> days = parse(month, html);
            return new CalendarMonth(month, days, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("KRX KIND 거래 달력 조회·월별 날짜 검증 실패");
        }
    }

    static List<Day> parse(YearMonth month, String html) throws Exception {
        List<String> cells = new ArrayList<>(), headers = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            boolean calendar, td, th;
            StringBuilder value;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attr, int pos) {
                if (tag == HTML.Tag.DIV && "calBig".equals(attr.getAttribute(HTML.Attribute.ID))) calendar = true;
                if (calendar && (tag == HTML.Tag.TD || tag == HTML.Tag.TH)) {
                    td = tag == HTML.Tag.TD; th = tag == HTML.Tag.TH; value = new StringBuilder();
                }
            }
            @Override public void handleText(char[] text, int pos) {
                if (calendar && (td || th)) value.append(text).append(' ');
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (calendar && tag == HTML.Tag.TD && td) { cells.add(value.toString().strip()); td = false; }
                if (calendar && tag == HTML.Tag.TH && th) { headers.add(value.toString().strip()); th = false; }
                if (tag == HTML.Tag.DIV && calendar) calendar = false;
            }
        }, true);
        if (!headers.equals(List.of("일", "월", "화", "수", "목", "금", "토")) || cells.size() < 28
                || cells.size() > 42 || cells.size() % 7 != 0) throw new IllegalArgumentException("달력 표 확인 필요");
        int offset = month.atDay(1).getDayOfWeek().getValue() % 7;
        List<Day> result = new ArrayList<>();
        var number = Pattern.compile("^([0-9]{1,2})(?:\\s+(.*))?$", Pattern.DOTALL);
        for (int i = 0; i < cells.size(); i++) {
            String text = cells.get(i).replaceAll("\\s+", " ").strip();
            int expected = i - offset + 1;
            if (expected < 1 || expected > month.lengthOfMonth()) {
                if (!text.isEmpty()) throw new IllegalArgumentException("다른 월 날짜 혼입");
                continue;
            }
            var match = number.matcher(text);
            if (!match.matches() || Integer.parseInt(match.group(1)) != expected)
                throw new IllegalArgumentException("날짜 누락·중복·배열 불일치");
            String event = match.group(2) == null ? "" : match.group(2).strip();
            if (event.length() > 160) throw new IllegalArgumentException("달력 일정 길이 확인 필요");
            LocalDate date = month.atDay(expected);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            String compact = event.replace(" ", "");
            boolean holiday = !event.isEmpty() && List.of("신정", "설날", "삼일절", "3·1절", "어린이날", "부처님오신날",
                "석가탄신일", "현충일", "광복절", "추석", "개천절", "한글날", "대체휴일", "대체공휴일", "임시공휴일",
                "선거", "근로자의날", "성탄절", "크리스마스", "연말휴장", "연말휴일").stream().anyMatch(compact::contains);
            // A special session or an unfamiliar event is not guessed to be an ordinary open day.
            boolean reviewed = event.isEmpty() || holiday;
            result.add(new Day(date, weekend || holiday, !event.isEmpty() ? event : weekend ? "주말" : "정규 거래일", reviewed));
        }
        if (result.size() != month.lengthOfMonth()) throw new IllegalArgumentException("월 전체 날짜 미확인");
        Map<LocalDate, String> reviewedClosures = Map.of(LocalDate.of(2026, 10, 5), "대체휴일", LocalDate.of(2026, 10, 9), "한글날");
        for (Day day : result) if (reviewedClosures.containsKey(day.date()) && (!day.closed()
                || !day.reason().replace(" ", "").contains(reviewedClosures.get(day.date()))))
            throw new IllegalArgumentException("기존 공식 휴장 공지와 충돌");
        return List.copyOf(result);
    }
    public record Day(LocalDate date, boolean closed, String reason, boolean scheduleReviewed) {}
    public record CalendarMonth(YearMonth month, List<Day> days, String contentHash) {}
}
