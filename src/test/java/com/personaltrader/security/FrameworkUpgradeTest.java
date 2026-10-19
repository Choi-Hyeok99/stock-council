package com.personaltrader.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.SpringVersion;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** Version and snapshot compatibility checks only; no credentials, network or trading. */
class FrameworkUpgradeTest {
    @Test void runtimeFrameworkIncludesBothAdvisoryFixes() {
        String version=SpringVersion.getVersion();
        assertThat(version).isNotNull();
        String[] parts=version.split("[.-]");
        int major=Integer.parseInt(parts[0]),minor=Integer.parseInt(parts[1]),patch=Integer.parseInt(parts[2]);
        assertThat(major>7||major==7&&(minor>0||patch>=9)).isTrue();
        assertThat(SpringBootVersion.getVersion()).startsWith("4.");
    }

    @Test void originalSnapshotMapperIsAvailableWithoutMigratingStoredRecords() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
            .run(context->{
                assertThat(context).hasSingleBean(ObjectMapper.class);
                ObjectMapper mapper=context.getBean(ObjectMapper.class);
                var original=new Snapshot(Instant.parse("2026-01-01T00:00:00Z"),new BigDecimal("123.45"),mapper.readTree("{\"state\":\"READY\",\"missing\":null}"));
                String encoded=mapper.writeValueAsString(original);
                Snapshot read=mapper.readValue(encoded,Snapshot.class);
                assertThat(read.generatedAt()).isEqualTo(original.generatedAt());
                assertThat(read.amount()).isEqualByComparingTo(original.amount());
                assertThat(read.payload().path("state").asText()).isEqualTo("READY");
                assertThat(read.payload().path("missing").isNull()).isTrue();
                assertThat(mapper.readTree(encoded).path("generatedAt").isTextual()).isTrue();
            });
    }

    record Snapshot(Instant generatedAt,BigDecimal amount,JsonNode payload){}
}
