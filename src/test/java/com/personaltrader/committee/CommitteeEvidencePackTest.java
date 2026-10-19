package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommitteeEvidencePackTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private InvestmentCommitteeService.Brief brief()throws Exception{
        ObjectNode n=mapper.createObjectNode();n.put("code","000001");n.put("name","Fixture");n.put("agenda","BUY_REVIEW");n.set("evidence",mapper.valueToTree(List.of(new InvestmentCommitteeService.Evidence("document:fixture","OpenDART","공식 본문","https://dart.fss.or.kr/",null,null,Instant.EPOCH,"OFFICIAL_EXCERPT","ISSUER_BUSINESS"))));n.set("roles",mapper.createArrayNode());
        n.set("businessEvidence",mapper.valueToTree(new CommitteeBusinessEvidence("EXCERPTS",List.of(
            new CommitteeBusinessEvidence.Passage("document:fixture","https://dart.fss.or.kr/","2026년 반기","BUSINESS","사업의 개요","금융서비스와 고객 자산관리를 통한 수수료 수익",false,Instant.EPOCH,LocalDate.of(2026,8,14))),List.of())));
        n.set("financialEvidence",mapper.valueToTree(new CommitteeFinancialEvidence(true,true,"KRW",List.of(),List.of())));
        return mapper.treeToValue(n,InvestmentCommitteeService.Brief.class);
    }
    @Test void rolesReceiveActualBusinessPassageAndOwnScope()throws Exception{
        var r=brief();var pack=mapper.readTree(CommitteeEvidencePack.encode(r,mapper));
        assertThat(pack.path("businessEvidence").path("passages").get(0).path("text").asText()).contains("수수료 수익");
        assertThat(CommitteeEvidenceCoverage.roleInstructions(r,"SCIENCE")).contains("디지털 채널","임상·특허를 요구하지","자본적정성 판정을 반복하지");
        assertThat(CommitteeEvidenceCoverage.from(r).stream().filter(x->x.key().equals("INDUSTRY")).findFirst().orElseThrow().evidenceIds()).contains("document:fixture");
        assertThat(pack.has("roleCoverage")).isTrue();assertThat(pack.toString()).doesNotContain("사업보고서에 연결된 요약 확인 대기");
    }
    @Test void interimDebtRatioDoesNotUseAnnualValues(){
        var annual=new CommitteeFinancialEvidence.PeriodFacts(2025,"11011","2025년 연간","CFS","READY","annual",Instant.EPOCH,null,false,Map.of("LIABILITIES",new BigDecimal("900"),"EQUITY",new BigDecimal("100")),Map.of());
        var interim=new CommitteeFinancialEvidence.PeriodFacts(2026,"11012","2026년 반기 누계","CFS","READY","interim",Instant.EPOCH,null,false,Map.of("LIABILITIES",new BigDecimal("1200"),"EQUITY",new BigDecimal("100")),Map.of());
        var f=new CommitteeFinancialEvidence(true,true,"KRW",List.of(annual,interim),List.of());
        assertThat(CommitteeQuantitativeFacts.from(f).stream().filter(x->x.key().equals("DEBT_RATIO")).findFirst().orElseThrow().value()).isEqualByComparingTo("900");
        var fact=CommitteeQuantitativeFacts.interim(f).stream().filter(x->x.key().equals("INTERIM_DEBT_RATIO")).findFirst().orElseThrow();
        assertThat(fact.value()).isEqualByComparingTo("1200");assertThat(fact.period()).contains("반기");assertThat(fact.evidenceIds()).containsExactly("interim");
    }
    @Test void originalV6RemainsReadableWithoutInventedBusinessEvidence()throws Exception{
        ObjectNode n=mapper.valueToTree(brief());n.remove("businessEvidence");
        assertThat(mapper.treeToValue(n,InvestmentCommitteeService.Brief.class).businessEvidence()).isNull();
        assertThat(CommitteeContract.supportedVersion("COMMITTEE_RESEARCH_V6")).isTrue();assertThat(CommitteeContract.usesDebate("COMMITTEE_RESEARCH_V6")).isTrue();
    }
    @Test void shortCitationsRoundTripToCanonicalSourcesAndRejectUnknownAliases()throws Exception{
        var r=brief();assertThat(CommitteeEvidenceIds.canonical("S01",r)).isEqualTo("document:fixture");
        assertThatThrownBy(()->CommitteeEvidenceIds.canonical("S99",r)).isInstanceOf(IllegalArgumentException.class);
        var pack=mapper.readTree(CommitteeEvidencePack.encode(r,mapper));
        assertThat(pack.path("sources").get(0).path("id").asText()).isEqualTo("S01");
        assertThat(pack.path("businessEvidence").path("passages").get(0).path("sourceId").asText()).isEqualTo("S01");
        assertThat(r.evidence().getFirst().id()).isEqualTo("document:fixture");
    }
    @Test void actualOpinionParserRestoresCanonicalCitationsWithoutSendingARequest()throws Exception{
        var at=Instant.parse("2026-10-16T00:00:00Z");
        var coordinator=new CommitteeAiCoordinator(null,mapper,Clock.fixed(at,ZoneOffset.UTC),null,null,null,null,
            org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),null);
        var entry=new InvestmentCommitteeService.Entry(1,at,"fingerprint",brief());var role=CommitteeContract.ROLES.getFirst();
        var n=mapper.createObjectNode();n.put("vote","APPROVE");n.put("summary","조건부 논거");n.put("changedReason","");
        for(String key:List.of("facts","inferences","risks"))n.set(key,mapper.createArrayNode());n.set("evidenceIds",mapper.valueToTree(List.of("S01")));
        n.set("discussion",mapper.valueToTree(new CommitteeContract.Discussion(CommitteeContract.questionTarget(role.key()),"일회성 이익 여부는?","","","","다음 공시의 이익 구성 확인")));
        CommitteeContract.Opinion opinion=org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator,"parseOpinion",n,role,"INDEPENDENT",entry,at,List.of());
        assertThat(opinion.evidenceIds()).containsExactly("document:fixture");
        n.set("evidenceIds",mapper.valueToTree(List.of("S99")));
        assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(coordinator,"parseOpinion",n,role,"INDEPENDENT",entry,at,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
