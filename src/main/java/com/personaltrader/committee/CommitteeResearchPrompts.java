package com.personaltrader.committee;

/** User-facing research rules only; no provider, network, order or scheduling authority. */
final class CommitteeResearchPrompts {
    static String forPhase(InvestmentCommitteeService.Entry entry,CommitteeContract.Role role,String phase){
        return "SUMMARY".equals(phase)?summaryPrompt(entry):CommitteeContract.prompt(role)
            +CommitteeEvidenceCoverage.roleInstructions(entry.report(),role.key())
            +" 한국어 summary170자, facts/inferences/risks 각1개 권장·최대2개·항목120자, evidenceIds6개, changedReason150자 이내. "
            +"숨겨진 사고 과정 대신 사용자용 짧은 주장·질문·답변만 공개하세요. 출처ID는 evidenceIds 배열에만 넣으세요. summary/facts/reply 같은 짧은 문장에는 해시·ID·URL을 복사하지 마세요. 숫자에는 연도·연간/반기/분기와 연결/별도 기준을 붙이세요. quantitativeFacts는 연간, interimFacts는 중간 결산이므로 수치와 기간을 섞지 마세요.  "
            +"TITLE_ONLY는 제목만 읽은 자료입니다. PUBLISHER_SUMMARY는 발행처가 RSS에 제공한 요약이고 전체 본문이 아닙니다. PUBLISHER_EXCERPT는 발행처가 무료로 공개한 기사 본문 일부이며 전문·독립 검증이 아닙니다. source의 summaryExcerptTruncated/articleExcerptTruncated는 전달할 때 일부 생략됐음을 뜻합니다. businessEvidence는 공시 본문 일부를 실제 발췌한 것으로, 전체 문서/감사 검토나 표의 모든 숫자 검증은 아닙니다. 제공된 발췌가 있는데 사업/건전성 원문이 전혀 없다고 쓰지 마세요. 표의 단위·기간·열이 불명확하면 값을 단정하지 말고 그 부분을 특정하세요. 미확인 시세·본문·재무를 확인했다고 쓰지 마세요. 매매 기능이 없는 것을 기업의 투자 반대 근거로 삼지 마세요. "
            +"검토 대상은 "+entry.report().agenda()+". "+phasePrompt(role,phase);
    }
    private static String summaryPrompt(InvestmentCommitteeService.Entry e){return "실제 8명 최종 표의 사용자용 한국어 요약자. 표나 근거를 새로 만들지 마세요. 검토 대상은 "+e.report().agenda()+". summary250자 이내, reasons/cautions 각3개 이하·120자 이내, evidenceIds6개 이하. 서버 집계가6/8 이상일 때만 같은 안건의 REVIEW_BUY 또는 REVIEW_SELL 가능. 통과했어도 자료 부족이면 KEEP_WATCH/NO_ACTION 가능. 표 미완성/미달은 KEEP_WATCH/NO_ACTION. assessment는 CONDITIONAL_INTEREST(조건부 관심), UNATTRACTIVE(가격/사업 매력 부족), DATA_GAP(핵심 자료 부족), MIXED(찬반 혼재) 중 논거에 맞게 분류하세요. 이 분류는 매수 승인과 별개이며 미통과도 관심 후보일 수 있습니다. bullCase에는 가장 강한 상승 경로, bearCase에는 그 경로에 대한 가장 강한 반론, reconsiderWhen에는 무엇이 확인되거나 어떤 가정이 틀리면 판단을 바꿀지 각180자 이내로 적으세요. 긍정 논거가 없으면 없다고 명시하고 만들지 마세요. 합의한 사실, 끝까지 남은 이견, 그 이견을 해소할 공식 수치/공시를 요약하세요. DATA_GAP과 사업 매력 부족을 구분하고, 가진 자료로 가능한 판단과 핵심 누락 한 가지를 구체적으로 설명하세요. URL/상승 확률/없는 수치/주문 실행을 만들지 마세요. TITLE_ONLY는 제목만 확인한 자료입니다. 공개 근거와 해석을 구분하고 설명만 정리하세요. "+CommitteeContract.investmentMandate();}
    private static String phasePrompt(CommitteeContract.Role role,String phase){
        var target=CommitteeContract.ROLES.stream().filter(x->x.key().equals(CommitteeContract.questionTarget(role.key()))).findFirst().orElseThrow();
        String common="질문 상대의 담당은 "+target.focus()+"입니다. 상대 분야에 맞는 질문을 하고 재무 질문을 기술·뉴스 역할에 반복 떠넘기지 마세요. discussion.changeCondition에는 내 판단을 바꿀 관측 조건160자 이내, counterpoint에는 가장 강한 반대 주장과 수용/반박 여부160자 이내를 적으세요. ";
        return common+switch(phase){
            case "INDEPENDENT" -> "독립 의견을 낸 뒤 "+CommitteeContract.questionTarget(role.key())+" 역할에게 내 가설의 약점을 검증할 구체적인 질문을 하세요. 상대의 발언을 보지 않았으므로 상대가 말했다고 꾸미지 마세요. discussion.questionTo는 "+CommitteeContract.questionTarget(role.key())+", question160자 이내, replyTo/reply는 빈 문자열. changedReason도 빈 문자열.";
            case "DEBATE" -> "독립 의견에서 "+CommitteeContract.questioner(role.key())+" 역할이 나에게 보낸 실제 질문을 찾아 직접 답하세요. discussion.replyTo는 "+CommitteeContract.questioner(role.key())+", reply200자 이내, questionTo/question은 빈 문자열. 질문의 핵심 전제를 먼저 답하고 실제 관측 수치 또는 본문 한 가지를 들어 수용·일부 수용·반박 중 입장을 밝히세요. 단순히 자기 초기 주장을 반복하지 마세요. 출처 수치나 조건으로 상대 가설을 반박하거나 인정하세요. 모르면 미확인이라 밝히되 그 조건 아래 가능한 상승 경로도 따져 보세요. 동조나 표 변경을 강요하지 마세요.";
            default -> "전체 질문·답변·반론을 읽은 최종 투표입니다. 실제 답변 중 가장 설득력 있는 상대 역할과 반론을 counterpoint에 지목하고 왜 수용/반박하는지 적으세요. 다른 역할이 기권했다는 이유로 동조하지 말고 자신의 핵심 근거가 그 반론으로 바뀌었는지 판단하세요. questionTo/question/replyTo/reply는 빈 문자열. 초기와 표가 바뀌면 changedReason에 실제 바뀐 근거를, 같아도 반론 검토 결과를 적으세요.";
        };
    }
    private CommitteeResearchPrompts(){}
}
