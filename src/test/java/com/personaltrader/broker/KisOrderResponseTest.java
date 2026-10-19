package com.personaltrader.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

/** Provider field names verified by today's read-only response. Fixture contains no real identifiers. */
class KisOrderResponseTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final KisExecutionBrokerClient client=new KisExecutionBrokerClient(null,null,null,mapper,
        Clock.fixed(Instant.parse("2026-10-06T04:00:00Z"),ZoneOffset.UTC));
    @Test void SubmittedPaperOrderReadsCnclConfirmedQuantity(){
        var result=client.parseOrder(row(0,2,0,"N"));
        assertThat(result.quantity()).isEqualTo(2);assertThat(result.filledQuantity()).isZero();
        assertThat(result.canceled()).isFalse();
    }
    @Test void ConfirmedRemainderCancellationIsTerminal(){
        var result=client.parseOrder(row(1,0,1,"Y"));
        assertThat(result.filledQuantity()).isEqualTo(1);assertThat(result.canceled()).isTrue();
    }
    @Test void CancellationFlagWithoutConfirmedQuantityDoesNotReleaseReservation(){
        assertThat(client.parseOrder(row(0,2,0,"Y")).canceled()).isFalse();
    }
    @Test void MissingConfirmedQuantityCannotBeTreatedAsZero(){
        ObjectNode row=row(0,2,0,"N");row.remove("cncl_cfrm_qty");
        assertThatThrownBy(()->client.parseOrder(row)).isInstanceOf(IllegalStateException.class);
    }
    @Test void OfficialSampleFieldVariantRemainsSupported(){
        ObjectNode row=row(1,0,1,"Y");row.put("cnc_cfrm_qty",row.remove("cncl_cfrm_qty").asText());
        assertThat(client.parseOrder(row).canceled()).isTrue();
    }
    @Test void ConflictingVariantsBlockReconciliation(){
        ObjectNode row=row(0,2,0,"N");row.put("cnc_cfrm_qty","1");
        assertThatThrownBy(()->client.parseOrder(row)).isInstanceOf(IllegalStateException.class).hasMessageContaining("응답 충돌");
    }
    private ObjectNode row(int filled,int remaining,int canceled,String flag){
        var row=mapper.createObjectNode();
        row.put("odno","1").put("orgn_odno","0").put("ord_orgno","1").put("ord_gno_brno","1")
            .put("sll_buy_dvsn_cd","02").put("ord_qty","2").put("tot_ccld_qty",String.valueOf(filled))
            .put("rmn_qty",String.valueOf(remaining)).put("cncl_cfrm_qty",String.valueOf(canceled)).put("rjct_qty","0")
            .put("tot_ccld_amt",String.valueOf(filled*1000)).put("ord_unpr","1000").put("cncl_yn",flag)
            .put("pdno","000001").put("ord_dt","20261006").put("ord_tmd","110000");
        return row;
    }
}
