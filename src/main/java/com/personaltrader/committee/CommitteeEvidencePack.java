package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit periods and compact evidence, bounded by the coordinator's unchanged 24KB budget. */
final class CommitteeEvidencePack {
    static String encode(InvestmentCommitteeService.Brief r,ObjectMapper mapper){
        try{
            Map<String,Object> pack=new LinkedHashMap<>();
            pack.put("roleDossiersHash",com.personaltrader.research.PublicInformationService.hash(mapper.writeValueAsString(CommitteeResearchDossier.from(r))));
            pack.put("promptVersion",CommitteeContract.PROMPT_VERSION);pack.put("company",List.of(r.code(),r.name(),r.agenda()));
            pack.put("chartBasisDate",r.chartBasisDate());pack.put("confirmedClose",r.referenceClose());
            pack.put("financialCurrent",r.financialCurrent());
            var f=r.financialEvidence();
            if(f!=null){
                var periods=f.periods().stream().filter(p->"READY".equals(p.state())).map(p->{Map<String,Object> item=new LinkedHashMap<>();
                    item.put("period",p.label());item.put("report",p.report());item.put("fs",p.fs());item.put("sourceId",p.sourceId());
                    item.put("valuesKRW",p.values());item.put("unavailable",p.unavailable());item.put("restatement",p.changedSinceFirstFetch());return item;}).toList();
                pack.put("financialEvidence",Map.of("financialSectorReview",f.financialSectorReview(),"currency",f.currency(),"periods",periods,
                    "unavailablePeriods",f.periods().stream().filter(p->!"READY".equals(p.state())).map(p->p.label()+" · "+p.state()).toList()));
            }
            pack.put("quantitativeFacts",compactFacts(CommitteeQuantitativeFacts.from(f)));
            pack.put("interimFacts",compactFacts(CommitteeQuantitativeFacts.interim(f)));
            // Business excerpt is supplied once. Curated hypotheses are separate from publisher text.
            var v=r.valueReview();if(v!=null){var tree=mapper.valueToTree(v);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("businessFacts");pack.put("valueReview",tree);}
            pack.put("businessEvidence",r.businessEvidence());pack.put("multiples",r.multiples());
            pack.put("roleCoverage",CommitteeEvidenceCoverage.from(r).stream().map(x->Map.of("role",x.key(),"state",x.state(),"evidenceIds",x.evidenceIds())).toList());
            List<Map<String,Object>> sources=r.evidence().stream().map(x->{Map<String,Object> item=new LinkedHashMap<>();item.put("id",x.id());item.put("source",x.source());
                item.put("title",x.title());item.put("date",x.publishedAt()!=null?x.publishedAt():x.publishedDate());
                item.put("accessLevel",x.accessLevel());item.put("relevance",x.relevance());return item;}).toList();
            pack.put("sources",sources);
            // Preserve core numbers and original source IDs; only optional publisher excerpts shrink.
            // The full bounded publisher summary remains in the frozen brief for role-specific dossiers/UI.
            for(int limit:new int[]{240,160,80,0}){
                for(int i=0;i<sources.size();i++){
                    var source=r.evidence().get(i);var item=sources.get(i);String summary=source.publisherSummary();
                    String article=source.articleExcerpt();item.remove("publisherExcerpt");
                    if(article!=null&&!article.isBlank()){
                        if(limit>0)item.put("publisherExcerpt",article.substring(0,Math.min(article.length(),limit)));
                        item.put("articleExcerptTruncated",source.articleTruncated()||article.length()>limit);
                        item.put("articleScope",limit==0?"ROLE_DOSSIER_ONLY":"PUBLISHER_EXCERPT");
                    }
                    if(summary==null||summary.isBlank())continue;
                    item.remove("publisherSummary");
                    if(limit>0)item.put("publisherSummary",summary.substring(0,Math.min(summary.length(),limit)));
                    item.put("summaryExcerptTruncated",source.summaryTruncated()||summary.length()>limit);
                    if(limit==0)item.put("summaryScope","ROLE_DOSSIER_ONLY");else item.remove("summaryScope");
                }
                String encoded=CommitteeEvidenceIds.encode(pack,r,mapper);
                if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=24000)return encoded;
            }
            return CommitteeEvidenceIds.encode(pack,r,mapper); // Enqueue validates the size; read-only pages remain available.
        }catch(Exception e){throw new IllegalArgumentException("분석 입력 형식 확인 필요");}
    }
    private static List<Map<String,Object>> compactFacts(List<CommitteeQuantitativeFacts.Fact> facts){
        return facts.stream().map(f->{Map<String,Object> m=new LinkedHashMap<>();m.put("key",f.key());m.put("label",f.label());m.put("value",f.value());
            m.put("unit",f.unit());m.put("period",f.period());m.put("fs",f.fs());m.put("formula",f.formula());m.put("limitation",f.limitation());m.put("evidenceIds",f.evidenceIds());return m;}).toList();
    }
    private CommitteeEvidencePack(){}
}
