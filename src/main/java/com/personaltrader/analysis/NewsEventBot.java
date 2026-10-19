package com.personaltrader.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
@Profile("paper")
public class NewsEventBot {
    private final boolean paperObservation;
    private final com.personaltrader.research.PublicInformationService information;
    public NewsEventBot(@Value("${execution.environment:PAPER}") String environment,
                        @Value("${execution.paper-news-observation:true}") boolean observation, com.personaltrader.research.PublicInformationService information) {
        this.information=information;
        this.paperObservation="PAPER".equalsIgnoreCase(environment)&&observation;
    }
    public BotStatus summarize() {
        return new BotStatus(
                "News & Event Bot",
                information.healthy()?"TITLE_ONLY":"UNKNOWN",
                information.healthy()?"실제 RSS 제목·공시 피드 수집 · 본문 해석 대기":"기사·공시 수집 상태 확인 대기",
                paperObservation ? "모의 연구 실험: 명확히 연결된 위험/정정 제목은 보류하며 차트·시장·위험 관문으로 판단합니다. 제목 분류는 실제 뉴스 CLEAR가 아닙니다."
                        : "최신 뉴스·공시 확인 전 신규 매수를 허용하지 않습니다."
        );
    }
}
