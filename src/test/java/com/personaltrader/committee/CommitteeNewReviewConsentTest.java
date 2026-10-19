package com.personaltrader.committee;

import com.personaltrader.research.ResearchWorkspace;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommitteeNewReviewConsentTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ResearchWorkspace workspace=mock(ResearchWorkspace.class);
    private final CommitteeRequestPolicy requests=mock(CommitteeRequestPolicy.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"),ZoneOffset.UTC);
    private CommitteeNewReviewConsent.Uncertain call(String scope,String state,boolean requested){
        return new CommitteeNewReviewConsent.Uncertain(7L,10L,scope,state,"검증용 기업",new BigDecimal("0.01"),requested);
    }
    private CommitteeNewReviewConsent service(boolean enabled,List<CommitteeNewReviewConsent.Uncertain> rows){
        when(workspace.id()).thenReturn("scope");when(requests.unknownBlocked(42L)).thenReturn(true);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CommitteeNewReviewConsent.Uncertain>>any())).thenReturn(rows);
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_brief WHERE id=? AND scope_id=?)",Boolean.class,42L,"scope")).thenReturn(true);
        return new CommitteeNewReviewConsent(jdbc,clock,workspace,requests,enabled);
    }
    @Test void previewAllowsExplicitConsentButCannotGrantPaidAuthority(){
        var s=service(true,List.of(call("scope","CLOSED",true)));
        var v=s.view(42L);assertThat(v.required()).isTrue();assertThat(v.canConfirm()).isTrue();
        assertThat(v.calls()).extracting(CommitteeNewReviewConsent.Pending::callId).containsExactly(7L);
        assertThat(v.reservedUsd()).isEqualByComparingTo("0.01");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void activeOrUnrequestedOrOtherWorkspaceUnknownsCannotBeAcknowledgedAsClosed(){
        assertThat(CommitteeNewReviewConsent.eligible(true,"scope",List.of(call("scope","UNKNOWN",true)))).isFalse();
        assertThat(CommitteeNewReviewConsent.eligible(true,"scope",List.of(call("scope","CLOSED",false)))).isFalse();
        assertThat(CommitteeNewReviewConsent.eligible(true,"scope",List.of(call("other","CLOSED",true)))).isFalse();
        assertThat(CommitteeNewReviewConsent.eligible(false,"scope",List.of(call("scope","CLOSED",true)))).isFalse();
    }
    @Test void merelyClosingAJobDoesNotAuthorizeAnyBrief(){
        var s=service(true,List.of(call("scope","CLOSED",true)));
        assertThatThrownBy(()->s.authorize(42L,null)).isInstanceOf(CommitteeAiCoordinator.RequestRejected.class).hasMessageContaining("직접 확인");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void exactAcknowledgedCallSetIsRequiredAndDuplicatesCannotMasqueradeAsAcknowledgement(){
        assertThat(CommitteeNewReviewConsent.confirms(new CommitteeNewReviewConsent.Confirmation(true,List.of(7L)),Set.of(7L))).isTrue();
        assertThat(CommitteeNewReviewConsent.confirms(new CommitteeNewReviewConsent.Confirmation(false,List.of(7L)),Set.of(7L))).isFalse();
        assertThat(CommitteeNewReviewConsent.confirms(new CommitteeNewReviewConsent.Confirmation(true,List.of(7L)),Set.of(7L,8L))).isFalse();
        assertThat(CommitteeNewReviewConsent.confirms(new CommitteeNewReviewConsent.Confirmation(true,List.of(7L,7L)),Set.of(7L))).isFalse();
        assertThat(CommitteeNewReviewConsent.confirms(new CommitteeNewReviewConsent.Confirmation(true,Arrays.asList(7L,null)),Set.of(7L))).isFalse();
    }
    @Test void staleAcknowledgementCannotWriteAnException(){
        var s=service(true,List.of(call("scope","CLOSED",true)));
        assertThatThrownBy(()->s.authorize(42L,new CommitteeNewReviewConsent.Confirmation(true,List.of(8L)))).isInstanceOf(CommitteeAiCoordinator.RequestRejected.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void confirmedAcknowledgementOnlyAppendsPermissionForThisNewBrief(){
        var s=service(true,List.of(call("scope","CLOSED",true)));
        s.authorize(42L,new CommitteeNewReviewConsent.Confirmation(true,List.of(7L)));
        verify(jdbc).update(argThat(sql->sql.contains("INSERT INTO committee_unknown_review_authorization")),eq(42L),eq(7L),eq(clock.instant().atOffset(ZoneOffset.UTC)),contains("예약 비용 유지"));
        verify(jdbc,never()).update(argThat(sql->sql.contains("UPDATE ")||sql.contains("DELETE ")),any(Object[].class));
        verify(requests,never()).record(anyLong(),any());
    }
    @Test void existingJobOrAnyActiveMeetingRequiresItsLifecycleControls(){
        var s=service(true,List.of(call("scope","CLOSED",true)));
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM committee_ai_job WHERE brief_id=? OR state IN ('QUEUED','RUNNING','FINALIZING'))",Boolean.class,42L)).thenReturn(true);
        assertThat(s.view(42L).canConfirm()).isFalse();
        assertThatThrownBy(()->s.authorize(42L,new CommitteeNewReviewConsent.Confirmation(true,List.of(7L)))).isInstanceOf(CommitteeAiCoordinator.RequestRejected.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void exhaustedMonthlyBudgetCannotCreateAJobOrRequestEvenWhenNoUnknownBlocksIt(){
        var gateway=mock(CommitteeAiGateway.class);var ledger=mock(CommitteeAiLedger.class);
        var transactions=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(gateway.available()).thenReturn(true);when(workspace.id()).thenReturn("scope");
        when(ledger.view("scope")).thenReturn(new CommitteeAiLedger.Budget(java.time.LocalDate.of(2026,10,1),BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,0,BigDecimal.ZERO,1,10,"검증용"));
        var coordinator=new CommitteeAiCoordinator(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),clock,gateway,ledger,workspace,mock(CommitteeConversationService.class),transactions,requests);
        assertThatThrownBy(()->coordinator.enqueue(42L)).isInstanceOf(CommitteeAiCoordinator.RequestRejected.class).hasMessageContaining("남은 AI 호출 예산");
        verify(jdbc,never()).update(anyString(),any(Object[].class));verify(requests,never()).record(anyLong(),any());
        verify(gateway,never()).submit(anyLong(),any());
    }
}
