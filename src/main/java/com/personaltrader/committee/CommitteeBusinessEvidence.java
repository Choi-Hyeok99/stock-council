package com.personaltrader.committee;

import com.personaltrader.research.*;
import java.util.*;

/** Frozen, bounded official text. A located passage is not a verified thesis or table value. */
public record CommitteeBusinessEvidence(String state,List<Passage> passages,List<String> gaps) {
    public record Passage(String sourceId,String sourceUrl,String period,String category,String title,String text,boolean truncated,java.time.Instant fetchedAt,java.time.LocalDate disclosureDate){}
    public static CommitteeBusinessEvidence from(DartFinancialService.Company c,CompanySourceService sources){
        if(c==null)return new CommitteeBusinessEvidence("WAITING",List.of(),List.of("공식 재무·공시 식별 대기"));
        var periods=new ArrayList<DartFinancialService.Period>();
        if(c.latestInterim()!=null)periods.add(c.latestInterim());
        c.annuals().stream().filter(p->"READY".equals(p.state())).max(Comparator.comparingInt(DartFinancialService.Period::year)).ifPresent(periods::add);
        Map<String,List<Passage>> candidates=new LinkedHashMap<>();List<String> gaps=new ArrayList<>();
        for(var p:periods){
            if(p.sourceUrl()==null)continue;
            var doc=sources.source(c.code(),"DOCUMENT",CompanySourceService.receipt(p.sourceUrl()));
            if(doc==null||!"READY".equals(doc.state())||doc.payload().path("extractorVersion").asInt()<OfficialDocumentExcerpt.VERSION){
                gaps.add(p.label()+" 본문 "+(doc!=null&&"ERROR".equals(doc.state())?"수집/형식 확인 대기":"발췌 수집 대기"));continue;
            }
            for(var s:doc.payload().path("sections")){
                String category=s.path("category").asText(),text=s.path("text").asText();
                if(text.isBlank())continue;
                candidates.computeIfAbsent(category,ignored->new ArrayList<>()).add(new Passage("document:"+doc.identity(),p.sourceUrl(),p.label(),category,s.path("title").asText(),
                    text,s.path("truncated").asBoolean(),doc.fetchedAt(),p.disclosureDate()));
            }
        }
        if(!candidates.containsKey("BUSINESS"))gaps.add("사업 구조 본문 미연결");
        if(c.financialSectorReview()&&!candidates.containsKey("CAPITAL"))gaps.add("금융업 건전성 본문 미연결");
        if(!candidates.containsKey("AUDIT"))gaps.add("감사/검토 의견 본문 미연결");
        var order=List.of("BUSINESS","OPERATIONS","CAPITAL","AUDIT","TECHNOLOGY","RISK","MANAGEMENT");
        var selected=new ArrayList<Passage>();
        for(String category:order){
            var options=candidates.get(category);if(options==null)continue;
            // A short interim cross-reference must not displace the annual business description.
            // Current figures/risk retain the newest source; business/technology prefer substantive text.
            var p=Set.of("BUSINESS","TECHNOLOGY").contains(category)?options.stream()
                .max(Comparator.comparingInt(x->substance(x.text()))).orElseThrow():options.getFirst();
            if(substance(p.text())<120)p=options.stream().max(Comparator.comparingInt(x->substance(x.text()))).orElseThrow();
            selected.add(p);
        }
        // Reserve room for every available category instead of dropping all later roles after large tables.
        var bounded=new ArrayList<Passage>();int remaining=3000;
        for(int i=0;i<selected.size();i++){
            var p=selected.get(i);int max=switch(p.category()){case "OPERATIONS","CAPITAL"->700;case "AUDIT"->420;default->550;};
            int allowance=Math.min(max,remaining-240*(selected.size()-i-1));
            String text=clip(p.text(),allowance);boolean clipped=text.length()<p.text().length();
            bounded.add(new Passage(p.sourceId(),p.sourceUrl(),p.period(),p.category(),p.title(),text,
                p.truncated()||clipped,p.fetchedAt(),p.disclosureDate()));remaining-=text.length();
        }
        return new CommitteeBusinessEvidence(bounded.isEmpty()?"WAITING":gaps.isEmpty()?"EXCERPTS":"PARTIAL",List.copyOf(bounded),List.copyOf(gaps));
    }
    private static int substance(String text){return text.contains("기재를 생략")||text.contains("참조하시기")?Math.min(text.length(),80):text.length();}
    private static String clip(String text,int limit){
        if(text.length()<=limit)return text;
        int end=limit-10,breakAt=text.lastIndexOf(" / ",end);
        if(breakAt>=end*2/3)end=breakAt;
        return text.substring(0,end)+" …[이하 생략]";
    }
}
