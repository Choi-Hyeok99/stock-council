package com.personaltrader.education;

import com.personaltrader.marketdata.UniverseService;
import com.personaltrader.marketdata.WatchlistQuoteCollector;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@Service
public class KnowledgeReview {
    private final InvestmentKnowledge knowledge;
    private final com.personaltrader.research.PaperPortfolioPolicy policy;
    private final com.personaltrader.research.PublicInformationService information;
    public KnowledgeReview(InvestmentKnowledge knowledge, com.personaltrader.research.PaperPortfolioPolicy policy, com.personaltrader.research.PublicInformationService information) { this.knowledge = knowledge; this.policy=policy; this.information=information; }

    public Review review(UniverseService.Snapshot selection, List<WatchlistQuoteCollector.StockView> stocks, Instant now,
                         com.personaltrader.marketdata.MarketIndexCollector.Snapshot indices) {
        var today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        boolean history = !stocks.isEmpty() && stocks.stream().allMatch(view -> view.stock().history().completedSessions() >= 20
                && view.stock().history().basisDate().isBefore(today));
        boolean fresh = selection.usable() && stocks.stream().anyMatch(WatchlistQuoteCollector.StockView::fresh);
        boolean limits = !stocks.isEmpty() && stocks.stream().allMatch(view -> view.quantity() >= 0
                && view.estimatedLoss().signum() >= 0 && (!policy.expectedLossSizingEnabled() || view.estimatedLoss().compareTo(policy.lossLimit()) <= 0)
                && view.price().multiply(BigDecimal.valueOf(view.quantity())).compareTo(policy.positionLimit()) <= 0);
        return new Review(knowledge.curriculum().version(), knowledge.hash(), "REFERENCE_REVIEW_ONLY", List.of(
            new Check("확정 일봉", history ? "PASS" : "WAIT", "20개 이상 · 오늘 형성 중인 봉 제외 여부", List.of("ASOF", "MULTI_INPUT")),
            new Check("거래용 시세", fresh ? "PASS" : "WAIT", "선정 유효성 및 날짜·시각을 확인한 신선한 체결 시세", List.of("ASOF", "UNCERTAINTY")),
            new Check("최신 시장 지수", indices.indices().stream().filter(index -> index.code().equals("0001")).anyMatch(index -> index.fresh()) ? "PASS" : "WAIT",
                    "KOSPI 응답의 실제 기준일·시각과 수신 시각 120초 이내 · 확정 일봉 평균과 분리 · 날짜만 있으면 거래 관문 대기", List.of("ASOF", "UNCERTAINTY")),
            new Check("관찰 제안의 수량·배정 한도", limits ? "PASS" : stocks.isEmpty() ? "WAIT" : "REJECT", "종목 상한 "+policy.positionLimit().toPlainString()+"원 · "+(policy.expectedLossSizingEnabled()?"예상손실 상한 "+policy.lossLimit().toPlainString()+"원":"모의 예상손실 수량 제한 해제 · 최초 매수 총액150만원 이상 · 일반300만원/차트·재무 강화 시500만원")+". 실제 주문의 보유 합산·현금·비용 검사는 별도", List.of("RISK_FIRST", "DISCIPLINE")),
            new Check("사업·재무 평가", "UNKNOWN", "DART 재무 수치·가치 가정 연결 · 사업 경쟁력/감사/경영 판단과 장기 성과 검증 미완료", List.of("UNDERSTAND")),
            new Check("뉴스·공시", information.healthy()?"TITLE_ONLY":"UNKNOWN", "실제 RSS 제목·링크를 관찰 · 기사 본문/사업 영향 미검증 · 긍정 제목만으로 매수하지 않음", List.of("UNCERTAINTY", "PUBLIC_ONLY")),
            new Check("계좌 비용·거래일", "UNKNOWN", "계좌 실제 적용 요율 대조 대기 · 공식 월별 거래 달력 갱신 상태는 운영 화면에서 별도 확인", List.of("COSTS", "ORDER_REALITY")),
            new Check("집중 위험", "REFERENCE_ONLY", "최대 "+policy.maxPositions()+"종목 · 업종/위험 상관관계 미검증 · 종목 수만으로 분산 충분성 확정 금지", List.of("DIVERSIFY")),
            new Check("버크셔 검토·모의 실행 분리", "RESEARCH_ONLY", "사용자 선택: 모의 신규·추가 매수 허용 · 기존 실행 관문과 위험 한도 유지 · 기업가치 검토를 매수 허가로 승격하지 않음", List.of("PATIENT_CAPITAL", "THESIS_FIRST")),
            new Check("학습·규칙 변경", "OFF", "자료 검토만 수행 · 모델 훈련 및 운영 전략 자동 승격 없음", List.of("ECONOMIC_REASON", "NO_AUTO_PROMOTION"))
        ));
    }
    public record Review(String curriculumVersion, String contentHash, String mode, List<Check> checks) {}
    public record Check(String name, String state, String detail, List<String> principleIds) {}
}
