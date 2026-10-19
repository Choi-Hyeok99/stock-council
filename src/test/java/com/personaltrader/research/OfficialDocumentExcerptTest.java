package com.personaltrader.research;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class OfficialDocumentExcerptTest {
    private final String receipt="20261015000001",corp="00000001";
    private byte[] zip(String xml)throws Exception{var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry(receipt+".xml"));z.write(xml.getBytes(StandardCharsets.UTF_8));z.closeEntry();}return out.toByteArray();}
    private String xml(String content){return "<DOCUMENT><DOCUMENT-NAME>반기보고서</DOCUMENT-NAME><COMPANY-NAME AREGCIK=\""+corp+"\">Fixture</COMPANY-NAME>"+content+"</DOCUMENT>";}
    @Test void extractsActualBusinessAndCapitalTextWithTableHeaders()throws Exception{
        var doc=OfficialDocumentExcerpt.extract(zip(xml("<TITLE>1. 사업의 개요</TITLE><P>당사는 고객 자산관리와 위탁매매를 통해 수수료 수익을 창출하며, 시장 거래대금과 금리 변동에 영향을 받습니다.</P>"
            +"<TITLE>5. 재무건전성 등 기타 참고사항</TITLE><P>연결기준 순자본비율은 다음과 같습니다. 아래 표는 당반기와 전년말을 구분하며 연결 범위가 포함됩니다.</P><TABLE><TR><TD>구분</TD><TD>당반기</TD><TD>전년말</TD></TR><TR><TD>순자본비율</TD><TD>450%</TD><TD>400%</TD></TR></TABLE>")),receipt,corp);
        assertThat(doc.reportName()).isEqualTo("반기보고서");
        assertThat(doc.sections()).anySatisfy(s->assertThat(s.text()).contains("자산관리","수수료 수익"));
        assertThat(doc.sections()).anySatisfy(s->{assertThat(s.category()).isEqualTo("CAPITAL");assertThat(s.text()).contains("당반기","전년말","450%","400%");});
    }
    @Test void rejectsWrongIssuerExternalEntitiesAndAbsentBody()throws Exception{
        assertThatThrownBy(()->OfficialDocumentExcerpt.extract(zip(xml("<TITLE>사업의 개요</TITLE>")),receipt,corp)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->OfficialDocumentExcerpt.extract(zip(xml("<!DOCTYPE foo><TITLE>사업의 개요</TITLE>")),receipt,corp)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->OfficialDocumentExcerpt.extract(zip(xml("<TITLE>사업의 개요</TITLE><P>"+"본문".repeat(100)+"</P>")),receipt,"00000002")).isInstanceOf(IOException.class);
    }
    @Test void excerptIsBoundedAndMarkedAsPartial()throws Exception{
        var doc=OfficialDocumentExcerpt.extract(zip(xml("<TITLE>사업의 개요</TITLE><P>"+"공식 설명이 계속됩니다. ".repeat(1000)+"</P>")),receipt,corp);
        assertThat(doc.sections().getFirst().text().length()).isLessThanOrEqualTo(1420);assertThat(doc.sections().getFirst().truncated()).isTrue();
    }
}
