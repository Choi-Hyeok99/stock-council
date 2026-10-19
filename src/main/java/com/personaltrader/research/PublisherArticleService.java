package com.personaltrader.research;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** A small allowlisted reader for publisher-labelled free articles. No credentials, redirects or tools. */
@Service
public class PublisherArticleService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Pattern LD=Pattern.compile("(?is)<script\\b[^>]{0,2048}type=[\"']application/ld\\+json[\"'][^>]{0,2048}>(.{0,200000}?)</script>");
    private static final Pattern DIV=Pattern.compile("(?is)<(/?)div\\b[^>]{0,8192}>");
    public PublisherArticleService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
    @Scheduled(fixedDelay=120000,initialDelay=24000)
    public synchronized void collect(){
        Instant attempt=clock.instant();int saved=0,processed=0;
        try{
            var candidates=jdbc.query("""
                WITH watched AS MATERIALIZED(SELECT DISTINCT stock_code FROM committee_brief WHERE generated_at>=?)
                SELECT p.id,p.title,p.url FROM public_information p
                LEFT JOIN LATERAL (SELECT fetched_at,state FROM public_publisher_excerpt e WHERE e.information_id=p.id ORDER BY id DESC LIMIT 1) old ON TRUE
                WHERE p.kind='NEWS_RSS' AND p.source LIKE '한경%' AND p.published_at>=?
                  AND p.title NOT LIKE '[포토]%' AND p.title NOT LIKE '[사진]%'
                  AND (old.fetched_at IS NULL OR old.fetched_at<? OR (old.state='ERROR' AND old.fetched_at<?))
                ORDER BY CASE WHEN EXISTS(SELECT 1 FROM information_company c WHERE c.information_id=p.id AND c.stock_code IN (
                    SELECT b.stock_code FROM committee_ai_job j JOIN committee_manual_request m ON m.job_id=j.id JOIN committee_brief b ON b.id=j.brief_id GROUP BY b.stock_code ORDER BY MAX(m.requested_at) DESC LIMIT 3)) THEN 0
                    WHEN EXISTS(SELECT 1 FROM information_company c JOIN watched w ON w.stock_code=c.stock_code WHERE c.information_id=p.id) THEN 1
                    WHEN EXISTS(SELECT 1 FROM information_company c WHERE c.information_id=p.id) THEN 2 ELSE 3 END,
                    CASE WHEN p.title ~ '(실적|매출|영업이익|수주|계약|증자|신제품|출시|임상|규제)' THEN 0 ELSE 1 END,p.published_at DESC,p.id LIMIT 3
                """,(rs,n)->new Candidate(rs.getString(1),rs.getString(2),rs.getString(3)),utc(attempt.minus(Duration.ofHours(2))),utc(attempt.minus(Duration.ofDays(7))),utc(attempt.minus(Duration.ofHours(24))),utc(attempt.minus(Duration.ofMinutes(30))));
            for(var c:candidates){
                processed++;try{var result=read(c);persist(c.id(),result);if(result.text()!=null)saved++;}
                catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                catch(Exception e){persist(c.id(),new Excerpt(null,clock.instant(),"ERROR",false));}
            }
            status(attempt,saved,processed,false);
        }catch(Exception e){status(attempt,saved,processed,true);}
    }
    private Excerpt read(Candidate candidate)throws Exception{
        URI uri=URI.create(candidate.url());
        if(!"https".equals(uri.getScheme())||!"www.hankyung.com".equals(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null
            ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().matches("/article/[0-9]{12,16}"))return unavailable();
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).header("User-Agent","PersonalInvestmentReader/1.0").GET().build();
        var pending=http.sendAsync(request,info->new LimitedBody());HttpResponse<byte[]> response;
        try{response=pending.get(8,TimeUnit.SECONDS);}catch(Exception e){pending.cancel(true);throw e;}
        if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/html"))return unavailable();
        String html=new String(response.body(),StandardCharsets.UTF_8);
        // Require an explicit publisher declaration of free access and the same headline.
        boolean publicArticle=false;var scripts=LD.matcher(html);int nodes=0;
        while(scripts.find()&&++nodes<=20){
            JsonNode node;try{node=mapper.readTree(scripts.group(1));}catch(Exception ignored){continue;}
            if(!"NewsArticle".equals(node.path("@type").asText()))continue;
            if(!node.path("isAccessibleForFree").isBoolean()||!node.path("isAccessibleForFree").booleanValue())return unavailable();
            if(!normalize(node.path("headline").asText()).equals(normalize(candidate.title())))return unavailable();publicArticle=true;
        }
        if(!publicArticle)return unavailable();
        String safe=html.replaceAll("(?is)<(?:script|style)\\b[^>]{0,2048}>.*?</(?:script|style)>"," ");
        var target=Pattern.compile("(?is)<div\\b[^>]{0,8192}\\bid=[\"']articletxt[\"'][^>]{0,8192}>").matcher(safe);
        if(!target.find())return unavailable();int start=target.end(),end=-1,depth=1;var divs=DIV.matcher(safe);divs.region(start,safe.length());
        while(divs.find()){depth+=divs.group(1).isEmpty()?1:-1;if(depth==0){end=divs.start();break;}if(divs.end()-start>250000)return unavailable();}
        if(end<0)return unavailable();
        String body=safe.substring(start,end).replaceAll("(?is)<figure\\b[^>]{0,2048}>.*?</figure>"," ");
        String text=HtmlUtils.htmlUnescape(body.replaceAll("<[^>]{0,8192}>"," ")).replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").strip();
        if(text.length()<100)return unavailable();return new Excerpt(text.substring(0,Math.min(text.length(),1200)),clock.instant(),"READY",text.length()>1200);
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>{
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private long received;private boolean stopped;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> items){if(stopped)return;for(var item:items){received+=item.remaining();if(received>1500000){stopped=true;subscription.cancel();delegate.onError(new java.io.IOException("publisher size limit"));return;}}delegate.onNext(items);}
        public void onError(Throwable e){if(!stopped){stopped=true;delegate.onError(e);}}
        public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
    }
    private static String normalize(String value){return HtmlUtils.htmlUnescape(value).replaceAll("[\\p{Punct}\\p{P}\\s]","").strip();}
    private Excerpt unavailable(){return new Excerpt(null,clock.instant(),"NO_PUBLIC_TEXT",false);}
    private void persist(String id,Excerpt e){jdbc.update("INSERT INTO public_publisher_excerpt(information_id,fetched_at,state,excerpt,truncated,content_hash) VALUES (?,?,?,?,?,?)",
        id,utc(e.fetchedAt()),e.state(),e.text(),e.truncated(),e.text()==null?null:PublicInformationService.hash(e.text()));}
    public Excerpt latest(String id){return jdbc.query("SELECT excerpt,fetched_at,state,truncated FROM public_publisher_excerpt WHERE information_id=? ORDER BY id DESC LIMIT 1",
        (rs,n)->new Excerpt(rs.getString(1),rs.getObject(2,OffsetDateTime.class).toInstant(),rs.getString(3),rs.getBoolean(4)),id).stream().findFirst().orElse(null);}
    private void status(Instant at,int saved,int processed,boolean error){jdbc.update("""
        INSERT INTO information_source_status(source,state,last_attempt_at,last_success_at,item_count,coverage,issue)
        VALUES ('한경 공개 기사 발췌',?,?,?,?,?,?) ON CONFLICT(source) DO UPDATE SET state=EXCLUDED.state,last_attempt_at=EXCLUDED.last_attempt_at,
            last_success_at=COALESCE(EXCLUDED.last_success_at,information_source_status.last_success_at),item_count=EXCLUDED.item_count,coverage=EXCLUDED.coverage,issue=EXCLUDED.issue
        """,error?"ERROR":saved>0?"READY":"PARTIAL",utc(at),saved>0?utc(clock.instant()):null,saved,
        "명시적으로 무료인 발행처 기사만 · 최근 검토 기업 우선 · 2분마다 최대3건 · 최대1200자 · 전문/교차검증 아님",
        error?"발행처 기사 수집 확인 필요":processed>0&&saved==0?"공개 본문/같은 제목 확인 불가 · 제목만 유지":null);}
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private record Candidate(String id,String title,String url){}
    public record Excerpt(String text,Instant fetchedAt,String state,boolean truncated){}
}
