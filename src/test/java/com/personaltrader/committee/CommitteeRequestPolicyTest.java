package com.personaltrader.committee;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommitteeRequestPolicyTest {
    @Test void oldUnknownExceptionDoesNotPermitAnotherUnknown() {
        assertThat(CommitteeRequestPolicy.allowsUnknowns(Set.of(169L),Set.of(169L))).isTrue();
        assertThat(CommitteeRequestPolicy.allowsUnknowns(Set.of(169L,170L),Set.of(169L))).isFalse();
        assertThat(CommitteeRequestPolicy.allowsUnknowns(Set.of(169L),Set.of())).isFalse();
        assertThat(CommitteeRequestPolicy.allowsUnknowns(Set.of(),Set.of())).isTrue();
    }
    @Test void requestDoesNotExistByDefault() {
        var jdbc=mock(JdbcTemplate.class);var policy=new CommitteeRequestPolicy(jdbc);
        assertThat(policy.requested(42L)).isFalse();
        verify(jdbc).queryForObject("SELECT EXISTS(SELECT 1 FROM committee_manual_request WHERE job_id=?)",Boolean.class,42L);
        verifyNoMoreInteractions(jdbc);
    }
    @Test void exceptionIsBoundToTheRequestedBrief() {
        var jdbc=mock(JdbcTemplate.class);var policy=new CommitteeRequestPolicy(jdbc);
        when(jdbc.queryForList(argThat(sql->sql.contains("committee_archived_call")&&sql.contains("state='UNKNOWN'")),eq(Long.class))).thenReturn(List.of(169L));
        when(jdbc.queryForList("SELECT unknown_call_id FROM committee_unknown_review_authorization WHERE brief_id=?",Long.class,42L)).thenReturn(List.of(169L));
        assertThat(policy.unknownBlocked(42L)).isFalse();
        assertThat(policy.unknownBlocked(43L)).isTrue();
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
