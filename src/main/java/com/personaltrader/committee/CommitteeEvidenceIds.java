package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Per-frozen-pack short citation aliases. Persistent records always retain canonical source IDs. */
final class CommitteeEvidenceIds {
    static Map<String,String> aliases(InvestmentCommitteeService.Brief r){
        Map<String,String> out=new LinkedHashMap<>();
        for(var e:r.evidence())if(!out.containsKey(e.id()))out.put(e.id(),"S"+String.format(Locale.ROOT,"%02d",out.size()+1));
        return Collections.unmodifiableMap(out);
    }
    static Set<String> allowed(InvestmentCommitteeService.Brief r){return new LinkedHashSet<>(aliases(r).values());}
    static String canonical(String alias,InvestmentCommitteeService.Brief r){
        return aliases(r).entrySet().stream().filter(e->e.getValue().equals(alias)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("출처 참조 확인 필요"));
    }
    static String encode(Object value,InvestmentCommitteeService.Brief r,ObjectMapper mapper){
        try{return aliasSerialized(mapper.writeValueAsString(value),r,mapper);}catch(Exception e){throw new IllegalArgumentException("출처 입력 확인 필요");}
    }
    static String aliasSerialized(String json,InvestmentCommitteeService.Brief r,ObjectMapper mapper){
        try{for(var e:aliases(r).entrySet())json=json.replace(mapper.writeValueAsString(e.getKey()),mapper.writeValueAsString(e.getValue()));return json;}
        catch(Exception e){throw new IllegalArgumentException("출처 입력 확인 필요");}
    }
    private CommitteeEvidenceIds(){}
}
