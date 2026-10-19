package com.personaltrader.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("paper")
public class InvestorReferenceBot {
    private final com.personaltrader.education.InvestmentKnowledge knowledge;
    public InvestorReferenceBot(com.personaltrader.education.InvestmentKnowledge knowledge) { this.knowledge = knowledge; }
    public BotStatus summarize() {
        return new BotStatus(
                "Investor Playbook",
                "REFERENCE_ONLY",
                "버크셔 원칙 · 기업 근거 검토와 모의 주문 판단 분리",
                "이해 가능한 사업·현금·합리적 가격·경영·보유 가설 점검 · DART 재무/가치 가정 연결, 경쟁력·경영 검증 대기 · " + knowledge.curriculum().version()
        );
    }
}
