package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;

class CommitteeGatewayBoundaryTest {
    @Test void schemaBoundsMatchShortUserFacingOpinion() {
        var schema=new ObjectMapper().valueToTree(CommitteeAiGateway.schema(false,Set.of("source")));
        var properties=schema.path("properties");
        assertThat(properties.path("summary").path("maxLength").intValue()).isEqualTo(170);
        for(String field:List.of("facts","inferences","risks")){
            assertThat(properties.path(field).path("maxItems").intValue()).isEqualTo(2);
            assertThat(properties.path(field).path("items").path("maxLength").intValue()).isEqualTo(120);
        }
        assertThat(properties.path("evidenceIds").path("maxItems").intValue()).isEqualTo(6);
        assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
    }
    @Test void summaryAlsoHasProviderSideBounds() {
        var p=new ObjectMapper().valueToTree(CommitteeAiGateway.schema(true,Set.of("source"))).path("properties");
        assertThat(p.path("summary").path("maxLength").intValue()).isEqualTo(250);
        assertThat(p.path("cautions").path("maxItems").intValue()).isEqualTo(3);
    }
    private static class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long n){}
        @Override public void cancel(){cancelled=true;}
    }
    @Test void oversizedResponseIsCancelledBeforeAccumulation() {
        var s=new BoundedResponseSubscriber(3);var subscription=new Subscription();s.onSubscribe(subscription);
        s.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(()->s.getBody().toCompletableFuture().join()).hasCauseInstanceOf(java.io.IOException.class);
    }
    @Test void responseWithinLimitRemainsUnchanged() {
        var s=new BoundedResponseSubscriber(3);var subscription=new Subscription();s.onSubscribe(subscription);
        s.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));s.onComplete();
        assertThat(subscription.cancelled).isFalse();
        assertThat(s.getBody().toCompletableFuture().join()).containsExactly(1,2,3);
    }
    @Test void malformedModelTextIsRejectedWithoutEchoingIt() {
        String privateMarker="synthetic-response-marker";
        var node=new ObjectMapper().createObjectNode().put("summary",privateMarker);
        assertThatThrownBy(()->CommitteeAiCoordinator.text(node,"summary",5))
            .hasMessage("TEXT_summary").hasMessageNotContaining(privateMarker);
    }
    @Test void tooManyItemsCannotReachStoredOpinions() {
        var node=new ObjectMapper().createObjectNode();node.putArray("facts").add("A").add("B").add("C").add("D");
        assertThatThrownBy(()->CommitteeAiCoordinator.strings(node,"facts",3,180)).hasMessage("LIST_facts");
    }
}
