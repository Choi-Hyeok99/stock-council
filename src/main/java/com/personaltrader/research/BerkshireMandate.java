package com.personaltrader.research;

import com.personaltrader.broker.ExecutionBrokerClient.Environment;

/** Current research mandate; historical snapshots keep their original mandate version. */
public final class BerkshireMandate {
    public static final String VERSION = "BERKSHIRE_RESEARCH_ASSISTANT_V3";
    public static final String REASON = "자동 매수·매도·취소 없음 · 기업 연구와 사용자 확인 직접 모의 매도를 분리";
    private BerkshireMandate() {}
    public static boolean entriesPaused(Environment environment) { return true; }
}
