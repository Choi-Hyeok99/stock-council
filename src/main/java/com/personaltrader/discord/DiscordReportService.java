package com.personaltrader.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personaltrader.analysis.BotMeetingRepository;
import com.personaltrader.reports.DailyReportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.personaltrader.discord.DiscordReportFormatter.Packet;

/** Outbound reports only. Research digests; never submits orders or reads Discord conversations. */
@Service
public class DiscordReportService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final DiscordSettings settings;
    private final DiscordReportFormatter formatter;
    private final DailyReportRepository reports;
    private final com.personaltrader.research.IdeaBoardService ideas;
    private final com.personaltrader.research.CompanyThesisService theses;
    private final com.personaltrader.research.MarketSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String runtimeIssue;
    @org.springframework.beans.factory.annotation.Autowired private com.personaltrader.research.ResearchDigestService digests;

    public DiscordReportService(DiscordSettings settings,DiscordReportFormatter formatter,
            DailyReportRepository reports,com.personaltrader.research.IdeaBoardService ideas,com.personaltrader.research.MarketSessionService sessions,JdbcTemplate jdbc,ObjectMapper mapper,
            Clock clock,PlatformTransactionManager manager,com.personaltrader.research.CompanyThesisService theses,com.personaltrader.insights.AccountInsightsService insights){
        this.theses=theses;this.settings=settings;this.formatter=formatter;this.reports=reports;this.ideas=ideas;this.sessions=sessions;
        this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelay=15000,initialDelay=10000)
    public synchronized void collectDue(){
        if(!settings.enabled())return;
        try{
            String key=settings.targetKey(); Instant now=clock.instant();
            var idea=ideas.latest();
            var thesisView=theses.view();var thesis=thesisView.entry();
            tx.executeWithoutResult(transaction->{
                long baseline=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM daily_bot_report",Long.class);
                int added=jdbc.update("INSERT INTO discord_report_target(target_key,last_report_id,connected_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",key,baseline,utc(now));
                Target target=jdbc.queryForObject("SELECT * FROM discord_report_target WHERE target_key=? FOR UPDATE",this::target,key);
                if(target.disabledReason()!=null)return;
                jdbc.update("UPDATE discord_report_delivery SET state='EXPIRED',issue='관심 기업 요약 전송 방식으로 변경' WHERE target_key=? AND state='QUEUED' AND (kind='BOT_PROGRESS' OR kind='DAILY_REPORT' AND author<>'투자비서')",key);
                if(added==1){
                    enqueue(key,"CONNECTED","CONNECTION",List.of(formatter.connection(now)),now,now.plus(Duration.ofMinutes(30)));
                    return; // Historical reports are not replayed into a newly connected channel.
                }
                jdbc.update("UPDATE discord_report_delivery SET state='EXPIRED',issue='기업 연구 전용으로 전환' WHERE target_key=? AND state='QUEUED' AND (source_key LIKE 'REPORT-ACCOUNT:%' OR source_key LIKE 'ACCOUNT:%')",key);
                for(var entry:digests.latest()){
                    var report=entry.report();
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM discord_report_delivery WHERE target_key=? AND source_key LIKE ?)",Boolean.class,key,"RESEARCH-DIGEST:"+entry.id()+":%"))||entry.generatedAt().isBefore(target.connectedAt())||!entry.generatedAt().atZone(SEOUL).toLocalDate().equals(now.atZone(SEOUL).toLocalDate())||!settings.slots().contains(report.slot()))continue;
                    String slotLabel=switch(report.slot()){case "MORNING"->"장전";case "MIDDAY"->"점심";default->"마감";};
                    enqueue(key,"RESEARCH-DIGEST:"+entry.id(),"IDEA_REPORT",formatter.ideas(ideas.view().current()?idea:null,now,"📋 "+slotLabel+" 기업 연구",sessions.reportLabel()+" · 연구 전용 · AI 자동 호출 없음"),now,now.plus(Duration.ofMinutes(30)));
                }
                jdbc.update("INSERT INTO discord_thesis_cursor(target_key) VALUES (?) ON CONFLICT DO NOTHING",key);
                var thesisCursor=jdbc.queryForObject("SELECT last_fingerprint,last_queued_at FROM discord_thesis_cursor WHERE target_key=? FOR UPDATE",(rs,n)->new ThesisCursor(rs.getString(1),instant(rs,"last_queued_at")),key);
                var thesisTime=now.atZone(SEOUL).toLocalTime();
                if(thesis!=null&&thesisView.current()&&thesisView.issue()==null&&!thesis.fingerprint().equals(thesisCursor.fingerprint())
                    &&(thesisCursor.queuedAt()==null||Duration.between(thesisCursor.queuedAt(),now).toMinutes()>=30)
                    &&!thesisTime.isBefore(LocalTime.of(8,0))&&thesisTime.isBefore(LocalTime.of(23,0))){
                    enqueue(key,"THESIS:"+thesis.id(),"THESIS_REPORT",formatter.theses(thesis,now,"기업 근거·가치 가정 업데이트"),now,now.plus(Duration.ofMinutes(30)));
                    jdbc.update("UPDATE discord_thesis_cursor SET last_fingerprint=?,last_queued_at=? WHERE target_key=?",thesis.fingerprint(),utc(now),key);
                }
                jdbc.update("INSERT INTO discord_idea_cursor(target_key) VALUES (?) ON CONFLICT DO NOTHING",key);
                IdeaCursor cursor=jdbc.queryForObject("SELECT last_snapshot_id,last_fingerprint,last_sent_at FROM discord_idea_cursor WHERE target_key=? FOR UPDATE",(rs,n)->new IdeaCursor(rs.getLong("last_snapshot_id"),rs.getString("last_fingerprint"),instant(rs,"last_sent_at")),key);
                ZonedDateTime local=now.atZone(SEOUL);
                // One evening digest per closed day, even if the candidate list is
                // unchanged. Existing unique source keys make restarts idempotent.
                if(sessions.knownClosed()&&!local.toLocalTime().isBefore(LocalTime.of(20,30))
                        &&local.toLocalTime().isBefore(LocalTime.of(21,0))){
                    String digestKey="CLOSED-DAY:"+local.toLocalDate();
                    boolean alreadyQueued=jdbc.queryForObject("SELECT COUNT(*)>0 FROM discord_report_delivery WHERE target_key=? AND source_key LIKE ?",
                        Boolean.class,key,digestKey+":%");
                    if(!alreadyQueued&&(ideas.view().current()||!local.toLocalTime().isBefore(LocalTime.of(20,35))))
                        enqueue(key,digestKey,"IDEA_REPORT",
                            formatter.ideas(ideas.view().current()?idea:null,now,"🗓️ 다음 장 준비 · 휴장일 기업 점검",
                                sessions.reportLabel()+" · 장전 08:30 재검토, 사업·가격·출처를 다시 확인"),
                            now,local.toLocalDate().atTime(21,0).atZone(SEOUL).toInstant());
                }
                Instant last=cursor.sentAt();
                boolean due=last==null||Duration.between(last,now).toMinutes()>=settings.progressMinutes();
                if(idea!=null&&idea.id()>cursor.id()){
                    if(idea.fingerprint().equals(cursor.fingerprint()))
                        jdbc.update("UPDATE discord_idea_cursor SET last_snapshot_id=? WHERE target_key=?",idea.id(),key);
                    else if(due&&!local.toLocalTime().isBefore(LocalTime.of(8,0))&&local.toLocalTime().isBefore(LocalTime.of(23,0))
                            &&Duration.between(idea.generatedAt(),now).toMinutes()<20&&ideas.view().current()){
                        enqueue(key,"IDEAS:"+idea.id(),"IDEA_REPORT",formatter.ideas(idea,now,"📌 관심 기업 업데이트",sessions.reportLabel()),now,now.plus(Duration.ofMinutes(30)));
                        jdbc.update("UPDATE discord_idea_cursor SET last_snapshot_id=?,last_fingerprint=?,last_sent_at=? WHERE target_key=?",idea.id(),idea.fingerprint(),utc(now),key);
                    }
                }
            });
            runtimeIssue=null;
        }catch(RuntimeException ignored){runtimeIssue="Discord 보고 큐 저장을 완료하지 못했습니다. 기존 전송 기록을 확인해 주세요.";}
    }

    private void markMeeting(String key,String hash,Instant now){
        jdbc.update("UPDATE discord_report_target SET last_meeting_hash=?,last_meeting_at=? WHERE target_key=?",hash,utc(now),key);
    }
    private void enqueue(String key,String source,String kind,List<Packet> packets,Instant now,Instant expiry){
        for(int i=0;i<packets.size();i++){
            Packet packet=packets.get(i);
            jdbc.update("""
                INSERT INTO discord_report_delivery(target_key,source_key,author,content,kind,created_at,expires_at,next_attempt_at)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(target_key,source_key) DO NOTHING
                """,key,source+":"+i,packet.author(),packet.content(),kind,utc(now),utc(expiry),utc(now));
        }
    }

    @Scheduled(fixedDelay=5000,initialDelay=15000)
    public synchronized void sendNext(){
        if(!settings.enabled())return;
        Delivery delivery=null;
        try{
            String key=settings.targetKey(); Instant now=clock.instant();
            Target target=readTarget(); if(target==null||target.disabledReason()!=null)return;
            // A crash may occur after POST succeeded. Never automatically repeat such a message.
            jdbc.update("UPDATE discord_report_delivery SET state='UNKNOWN',issue=? WHERE target_key=? AND state='SENDING' AND started_at<?",
                "전송 중 재시작 또는 중단 · 채널에서 발송 여부 확인 필요",key,utc(now.minus(Duration.ofMinutes(2))));
            jdbc.update("UPDATE discord_report_delivery SET state='EXPIRED',issue=? WHERE target_key=? AND state='QUEUED' AND expires_at<=?",
                "오래된 보고의 전송 유효기간 종료",key,utc(now));
            delivery=tx.execute(transaction->{
                Target locked=jdbc.queryForObject("SELECT * FROM discord_report_target WHERE target_key=? FOR UPDATE",this::target,key);
                if(locked.disabledReason()!=null)return null;
                Long sending=jdbc.queryForObject("SELECT COUNT(*) FROM discord_report_delivery WHERE target_key=? AND state='SENDING'",Long.class,key);
                if(sending>0)return null;
                List<Delivery> rows=jdbc.query("SELECT * FROM discord_report_delivery WHERE target_key=? AND state='QUEUED' AND next_attempt_at<=? AND expires_at>? ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",this::delivery,key,utc(now),utc(now));
                if(rows.isEmpty())return null;
                Delivery picked=rows.getFirst();
                jdbc.update("UPDATE discord_report_delivery SET state='SENDING',attempts=attempts+1,started_at=? WHERE id=?",utc(now),picked.id());
                return picked;
            });
            if(delivery==null)return;
            send(delivery);
        }catch(RuntimeException ignored){
            runtimeIssue="Discord 전송 기록 확인 필요 · 결과 불명 메시지는 자동 재전송하지 않습니다.";
            if(delivery!=null)markUnknown(delivery.id());
        }
    }

    private void send(Delivery delivery){
        final HttpRequest request;
        try{
            String json=mapper.writeValueAsString(Map.of("username",delivery.author(),"content",delivery.content(),
                "allowed_mentions",Map.of("parse",List.of()),"flags",4));
            request=HttpRequest.newBuilder(settings.endpoint()).timeout(Duration.ofSeconds(12))
                .header("Content-Type","application/json; charset=utf-8")
                .header("User-Agent","PersonalInvestmentAssistant/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
        }catch(Exception ignored){
            fail(delivery.id(),"전송 전 보고 메시지 준비 실패");return;
        }
        try{
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status=response.statusCode();
            if(status==200){
                String message=mapper.readTree(response.body()).path("id").asText("");
                if(!message.matches("[0-9]{17,20}")){markUnknown(delivery.id());return;}
                jdbc.update("UPDATE discord_report_delivery SET state='SENT',sent_at=?,message_id=?,issue=NULL WHERE id=?",utc(clock.instant()),message,delivery.id());
                runtimeIssue=null;
            }else if(status==429){
                if(delivery.attempts()+1>=5){fail(delivery.id(),"호출 제한이 반복되어 자동 전송 중지");return;}
                double seconds=60;
                try{seconds=Double.parseDouble(response.headers().firstValue("Retry-After")
                    .orElseGet(()->{try{return mapper.readTree(response.body()).path("retry_after").asText("60");}catch(Exception ignored){return "60";}}));}
                catch(RuntimeException ignored){ /* safe default */ }
                if(!Double.isFinite(seconds)||seconds<=0||seconds>86400){fail(delivery.id(),"호출 제한 대기시간 확인 필요");return;}
                Instant retry=clock.instant().plusSeconds(Math.max(1,(long)Math.ceil(seconds)));
                tx.executeWithoutResult(transaction->{
                    jdbc.update("UPDATE discord_report_delivery SET state='QUEUED',next_attempt_at=?,issue=? WHERE id=?",utc(retry),"Discord 호출 제한 · 재시도 시각 대기",delivery.id());
                    // The rate limit applies to the webhook, not only this packet.
                    jdbc.update("UPDATE discord_report_delivery SET next_attempt_at=GREATEST(next_attempt_at,?) WHERE target_key=? AND state='QUEUED'",utc(retry),settings.targetKey());
                });
            }else if(status==401||status==403||status==404){
                String issue="Discord 웹훅 삭제·권한·인증 확인 필요 · 자동 전송 중지";
                tx.executeWithoutResult(transaction->{
                    fail(delivery.id(),issue);
                    jdbc.update("UPDATE discord_report_target SET disabled_reason=? WHERE target_key=?",issue,settings.targetKey());
                });
            }else if(status>=400&&status<500)fail(delivery.id(),"Discord가 메시지를 거절했습니다. 전송 내용을 확인해 주세요.");
            else markUnknown(delivery.id());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();markUnknown(delivery.id());}
        catch(Exception ignored){markUnknown(delivery.id());}
    }
    private void markUnknown(long id){
        try{jdbc.update("UPDATE discord_report_delivery SET state='UNKNOWN',issue=? WHERE id=? AND state='SENDING'",
            "전송 결과 미확정 · 채널에서 확인 전 자동 재전송 금지",id);}catch(RuntimeException ignored){ /* SENDING is recovered conservatively */ }
    }
    private void fail(long id,String issue){jdbc.update("UPDATE discord_report_delivery SET state='FAILED',issue=? WHERE id=?",issue,id);}

    public View view(){
        String key=settings.targetKey(); Target target=readTarget();
        List<StateCount> counts=key==null?List.of():jdbc.query("SELECT state,COUNT(*) AS amount FROM discord_report_delivery WHERE target_key=? GROUP BY state ORDER BY state",(rs,n)->new StateCount(rs.getString(1),rs.getLong(2)),key);
        List<History> history=key==null?List.of():jdbc.query("SELECT id,author,kind,state,created_at,sent_at,issue FROM discord_report_delivery WHERE target_key=? ORDER BY id DESC LIMIT 20",(rs,n)->new History(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),instant(rs,"created_at"),instant(rs,"sent_at"),rs.getString(7)),key);
        Instant lastSent=key==null?null:jdbc.queryForObject("SELECT MAX(sent_at) FROM discord_report_delivery WHERE target_key=?",(rs,n)->instant(rs,"max"),key);
        boolean confirmed=counts.stream().anyMatch(c->c.state().equals("SENT")&&c.count()>0);
        boolean problem=counts.stream().anyMatch(c->Set.of("UNKNOWN","FAILED").contains(c.state())&&c.count()>0);
        String issue=target!=null&&target.disabledReason()!=null?target.disabledReason():runtimeIssue!=null?runtimeIssue:
            problem?"일부 보고의 전송 결과 확인 필요 · 전송 기록을 확인해 주세요.":null;
        String readiness=!settings.configured()?"Discord 웹훅 로컬 설정 필요":!settings.enabled()?"Discord 발송 설정 꺼짐"
            :issue!=null?issue:target==null?"첫 연결 보고 준비 중":!confirmed?"연결 확인 메시지 전송 대기":"전송 확인됨 · 정기 보고와 봇 상태 변경 확인 중";
        return new View(settings.configured(),settings.enabled(),confirmed,readiness,issue,"Asia/Seoul",List.copyOf(settings.slots()),
            settings.progressMinutes(),target==null?null:target.connectedAt(),lastSent,counts,history,
            "관심 기업/핵심 변화만 보고 · 실제 RSS 제목·공시 + 기업 검토서/재무·가치 가정 · 뉴스 본문/AI 해석 미연결 · 보고 모듈 주문 권한 없음");
    }
    private Target readTarget(){return !settings.configured()?null:jdbc.query("SELECT * FROM discord_report_target WHERE target_key=?",this::target,settings.targetKey()).stream().findFirst().orElse(null);}
    private Target target(ResultSet rs,int n)throws SQLException{return new Target(rs.getLong("last_report_id"),rs.getString("last_meeting_hash"),instant(rs,"last_meeting_at"),instant(rs,"connected_at"),rs.getString("disabled_reason"));}
    private Delivery delivery(ResultSet rs,int n)throws SQLException{return new Delivery(rs.getLong("id"),rs.getString("author"),rs.getString("content"),rs.getInt("attempts"));}
    private static boolean weekday(LocalDate day){return day.getDayOfWeek()!=DayOfWeek.SATURDAY&&day.getDayOfWeek()!=DayOfWeek.SUNDAY;}
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private record ThesisCursor(String fingerprint,Instant queuedAt){}
    private record IdeaCursor(long id,String fingerprint,Instant sentAt){}
    private record Target(long lastReportId,String lastMeetingHash,Instant lastMeetingAt,Instant connectedAt,String disabledReason){}
    private record Delivery(long id,String author,String content,int attempts){}
    public record StateCount(String state,long count){}
    public record History(long id,String author,String kind,String state,Instant createdAt,Instant sentAt,String issue){}
    public record View(boolean configured,boolean enabled,boolean deliveryConfirmed,String readiness,String issue,String timezone,List<String>reportSlots,long progressMinutes,
                       Instant connectedAt,Instant lastSentAt,List<StateCount>counts,List<History>history,String scope){}
}
