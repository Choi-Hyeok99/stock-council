package com.personaltrader.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.committee.CommitteeReviewLibrary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Scheduled snapshots reuse collected data. Never enqueue paid AI or invoke a broker. */
@Service
public class ResearchDigestService {
    private final ResearchWorkspace workspace;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    private final Clock clock;private final IdeaBoardService ideas;private final PublicInformationService information;
    private final CommitteeReviewLibrary library;
    private volatile String issue;
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final Map<String,LocalTime> SLOTS=Map.of("MORNING",LocalTime.of(8,30),"MIDDAY",LocalTime.of(12,0),"CLOSE",LocalTime.of(15,40));
    public ResearchDigestService(ResearchWorkspace workspace,JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,
        IdeaBoardService ideas,PublicInformationService information,CommitteeReviewLibrary library){
        this.workspace=workspace;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.ideas=ideas;this.information=information;this.library=library;
    }
    @Scheduled(fixedDelay=30000,initialDelay=80000)
    public synchronized void publishDue(){
        try{var now=clock.instant();var local=now.atZone(SEOUL);String scope=workspace.id();
            for(var slot:SLOTS.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList()){
                if(local.toLocalTime().isBefore(slot.getValue())||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM research_digest WHERE scope_id=? AND report_day=? AND slot=?)",Boolean.class,scope,local.toLocalDate(),slot.getKey())))continue;
                var board=ideas.view();var info=information.view();var inbox=library.view();
                List<Company> companies=board.entry()==null?List.of():board.entry().report().signals().stream().limit(10)
                    .map(s->new Company(s.code(),s.name(),s.basisDate(),s.lastClose(),s.reasons().stream().limit(3).toList(),s.risks().stream().limit(3).toList())).toList();
                var report=new Digest(slot.getKey(),now,local.toLocalDate().atTime(slot.getValue()).atZone(SEOUL).toInstant(),board.current(),board.lastComputedAt(),companies,
                    info.sources().stream().map(s->new Source(s.source(),s.state(),s.lastSuccessAt(),s.coverage())).toList(),
                    info.latest().stream().limit(8).map(e->new Article(e.title(),e.url(),e.source(),e.publishedAt(),e.publishedDate())).toList(),
                    inbox.reviews().stream().filter(CommitteeReviewLibrary.SavedReview::complete).limit(3).toList(),
                    List.of("저장 자료를 재사용한 정기 연구 요약 · 유료 AI 자동 호출 없음","기사 제목과 링크만 수집한 범위는 본문 검증·기업 수혜 확정이 아님","자동매매 없음 · 직접 거래 판단은 사용자 · 당시 자료와 수집 시각 확인"));
                jdbc.update("INSERT INTO research_digest(scope_id,report_day,slot,generated_at,report) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",scope,local.toLocalDate(),slot.getKey(),now.atOffset(ZoneOffset.UTC),mapper.writeValueAsString(report));
            }issue=null;
        }catch(Exception failure){issue="연구 보고서 저장 대기 · 이전 작성 시각을 확인하세요";}
    }
    public List<Entry> latest(){return jdbc.query("SELECT id,generated_at,report FROM research_digest WHERE scope_id=? ORDER BY id DESC LIMIT 12",(rs,n)->{
        try{return new Entry(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),mapper.readValue(rs.getString(3),Digest.class));}
        catch(Exception failure){throw new IllegalStateException("연구 보고서 확인 필요");}},workspace.id());}
    public String issue(){return issue;}
    public record Company(String code,String name,LocalDate priceDate,java.math.BigDecimal close,List<String> reasons,List<String> risks){}
    public record Source(String source,String state,Instant lastSuccessAt,String coverage){}
    public record Article(String title,String url,String source,Instant publishedAt,LocalDate publishedDate){}
    public record Digest(String slot,Instant generatedAt,Instant scheduledAt,boolean researchCurrent,Instant researchAt,List<Company> companies,List<Source> sources,List<Article> articles,List<CommitteeReviewLibrary.SavedReview> completedReviews,List<String> notes){}
    public record Entry(long id,Instant generatedAt,Digest report){}
}
