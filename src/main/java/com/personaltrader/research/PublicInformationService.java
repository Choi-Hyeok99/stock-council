package com.personaltrader.research;

import com.fasterxml.jackson.databind.*;
import com.personaltrader.marketdata.StockMasterClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded publisher-provided RSS text. No paywall bypass, arbitrary article fetching or synthetic news. */
@Service
public class PublicInformationService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final Map<String,String> FEEDS=Map.of(
        "한경 증권","https://www.hankyung.com/feed/finance",
        "한경 경제","https://www.hankyung.com/feed/economy",
        "한경 IT","https://www.hankyung.com/feed/it",
        "한경 국제","https://www.hankyung.com/feed/international",
        "한경 정치","https://www.hankyung.com/feed/politics",
        "DART RSS","https://dart.fss.or.kr/api/todayRSS.xml",
        "미 연준 통화정책","https://www.federalreserve.gov/feeds/press_monetary.xml");
    private final JdbcTemplate jdbc;
    private final StockMasterClient master;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private int issuerRotation;
    private String lastIssuerPriority;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public PublicInformationService(JdbcTemplate jdbc,StockMasterClient master,ObjectMapper mapper,Clock clock,PlatformTransactionManager transactions){
        this.jdbc=jdbc;this.master=master;this.mapper=mapper;this.clock=clock;this.tx=new TransactionTemplate(transactions);
    }
    @Scheduled(fixedDelayString="${information.poll-ms:120000}",initialDelay=5000)
    public synchronized void collect(){
        Map<String,StockMasterClient.MasterStock> catalog;
        try{catalog=master.load();}catch(RuntimeException unavailable){
            FEEDS.keySet().forEach(source->status(source,"ERROR",clock.instant(),0,"공식 종목 식별 갱신 실패","기업 연결 입력 미확인 · 신규 진입 보류"));return;
        }
        for(var feed:FEEDS.entrySet()){
            Instant attempt=clock.instant(); boolean dart=feed.getKey().equals("DART RSS");
            boolean official=feed.getKey().equals("미 연준 통화정책");
            String coverage=official?"미 연준 공식 통화정책 RSS · 제목/발행시각/제공 요약/원문 링크 · 개별 기업 수혜 검증 아님":dart?"당일 최근 최대100건(지분·펀드 제외) · 전체 공시 확인 아님":"발행처 RSS 최신100건 · 제공된 요약 최대900자 · 전체 기사 본문 확인 아님";
            try{
                byte[] body=get(URI.create(feed.getValue()));
                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
                Document doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
                if(!doc.getDocumentElement().getTagName().equals("rss"))throw new IllegalArgumentException();
                NodeList items=doc.getElementsByTagName("item"); int saved=0;
                for(int i=0;i<Math.min(100,items.getLength());i++){
                    Element item=(Element)items.item(i); String title=plain(field(item,"title"),500);
                    String link=safeUrl(field(item,"link"),feed.getKey());
                    if(title.isBlank()||link==null)continue;
                    Instant published=parseTime(field(item,"pubDate"));
                    if(published==null)published=parseTime(field(item,"dc:date"));
                    if(published!=null&&published.isAfter(clock.instant().plusSeconds(120)))continue;
                    PublisherSummary summary=dart?new PublisherSummary(null,false):publisherSummary(item,title);
                    save(feed.getKey(),dart?"DART_RSS":official?"OFFICIAL_MACRO_RSS":"NEWS_RSS",title,link,published,
                        published==null?null:published.atZone(SEOUL).toLocalDate(),null,catalog,summary);
                    saved++;
                }
                status(feed.getKey(),"READY",attempt,saved,coverage,null);
            }catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();return;} status(feed.getKey(),"ERROR",attempt,0,coverage,"피드 수집/형식 확인 실패 · 이전 자료는 최신 확인으로 쓰지 않음");}
        }
        String key=dartKey();
        if(key==null){status("OpenDART API","UNCONFIGURED",clock.instant(),0,"로컬 40자리 인증키 필요 · RSS는 별도 수집","인증키 입력 대기");return;}
        Instant attempt=clock.instant();int count=0;boolean partial=false;
        try{
            LocalDate today=attempt.atZone(SEOUL).toLocalDate();
            for(int page=1;page<=2;page++){
                URI uri=URI.create("https://opendart.fss.or.kr/api/list.json?crtfc_key="+key
                    +"&bgn_de="+today.minusDays(3).format(DateTimeFormatter.BASIC_ISO_DATE)
                    +"&end_de="+today.format(DateTimeFormatter.BASIC_ISO_DATE)+"&last_reprt_at=N&sort=date&sort_mth=desc&page_count=100&page_no="+page);
                JsonNode data=mapper.readTree(get(uri));String state=data.path("status").asText();
                if(state.equals("013"))break;
                if(!state.equals("000"))throw new IllegalArgumentException();
                for(JsonNode item:data.path("list")){
                    String code=item.path("stock_code").asText(),receipt=item.path("rcept_no").asText();
                    if(!code.matches("[0-9]{6}")||!receipt.matches("[0-9]{14}"))continue;
                    LocalDate date=LocalDate.parse(item.path("rcept_dt").asText(),DateTimeFormatter.BASIC_ISO_DATE);
                    if(date.isAfter(today))continue;
                    save("OpenDART API","DART_API",plain(item.path("corp_name").asText()+" · "+item.path("report_nm").asText(),500),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,null,date,code,catalog);count++;
                }
                int pages=data.path("total_page").asInt(1);partial=pages>2;
                if(page>=pages)break;
            }
            status("OpenDART API",partial?"PARTIAL":"READY",attempt,count,
                "최근 4일 최대 200건 · 정정 포함 · 접수일만 제공, 접수 시각 미확인"+(partial?" · 조회 페이지 상한 도달":""),null);
        }catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();return;} status("OpenDART API","ERROR",attempt,0,"키/호출 한도/응답 확인 필요","공시 API 조회 실패 · 인증키와 호출 한도를 확인해 주세요.");}
        collectIssuers(key,catalog);
    }
    /** Broad newest-200 is not issuer coverage. Rotate bounded issuer-specific official lookups. */
    private void collectIssuers(String key,Map<String,StockMasterClient.MasterStock> catalog){
        var priority=jdbc.queryForList("""
            SELECT b.stock_code FROM committee_ai_job j JOIN committee_manual_request m ON m.job_id=j.id
            JOIN committee_brief b ON b.id=j.brief_id GROUP BY b.stock_code ORDER BY MAX(m.requested_at) DESC LIMIT 3
            """,String.class);
        var candidates=new LinkedHashSet<>(priority);
        candidates.addAll(jdbc.queryForList("SELECT DISTINCT s.stock_code FROM company_public_source s WHERE s.source_kind='PROFILE' AND s.state='READY' ORDER BY s.stock_code LIMIT 40",String.class));
        var codes=List.copyOf(candidates);
        String newest=priority.isEmpty()?null:priority.getFirst();
        if(!Objects.equals(newest,lastIssuerPriority)){issuerRotation=0;lastIssuerPriority=newest;}
        if(codes.isEmpty())return;int saved=0;Instant attempt=clock.instant();boolean partial=false;
        try{
            LocalDate today=attempt.atZone(SEOUL).toLocalDate();
            for(int n=0;n<Math.min(2,codes.size());n++){
                String code=codes.get(Math.floorMod(issuerRotation++,codes.size()));
                var corp=jdbc.queryForList("SELECT corp_code FROM dart_corporation WHERE stock_code=?",String.class,code);if(corp.isEmpty())continue;
                URI uri=URI.create("https://opendart.fss.or.kr/api/list.json?crtfc_key="+key+"&corp_code="+corp.getFirst()
                    +"&bgn_de="+today.minusDays(29).format(DateTimeFormatter.BASIC_ISO_DATE)+"&end_de="+today.format(DateTimeFormatter.BASIC_ISO_DATE)
                    +"&last_reprt_at=N&sort=date&sort_mth=desc&page_count=100&page_no=1");
                JsonNode root=mapper.readTree(get(uri));if(root.path("status").asText().equals("013"))continue;
                if(!root.path("status").asText().equals("000"))throw new IOException();
                partial|=root.path("total_page").asInt(1)>1;
                for(var item:root.path("list")){
                    String receipt=item.path("rcept_no").asText();if(!receipt.matches("[0-9]{14}")||!code.equals(item.path("stock_code").asText()))continue;
                    LocalDate date=LocalDate.parse(item.path("rcept_dt").asText(),DateTimeFormatter.BASIC_ISO_DATE);if(date.isAfter(today)||date.isBefore(today.minusDays(29)))continue;
                    save("OpenDART API","DART_API",plain(item.path("corp_name").asText()+" · "+item.path("report_nm").asText(),500),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,null,date,code,catalog);saved++;
                }
            }
            status("기업별 OpenDART API",partial?"PARTIAL":"READY",attempt,saved,"최근 직접 검토 기업 우선 · 2분마다 2개 기업 순환 · 기업별 최근30일 최대100건 · 전체 공시 포괄 아님",null);
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();status("기업별 OpenDART API","ERROR",attempt,saved,"기업별 최근30일 제한 조회","기업별 공시 수집 확인 필요");}
    }
    private byte[] get(URI uri)throws Exception{
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("User-Agent","PersonalInvestmentReader/1.0").GET().build();
        HttpResponse<InputStream> response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream body=response.body()){
            if(response.statusCode()!=200)throw new IllegalArgumentException();
            byte[] bytes=body.readNBytes(1500001);if(bytes.length>1500000)throw new IllegalArgumentException();return bytes;
        }
    }
    private void save(String source,String kind,String title,String url,Instant published,LocalDate day,String issuer,
                      Map<String,StockMasterClient.MasterStock> catalog){
        save(source,kind,title,url,published,day,issuer,catalog,new PublisherSummary(null,false));
    }
    private void save(String source,String kind,String title,String url,Instant published,LocalDate day,String issuer,
                      Map<String,StockMasterClient.MasterStock> catalog,PublisherSummary summary){
        String id=hash(url);Instant now=clock.instant();
        tx.executeWithoutResult(transaction->{
        var old=jdbc.query("SELECT title,kind FROM public_information WHERE id=? FOR UPDATE",
            (rs,n)->new String[]{rs.getString(1),rs.getString(2)},id).stream().findFirst().orElse(null);
        if(old!=null&&old[1].equals("DART_API")&&kind.equals("DART_RSS")){
            jdbc.update("UPDATE public_information SET last_seen_at=? WHERE id=?",utc(now),id);return;
        }
        jdbc.update("""
            INSERT INTO public_information(id,source,kind,title,url,published_at,published_date,discovered_at,last_seen_at,publisher_summary,summary_truncated)
            VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=EXCLUDED.title,source=EXCLUDED.source,
              kind=EXCLUDED.kind,published_at=COALESCE(EXCLUDED.published_at,public_information.published_at),
              published_date=COALESCE(EXCLUDED.published_date,public_information.published_date),last_seen_at=EXCLUDED.last_seen_at,
              publisher_summary=EXCLUDED.publisher_summary,summary_truncated=EXCLUDED.summary_truncated
            """,id,source,kind,title,url,utc(published),day,utc(now),utc(now),summary.text(),summary.truncated());
        if(old!=null&&old[0].equals(title)&&old[1].equals(kind))return;
        // Rebuild matches if a publisher corrects the same URL's title. Stored idea snapshots remain immutable.
        jdbc.update("DELETE FROM information_company WHERE information_id=?",id);
        for(var company:catalog.values()){
            if(!company.allowed())continue;
            boolean direct=issuer!=null&&issuer.equals(company.code());
            boolean named=issuer==null&&explicitName(title,company.name());
            if(direct||named)jdbc.update("INSERT INTO information_company(information_id,stock_code,match_basis) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                id,company.code(),direct?"OFFICIAL_ISSUER_CODE":"EXPLICIT_NAME_IN_TITLE");
        }
        });
    }
    private boolean explicitName(String title,String name){
        String normalized=title.toLowerCase(Locale.ROOT),company=name.toLowerCase(Locale.ROOT);
        if(company.length()<3)return false;
        if(company.equals("naver"))return normalized.contains("네이버")||normalized.matches(".*(?<![a-z])naver(?![a-z]).*");
        if(company.matches("[a-z0-9 ]+"))return normalized.matches(".*(?<![a-z0-9])"+java.util.regex.Pattern.quote(company)+"(?![a-z0-9]).*");
        return normalized.contains(company);
    }
    private void status(String source,String state,Instant attempt,int count,String coverage,String issue){
        jdbc.update("""
            INSERT INTO information_source_status(source,state,last_attempt_at,last_success_at,item_count,coverage,issue)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT(source) DO UPDATE SET state=EXCLUDED.state,last_attempt_at=EXCLUDED.last_attempt_at,
              last_success_at=COALESCE(EXCLUDED.last_success_at,information_source_status.last_success_at),
              item_count=EXCLUDED.item_count,coverage=EXCLUDED.coverage,issue=EXCLUDED.issue
            """,source,state,utc(attempt),Set.of("READY","PARTIAL").contains(state)?utc(clock.instant()):null,count,coverage,issue);
    }
    public List<Evidence> evidence(String code){
        var rows=jdbc.query("""
            SELECT * FROM (
                SELECT p.*,c.match_basis,ROW_NUMBER() OVER (
                    PARTITION BY CASE WHEN p.kind NOT LIKE 'DART%' THEN 'NEWS'
                        WHEN p.title ~ '(정정|철회|감사의견|횡령|배임|소송|회생|부도|영업정지)' THEN 'MATERIAL'
                        WHEN p.title ~ '(일괄신고|투자설명서|증권발행실적보고서|파생결합증권)' THEN 'ISSUANCE'
                        ELSE 'ISSUER' END
                    ORDER BY COALESCE(p.published_at,p.published_date::timestamp AT TIME ZONE 'Asia/Seoul',p.discovered_at) DESC,p.id) AS category_rank
                FROM public_information p JOIN information_company c ON c.information_id=p.id
                WHERE c.stock_code=? AND ((p.published_at IS NOT NULL AND p.published_at>=?) OR p.published_date>=?)
            ) ranked WHERE category_rank<=25
            ORDER BY COALESCE(published_at,published_date::timestamp AT TIME ZONE 'Asia/Seoul',discovered_at) DESC,id
            """,this::mapEvidence,code,utc(clock.instant().minus(Duration.ofDays(30))),clock.instant().atZone(SEOUL).toLocalDate().minusDays(29));
        return selectEvidence(rows);
    }
    public static String evidenceKind(Evidence e){
        if(!e.kind().startsWith("DART"))return "NEWS_TITLE";
        String title=e.title();
        if(title.matches(".*(정정|철회|감사의견|횡령|배임|소송|회생|부도|영업정지).*"))return "MATERIAL_DISCLOSURE_TITLE";
        if(title.matches(".*(일괄신고|투자설명서|증권발행실적보고서|파생결합증권).*"))return "ISSUANCE_TITLE";
        return "ISSUER_DISCLOSURE_TITLE";
    }
    static List<Evidence> selectEvidence(List<Evidence> rows){
        // Recency alone can let repetitive filings hide news and material corrections.
        // Keep each category represented, preserving the original order inside each category.
        var selected=new LinkedHashMap<String,Evidence>();
        addEvidence(selected,rows,"MATERIAL_DISCLOSURE_TITLE",2,8);
        addEvidence(selected,rows,"NEWS_TITLE",3,8);
        addEvidence(selected,rows,"ISSUER_DISCLOSURE_TITLE",2,8);
        for(var row:rows)if(selected.size()<7&&!evidenceKind(row).equals("ISSUANCE_TITLE"))selected.putIfAbsent(row.id(),row);
        addEvidence(selected,rows,"ISSUANCE_TITLE",1,8);
        return List.copyOf(selected.values());
    }
    private static void addEvidence(Map<String,Evidence> selected,List<Evidence> rows,String kind,int limit,int cap){
        int added=0;for(var row:rows)if(evidenceKind(row).equals(kind)&&!selected.containsKey(row.id())&&selected.size()<cap){
            selected.put(row.id(),row);if(++added>=limit)break;
        }
    }
    public List<String> eventCandidates(){
        return jdbc.query("""
            SELECT c.stock_code FROM information_company c JOIN public_information p ON p.id=c.information_id
            WHERE p.discovered_at>=? AND (p.published_at>=? OR p.published_date>=?)
            GROUP BY c.stock_code ORDER BY MAX(p.discovered_at) DESC,c.stock_code LIMIT 20
            """,(rs,n)->rs.getString(1),utc(clock.instant().minus(Duration.ofDays(3))),utc(clock.instant().minus(Duration.ofDays(3))),
            clock.instant().atZone(SEOUL).toLocalDate().minusDays(3));
    }
    public View view(){
        List<Source> sources=jdbc.query("SELECT * FROM information_source_status ORDER BY source",(rs,n)->new Source(
            rs.getString("source"),rs.getString("state"),instant(rs,"last_attempt_at"),instant(rs,"last_success_at"),
            rs.getInt("item_count"),rs.getString("coverage"),rs.getString("issue")));
        List<Evidence> latest=jdbc.query("SELECT p.*,'MARKET_CONTEXT_ONLY' AS match_basis FROM public_information p WHERE p.published_at>=? OR p.published_date>=? ORDER BY COALESCE(p.published_at,p.discovered_at) DESC LIMIT 8",
            this::mapEvidence,utc(clock.instant().minus(Duration.ofDays(3))),clock.instant().atZone(SEOUL).toLocalDate().minusDays(3));
        return new View(dartKey()!=null,healthy(),sources,latest,"RSS 제목과 발행처 제공 요약 · 요약이 없는 피드는 제목만 · 전체 기사 본문/뉴스 재무영향 검증 아님 · 실제 재무제표는 /financials 별도 수집 · 전 종목/전체 기사 포괄 아님");
    }
    public boolean healthy(){
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_source_status WHERE source IN ('한경 증권','한경 경제','한경 IT','DART RSS') AND state='READY' AND last_success_at>=?",Long.class,
            utc(clock.instant().minus(Duration.ofMinutes(10))))==4;
    }
    private Evidence mapEvidence(ResultSet rs,int n)throws SQLException{return new Evidence(rs.getString("id"),rs.getString("source"),rs.getString("kind"),rs.getString("title"),rs.getString("url"),instant(rs,"published_at"),rs.getObject("published_date",LocalDate.class),instant(rs,"discovered_at"),rs.getString("match_basis"),rs.getString("publisher_summary"),rs.getBoolean("summary_truncated"),instant(rs,"last_seen_at"));}
    private static PublisherSummary publisherSummary(Element item,String title){
        String raw=field(item,"description");
        if(raw.isBlank())raw=field(item,"content:encoded");
        // Decode only the already downloaded XML text. No links, images or embedded resources are fetched.
        boolean clipped=raw.length()>18000;
        String bounded=raw.substring(0,Math.min(raw.length(),18000));
        String text=HtmlUtils.htmlUnescape(bounded).replaceAll("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>"," ")
            .replaceAll("<[^>]{0,8192}>"," ").replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").strip();
        text=text.replaceFirst("(?is)\\s*(?:Copyright\\s*(?:©|\\(c\\))|All rights reserved|무단전재 및 재배포 금지).*\\z", "").strip();
        if(text.length()<40||text.equals(title)||text.matches("(?is)^(RSS feed|RSS 서비스).*"))return new PublisherSummary(null,false);
        if(text.startsWith(title))text=text.substring(title.length()).strip();
        if(text.length()<40)return new PublisherSummary(null,false);
        clipped|=text.length()>900;return new PublisherSummary(text.substring(0,Math.min(text.length(),900)),clipped);
    }
    private record PublisherSummary(String text,boolean truncated){}
    private static String field(Element e,String name){NodeList list=e.getElementsByTagName(name);return list.getLength()==0?"":list.item(0).getTextContent();}
    private static Instant parseTime(String text){if(text.isBlank())return null;try{return ZonedDateTime.parse(text,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(RuntimeException ignored){try{return OffsetDateTime.parse(text).toInstant();}catch(RuntimeException invalid){return null;}}}
    public static String plain(String text,int limit){String value=text.replaceAll("<[^>]*>","").replaceAll("[\\p{Cntrl}]"," ").strip();return value.length()>limit?value.substring(0,limit):value;}
    static String safeUrl(String text,String source){try{
        if(text.length()>2048)return null;URI uri=URI.create(text.strip());String host=uri.getHost();
        if(uri.getRawUserInfo()!=null||uri.getPort()!=-1||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())))return null;
        String expected="DART RSS".equals(source)?"dart.fss.or.kr":"미 연준 통화정책".equals(source)?"www.federalreserve.gov":"www.hankyung.com";
        if(!expected.equals(host))return null;return text.strip().replaceFirst("^http:","https:");
    }catch(RuntimeException ignored){return null;}}
    /** Slow-moving monetary decisions remain dated context; never matched to an issuer by speculation. */
    public List<Evidence> macroEvidence(){return jdbc.query("SELECT p.*,'MARKET_CONTEXT_ONLY' AS match_basis FROM public_information p WHERE kind='OFFICIAL_MACRO_RSS' AND published_at>=? AND published_at<=? ORDER BY published_at DESC LIMIT 2",this::mapEvidence,utc(clock.instant().minus(Duration.ofDays(30))),utc(clock.instant()));}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception ignored){throw new IllegalStateException("기록 식별 실패");}}
    private static String dartKey(){try{Path p=Path.of(".env.dart");if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)||Files.size(p)>4096)return null;Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));Properties props=new Properties();props.load(new StringReader(Files.readString(p)));String key=props.getProperty("DART_API_KEY","").strip();return key.matches("[A-Za-z0-9]{40}")?key:null;}catch(Exception ignored){return null;}}
    private static OffsetDateTime utc(Instant at){return at==null?null:at.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime at=rs.getObject(column,OffsetDateTime.class);return at==null?null:at.toInstant();}
    public record Evidence(String id,String source,String kind,String title,String url,Instant publishedAt,LocalDate publishedDate,Instant discoveredAt,String matchBasis,String publisherSummary,boolean summaryTruncated,Instant lastSeenAt){
        public Evidence(String id,String source,String kind,String title,String url,Instant publishedAt,LocalDate publishedDate,Instant discoveredAt,String matchBasis){
            this(id,source,kind,title,url,publishedAt,publishedDate,discoveredAt,matchBasis,null,false,null);
        }
        public Evidence(String id,String source,String kind,String title,String url,Instant publishedAt,LocalDate publishedDate,Instant discoveredAt,String matchBasis,String publisherSummary,boolean summaryTruncated){
            this(id,source,kind,title,url,publishedAt,publishedDate,discoveredAt,matchBasis,publisherSummary,summaryTruncated,null);
        }
        public Instant receivedAt(){return lastSeenAt==null?discoveredAt:lastSeenAt;}
        public String contentLevel(){return publisherSummary==null||publisherSummary.isBlank()?"TITLE_ONLY":"PUBLISHER_SUMMARY";}
    }
    public record Source(String source,String state,Instant lastAttemptAt,Instant lastSuccessAt,int itemCount,String coverage,String issue){}
    public record View(boolean dartApiConfigured,boolean collectionHealthy,List<Source>sources,List<Evidence>latest,String scope){}
}
