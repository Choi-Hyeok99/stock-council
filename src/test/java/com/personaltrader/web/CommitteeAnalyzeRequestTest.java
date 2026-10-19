package com.personaltrader.web;

import com.personaltrader.committee.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommitteeAnalyzeRequestTest {
    @Test void unconfirmedRequestCannotEnqueueOrCallAi() {
        var ai=mock(CommitteeAiCoordinator.class);
        var controller=new InvestmentCommitteeController(mock(InvestmentCommitteeService.class),ai);
        var response=controller.analyze(42L,new InvestmentCommitteeController.AnalyzeRequest(false));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(((InvestmentCommitteeController.AnalyzeFailure)response.getBody()).code()).isEqualTo("CONFIRM_REQUIRED");
        verifyNoInteractions(ai);
    }
    @Test void exactSafeRejectionIsReturnedInsteadOfGenericReservationFailure() {
        var ai=mock(CommitteeAiCoordinator.class);
        when(ai.enqueue(42L)).thenThrow(new CommitteeAiCoordinator.RequestRejected("UNKNOWN_CALLS","기존 호출 비용 확인 대기"));
        var controller=new InvestmentCommitteeController(mock(InvestmentCommitteeService.class),ai);
        var response=controller.analyze(42L,new InvestmentCommitteeController.AnalyzeRequest(true));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        var body=(InvestmentCommitteeController.AnalyzeFailure)response.getBody();
        assertThat(body.code()).isEqualTo("UNKNOWN_CALLS");assertThat(body.message()).isEqualTo("기존 호출 비용 확인 대기");
        verify(ai,times(1)).enqueue(42L);
    }
    @Test void unexpectedInternalDetailsAreNotExposed() {
        var ai=mock(CommitteeAiCoordinator.class);
        when(ai.enqueue(42L)).thenThrow(new IllegalArgumentException("private-marker"));
        var controller=new InvestmentCommitteeController(mock(InvestmentCommitteeService.class),ai);
        assertThat(controller.analyze(42L,new InvestmentCommitteeController.AnalyzeRequest(true)).getBody().toString()).doesNotContain("private-marker");
    }
    @Test void closedUnknownAcknowledgementIsPassedOnlyWithConfirmedNewReview(){
        var ai=mock(CommitteeAiCoordinator.class);var controller=new InvestmentCommitteeController(mock(InvestmentCommitteeService.class),ai);
        controller.analyze(42L,new InvestmentCommitteeController.AnalyzeRequest(true,true,java.util.List.of(7L)));
        verify(ai).enqueue(42L,new CommitteeNewReviewConsent.Confirmation(true,java.util.List.of(7L)));
        verify(ai,never()).enqueue(42L);
    }
    @Test void acknowledgingOldCostWithoutConfirmingNewReviewCannotEnqueue(){
        var ai=mock(CommitteeAiCoordinator.class);var controller=new InvestmentCommitteeController(mock(InvestmentCommitteeService.class),ai);
        var response=controller.analyze(42L,new InvestmentCommitteeController.AnalyzeRequest(false,true,java.util.List.of(7L)));
        assertThat(response.getStatusCode().value()).isEqualTo(409);verifyNoInteractions(ai);
    }
}
