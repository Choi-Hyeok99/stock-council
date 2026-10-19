package com.personaltrader.reports;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ReportMarkdown {
    private ReportMarkdown() {}
    private static String time(java.time.Instant at) { return at == null ? "미확인" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'").format(at.atZone(ZoneId.of("Asia/Seoul"))); }
    private static String text(String value) { return value == null ? "미확인" : value.replace("\r", " ").replace("\n", " ").replace("|", " / "); }
    private static String money(java.math.BigDecimal value) { return value == null ? "미확인" : value.toPlainString() + "원"; }
    public static String render(DailyReportRepository.Entry entry) {
        DailyReport r = entry.report(); StringBuilder out = new StringBuilder();
        boolean brokerExecution = r.version()!=null && r.version().startsWith("BROKER_EXECUTION_");
        out.append("# ").append(r.reportDate()).append(" · ").append(text(r.title())).append("\n\n")
           .append("- 보고서 ID: ").append(entry.id()).append("\n- 예약: ").append(time(r.scheduledAt()))
           .append("\n- 작성·집계 종료: ").append(time(r.generatedAt())).append("\n- 집계 시작: ").append(time(r.windowStart()))
           .append("\n- 작성 상태: ").append(r.timing()).append("\n- 시장 일정: ").append(r.marketPhase()).append("\n\n")
           .append(brokerExecution ? "## 증권사 체결 확인 성과 · 비용 추정\n\n- 증권사 예수금: " : "## 내부 모의 성과\n\n- 현금: ")
           .append(money(r.account().cash())).append("\n- 누적 실현 순손익: ").append(money(r.account().realizedNetPnl()))
           .append("\n- 오늘 종료 거래: ").append(r.today().closedTrades()).append("건\n- 오늘 실현 순손익: ").append(money(r.today().netPnl()))
           .append(brokerExecution ? "\n- 오늘 수수료 / 거래세 추정: " : "\n- 오늘 수수료 / 거래세 / 슬리피지: ")
           .append(money(r.today().commission())).append(" / ").append(money(r.today().transactionTax()));
        if (!brokerExecution) out.append(" / ").append(money(r.today().slippage()));
        out.append("\n\n");
        for (var period : r.periods()) out.append("- ").append(period.label()).append(": ").append(money(period.netPnl())).append(" / 종료 거래 ").append(period.closedTrades()).append("건\n");
        out.append(brokerExecution ? "\n## 한국투자 계좌 잔고 조회\n\n" : "\n## 한국투자 모의 잔고 조회\n\n");
        if (r.broker() == null) out.append("확인된 조회 기록 없음\n");
        else out.append("- 상태: ").append(r.broker().state()).append("\n- 잔고 시각: ").append(time(r.broker().receivedAt()))
                .append("\n- 총평가금액: ").append(r.broker().totalAssets() == null ? "미확인" : r.broker().totalAssets()+"원")
                .append("\n- 보고 작성 시 신선도: ").append(r.broker().fresh() ? "120초 이내" : "이전 기록 또는 미확인").append("\n");
        if(r.insights()!=null){var a=r.insights();out.append("\n## 계좌 성과·점검 (보고 작성 당시)\n\n")
            .append("- 잔고 기준: ").append(time(a.balanceAt())).append(a.balanceFresh()?" · 120초 이내":" · 이전 기록")
            .append("\n- 봇 평가 순손익 (비용 추정): ").append(money(a.unrealizedNetPnl())).append("\n");
            for(var p:a.periods())out.append("- ").append(p.label()).append(" · 실제 ").append(time(p.from())).append(" ~ ").append(time(p.through()))
             .append(" · 자산 변화 ").append(money(p.assetChange())).append(" · 등록 순입금 ").append(money(p.netFlow())).append(" · 조정 손익 ").append(money(p.profit()))
             .append(" · 수익률 ").append(p.percent()==null?"입출금 검토/기록 확인 대기":p.percent()+"%").append(" · ").append(p.state()).append("\n");
            for(var alert:a.alerts())out.append("- 확인: ").append(text(alert)).append("\n");
            out.append("\n입출금이 있으면 시간 가중 입출금 분모를 쓰는 Modified Dietz 근사값. 정산 비용은 별도 사용자 확인 기록이며 원 주문 비용을 덮어쓰지 않습니다.\n");
        }
        if(r.disclosureImpacts()!=null){out.append("\n## 주요 공시 표 관찰 (보고 작성 당시)\n\n");for(var d:r.disclosureImpacts().entries()){
            out.append("- ").append(text(d.name())).append(" · ").append(text(d.title())).append(" · ").append(time(d.at())).append(" · ").append(d.url()).append("\n");
            for(var f:d.fields())out.append("  - ").append(text(f.label())).append(": ").append(text(f.value())).append("\n");
            for(var x:d.analysis())out.append("  - 검토: ").append(text(x)).append("\n");}}
        out.append("\n## 판단 근거\n\n");
        if (r.meeting() == null) out.append("저장된 회의록 없음\n");
        else {
            out.append("회의 ID ").append(r.meeting().id()).append(" · ").append(time(r.meeting().occurredAt())).append("\n\n");
            for (var speaker : r.meeting().report().speakers()) out.append("- ").append(text(speaker.name())).append(" [").append(text(speaker.state())).append("]: ").append(text(speaker.summary())).append(" · ").append(text(speaker.detail())).append("\n");
            out.append("\n### 종목별 관찰\n\n");
            for (var stock : r.meeting().report().stocks()) out.append("- ").append(text(stock.name())).append(" (").append(text(stock.code())).append("): ").append(stock.price()).append("원 · ").append(text(stock.priceLabel()))
                    .append(" · 일봉 ").append(stock.dailyBasisDate()).append(" · ").append(text(stock.decision())).append(" · ").append(text(stock.reason())).append("\n");
            for (var stock : r.meeting().report().stocks()) {
                if (stock.selectionRationale() == null) continue;
                out.append("\n#### ").append(text(stock.name())).append(" 선정 근거\n\n- ")
                        .append(stock.selectionRationale().method()==null?"당시 조회 순위: ":"후보 탐색 순서: ").append(stock.selectionRationale().rankingRank())
                        .append("\n- 입력 수신: ").append(time(stock.selectionRationale().rankingReceivedAt())).append("\n");
                for (var check : stock.selectionRationale().checks()) out.append("- ").append(text(check.role())).append(" / ").append(text(check.title()))
                        .append(" [").append(text(check.state())).append("]: ").append(text(check.detail())).append("\n");
            }
            if (r.meeting().report().marketIndices() != null) {
                out.append("\n### 회의 당시 최신 지수\n\n");
                for (var index : r.meeting().report().marketIndices()) {
                    out.append("- ").append(index.name()).append(" [").append(index.state()).append("]: ").append(text(index.label()));
                    if (index.quote() != null) out.append(" · ").append(index.quote().value()).append(" · 기준일 ").append(index.quote().businessDate())
                            .append(" · 기준 시각 ").append(time(index.quote().basisAt())).append(" · 수신 ").append(time(index.quote().receivedAt()));
                    out.append("\n");
                }
            }
            if (r.meeting().report().education() != null) {
                out.append("\n### 교육 기준 점검\n\n");
                for (var check : r.meeting().report().education().checks()) out.append("- ").append(text(check.name())).append(" [").append(check.state()).append("]: ").append(text(check.detail())).append("\n");
            }
        }
        out.append("\n## 학습 준비\n\n- 장중 관찰 일수: ").append(r.learning().observationDays()).append("일\n- 종료 거래 / 손익 표본: ").append(r.learning().closedTrades()).append(" / ").append(r.learning().labeledTrades())
                .append("\n- 모델 학습 / 자동 전략 변경: 꺼짐\n\n");
        for (String note : r.learning().limitations()) out.append("- ").append(text(note)).append("\n");
        out.append("\n## 기준과 출처\n\n- 교육 버전: ").append(r.curriculum().version()).append("\n- 원본 SHA-256: ").append(r.curriculumHash()).append("\n\n");
        for (var source : r.curriculum().sources()) out.append("- [").append(text(source.publisher())).append(" · ").append(text(source.title())).append("](").append(source.url()).append(")\n");
        out.append("\n## 보고 한계\n\n"); for (String note : r.notes()) out.append("- ").append(text(note)).append("\n");
        out.append("\n## 다음 준비\n\n"); for (String action : r.nextActions()) out.append("- ").append(text(action)).append("\n");
        return out.toString();
    }
}
