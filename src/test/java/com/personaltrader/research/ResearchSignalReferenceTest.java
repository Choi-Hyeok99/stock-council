package com.personaltrader.research;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchSignalReferenceTest {
    @Test void missingOrInvalidStoredReferenceNeverCreatesACompany(){
        var jdbc=mock(JdbcTemplate.class);
        var service=new IdeaBoardService(null,null,jdbc,new ObjectMapper(),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertThat(service.referenceSignal("bad")).isNull();verifyNoInteractions(jdbc);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThat(service.referenceSignal("000001")).isNull();
    }
}
