package com.personaltrader.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Reads real saved model outputs only. Preparation facts and events are not bot speech. */
@Service
public class CommitteeConversationService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    public CommitteeConversationService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
    public Conversation read(InvestmentCommitteeService.Entry entry){
        if(entry==null)return empty();
        var versions=jdbc.queryForList("SELECT prompt_version FROM committee_ai_job WHERE brief_id=?",String.class,entry.id());
        String expectedVersion=versions.isEmpty()?CommitteeContract.PROMPT_VERSION:versions.getFirst();
        Set<String> evidenceIds=new HashSet<>(entry.report().evidence().stream().map(InvestmentCommitteeService.Evidence::id).toList());
        List<String> issues=new ArrayList<>();List<Message> messages=new ArrayList<>();List<CommitteeContract.Opinion> finals=new ArrayList<>();
        var rows=jdbc.query("SELECT id,role_key,phase,generated_at,opinion FROM committee_opinion WHERE brief_id=? ORDER BY generated_at,id LIMIT 25",
            (rs,n)->new Row(rs.getLong("id"),rs.getString("role_key"),rs.getString("phase"),rs.getObject("generated_at",OffsetDateTime.class).toInstant(),rs.getString("opinion")),entry.id());
        if(rows.size()>(CommitteeContract.usesDebate(expectedVersion)?24:16)){issues.add("대화 기록 범위 확인 필요");return new Conversation(List.of(),conclusions(List.of()),CommitteeContract.tally(List.of(),entry.fingerprint(),evidenceIds,clock.instant(),expectedVersion),issues);}
        for(var row:rows){
            try{
                var o=mapper.readValue(row.json(),CommitteeContract.Opinion.class);
                var role=CommitteeContract.ROLES.stream().filter(r->r.key().equals(row.role())).findFirst().orElseThrow();
                if(!valid(o,row,entry,evidenceIds,expectedVersion))throw new IllegalArgumentException();
                messages.add(new Message(row.id(),role.key(),role.name(),o.phase(),o.vote(),o.summary(),o.facts(),o.inferences(),o.risks(),o.evidenceIds(),o.changedReason(),row.at(),o.discussion()));
                if("FINAL".equals(o.phase()))finals.add(o);
            }catch(Exception failure){issues.add("형식·출처·시각 확인이 필요한 발언은 표시를 보류했어요.");}
        }
        return new Conversation(List.copyOf(messages),conclusions(finals),CommitteeContract.tally(finals,entry.fingerprint(),evidenceIds,clock.instant(),expectedVersion),issues.stream().distinct().toList());
    }
    private boolean valid(CommitteeContract.Opinion o,Row row,InvestmentCommitteeService.Entry entry,Set<String> ids,String expectedVersion){
        return o!=null&&Objects.equals(o.roleKey(),row.role())&&Objects.equals(o.phase(),row.phase())
            &&Set.of("INDEPENDENT","DEBATE","FINAL").contains(o.phase())
            &&(!CommitteeContract.usesDebate(expectedVersion)||CommitteeContract.validDiscussion(o.discussion(),o.roleKey(),o.phase()))&&Objects.equals(o.inputFingerprint(),entry.fingerprint())
            &&CommitteeContract.supportedVersion(expectedVersion)&&Objects.equals(expectedVersion,o.promptVersion())&&o.generatedAt()!=null&&!o.generatedAt().isAfter(clock.instant())
            &&!row.at().isAfter(clock.instant())&&!row.at().isBefore(entry.generatedAt())
            &&Duration.between(o.generatedAt(),row.at()).abs().compareTo(Duration.ofSeconds(1))<0
            &&Set.of("APPROVE","REJECT","ABSTAIN").contains(o.vote())&&shortText(o.summary(),800)&&!o.summary().isBlank()
            &&list(o.facts())&&list(o.inferences())&&list(o.risks())&&o.evidenceIds()!=null&&o.evidenceIds().size()<=32
            &&ids.containsAll(o.evidenceIds())&&(!"APPROVE".equals(o.vote())||!o.evidenceIds().isEmpty())
            &&(o.changedReason()==null||shortText(o.changedReason(),800));
    }
    private static boolean shortText(String value,int max){return value!=null&&value.length()<=max;}
    private static boolean list(List<String> values){return values!=null&&values.size()<=12&&values.stream().allMatch(v->shortText(v,800));}
    private static List<Conclusion> conclusions(List<CommitteeContract.Opinion> opinions){
        return CommitteeContract.ROLES.stream().map(role->{var o=opinions.stream().filter(x->role.key().equals(x.roleKey())).findFirst().orElse(null);
            return new Conclusion(role.key(),role.name(),o==null?null:o.vote(),o==null?null:o.summary(),o==null?null:o.generatedAt());}).toList();
    }
    private Conversation empty(){return new Conversation(List.of(),conclusions(List.of()),CommitteeContract.tally(List.of(),null,Set.of(),clock.instant()),List.of());}
    private record Row(long id,String role,String phase,Instant at,String json){}
    public record Message(long id,String roleKey,String roleName,String phase,String vote,String summary,List<String> facts,List<String> inferences,
        List<String> risks,List<String> evidenceIds,String changedReason,Instant at,CommitteeContract.Discussion discussion){}
    public record Conclusion(String roleKey,String roleName,String vote,String summary,Instant at){}
    public record Conversation(List<Message> messages,List<Conclusion> conclusions,CommitteeContract.Tally tally,List<String> issues){}
}
