package com.personaltrader.research;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class IssuerEvidenceSelectionTest {
    private PublicInformationService.Evidence e(String id,String title){return new PublicInformationService.Evidence(id,"OpenDART API","DART_API",title,"https://dart.fss.or.kr/",null,null,Instant.EPOCH,"OFFICIAL_ISSUER_CODE");}
    @Test void repeatedProductIssuanceDoesNotCrowdOutBusinessFacts(){
        var rows=new ArrayList<PublicInformationService.Evidence>();for(int n=0;n<10;n++)rows.add(e("product"+n,"투자설명서(일괄신고)"));
        rows.add(e("earnings","영업(잠정)실적(공정공시)"));rows.add(e("correction","정정 투자설명서"));
        var selected=PublicInformationService.selectEvidence(rows);
        assertThat(selected).extracting(PublicInformationService.Evidence::id).contains("earnings","correction");
        assertThat(selected.stream().filter(x->PublicInformationService.evidenceKind(x).equals("ISSUANCE_TITLE")).count()).isEqualTo(1);
        assertThat(PublicInformationService.evidenceKind(rows.getLast())).isEqualTo("MATERIAL_DISCLOSURE_TITLE");
    }
}
