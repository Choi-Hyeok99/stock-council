package com.personaltrader.research;

import com.fasterxml.jackson.databind.*;
import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.broker.ExecutionBrokerClient.Environment;
import com.personaltrader.marketdata.UniverseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Official, read-only financial evidence. Never orders, target prices or synthetic amounts. */
@Service
public class DartFinancialService {
    @org.springframework.beans.factory.annotation.Autowired private ResearchWorkspace workspace;

    public static final String VERSION="DART_FINANCIAL_REVIEW_V3";
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final UniverseService universe;
    private final ExecutionBrokerClient broker;
    private final Environment environment;
    private final TransactionTemplate tx;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile View latest;
    private volatile String latestScope;
    private volatile String issue;
    private volatile Instant lastCycleAt;
    private String lastFingerprint;
    private Instant mappingRetry=Instant.EPOCH,globalRetry=Instant.EPOCH;
    private int rotation;

    public DartFinancialService(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,UniverseService universe,
            ExecutionBrokerClient broker,@Value("${execution.environment:PAPER}") String environment,
            PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.universe=universe;this.broker=broker;
        this.environment=Environment.valueOf(environment.toUpperCase(Locale.ROOT));tx=new TransactionTemplate(transactions);
    }
    private String scope(){return workspace.id();}
    private Map<String,Target> targets(String scope){
        Map<String,Target> result=new LinkedHashMap<>();
        if(scope!=null)jdbc.query("SELECT stock_code,stock_name FROM execution_position WHERE scope_id=? ORDER BY opened_at,stock_code",
            (rs,n)->new Target(rs.getString(1),rs.getString(2),true),scope).forEach(t->result.put(t.code(),t));
        universe.snapshot().stocks().stream().limit(10).forEach(s->result.putIfAbsent(s.code(),new Target(s.code(),s.name(),false)));
        if(scope!=null){
            var recent=jdbc.query("""
                SELECT stock_code,name FROM (
                  SELECT DISTINCT ON(stock_code) stock_code,report->>'name' AS name,generated_at
                  FROM committee_brief WHERE scope_id=? AND agenda='BUY_REVIEW' AND generated_at>=?
                  ORDER BY stock_code,id DESC
                ) latest ORDER BY generated_at DESC,stock_code LIMIT 30
                """,(rs,n)->new Target(rs.getString(1),rs.getString(2),false),scope,utc(clock.instant().minus(Duration.ofDays(1))));
            for(var t:recent){if(result.size()>=40)break;result.putIfAbsent(t.code(),t);}
        }
        return result;
    }
    @Scheduled(fixedDelay=15000,initialDelay=10000)
    public synchronized void collect(){
        try{
            Instant start=clock.instant();String scope=scope();Map<String,Target> targets=targets(scope);
            String key=key();issue=null;int calls=0;
            if(key!=null&&!start.isBefore(globalRetry)){
                boolean mapped=ensureMapping(key);
                if(mapped&&!targets.isEmpty()){
                    // First pass collects every held company before watchlist; subsequent passes rotate fairly.
                    List<Target> list=new ArrayList<>(targets.values());
                    int begin=Math.floorMod(rotation,list.size());
                    outer:for(int offset=0;offset<list.size();offset++){
                        int index=(begin+offset)%list.size();Target target=list.get(index);
                        var corp=corporation(target.code());if(corp==null)continue;
                        Map<String,Fetch> cache=fetches(target.code());
                        for(RequestSpec spec:plan(start.atZone(SEOUL).getYear())){
                            Fetch cfs=cache.get(spec.cacheKey("CFS"));
                            if(due(cfs,start)){
                                Fetch fresh=fetch(key,target.code(),corp.code(),spec,"CFS");calls++;
                                cache.put(spec.cacheKey("CFS"),fresh);cfs=fresh;
                                if(blocked(fresh.status())){globalRetry=clock.instant().plus(Duration.ofHours(1));issue="OpenDART 인증/한도/서비스 응답 확인 필요 · 1시간 재시도 대기";break outer;}
                            }
                            // Separate statements only after confirmed NO_DATA in consolidated statements.
                            if(cfs!=null&&cfs.status().equals("013")){
                                Fetch ofs=cache.get(spec.cacheKey("OFS"));
                                if(due(ofs,start)&&calls<6){Fetch fresh=fetch(key,target.code(),corp.code(),spec,"OFS");calls++;
                                    if(blocked(fresh.status())){globalRetry=clock.instant().plus(Duration.ofHours(1));issue="OpenDART 인증/한도/서비스 응답 확인 필요 · 1시간 재시도 대기";break outer;}}
                            }
                            if(calls>=6||Duration.between(start,clock.instant()).getSeconds()>=35){rotation=index;break outer;}
                        }
                        rotation=(index+1)%list.size();
                    }
                }
            }
            if(key==null)issue="OpenDART 인증키 입력 대기";
            else if(clock.instant().isBefore(globalRetry))issue="OpenDART 오류 후 재시도 대기 · 이전 원본과 조회 시각 확인 필요";
            List<Company> companies=new ArrayList<>();
            for(Target t:targets.values())companies.add(analyze(t,fetches(t.code()),clock.instant()));
            Instant now=clock.instant();Report report=new Report(VERSION,now,key!=null,List.copyOf(companies),
                "보유 우선·관찰 및 최근 위원회 후보 합계 최대 40개 · 최근 5개 사업연도와 당해 최신 공개 분기 · 공식 원본의 현재 제공본 기준 · 과거 시점 백테스트 입력 아님",
                "재무 수치 수집은 사업·감사·경쟁력·기업 가치 검토 완료가 아님 · 자동 매수의 실행 상태와 관문은 주문 화면에서 별도 확인");
            String json=mapper.writeValueAsString(report);
            String fingerprint=PublicInformationService.hash(mapper.writeValueAsString(List.of(key!=null,companies)));
            boolean unchanged=Objects.equals(scope,latestScope)&&latest!=null&&Objects.equals(fingerprint,lastFingerprint)
                &&!latest.generatedAt().isAfter(now)&&Duration.between(latest.generatedAt(),now).compareTo(Duration.ofHours(1))<0;
            if(!unchanged){
                Long id=jdbc.queryForObject("INSERT INTO financial_review_snapshot(scope_id,generated_at,version,report) VALUES (?,?,?,?::jsonb) RETURNING id",
                    Long.class,scope,utc(now),VERSION,json);
                latestScope=scope;latest=new View(id,now,true,issue,report,now);lastFingerprint=fingerprint;
            }
            lastCycleAt=now;
        }catch(Exception failure){
            if(failure instanceof InterruptedException){Thread.currentThread().interrupt();return;}
            issue="재무 수집/기록 실패 · 원문 오류와 비밀값은 출력하지 않음";
        }
    }
    private static boolean blocked(String status){return Set.of("010","011","012","020","800","901").contains(status);}
    private static List<RequestSpec> plan(int year){
        List<RequestSpec> requests=new ArrayList<>();requests.add(new RequestSpec(year-1,"11011"));
        requests.add(new RequestSpec(year,"11014"));requests.add(new RequestSpec(year,"11012"));requests.add(new RequestSpec(year,"11013"));
        for(int y=year-2;y>=year-5;y--)requests.add(new RequestSpec(y,"11011"));
        return requests;
    }
    private boolean due(Fetch old,Instant now){
        if(old==null)return true;
        Duration age=Duration.between(old.at(),now);
        if(age.isNegative())return true;
        return age.compareTo(old.status().equals("000")?Duration.ofHours(24):old.status().equals("013")?Duration.ofHours(6):Duration.ofMinutes(30))>=0;
    }
    private boolean ensureMapping(String key)throws Exception{
        var newest=jdbc.query("SELECT MAX(fetched_at) FROM dart_corporation",(rs,n)->rs.getObject(1,OffsetDateTime.class)).getFirst();
        Instant now=clock.instant();
        if(newest!=null&&!newest.toInstant().isAfter(now)&&Duration.between(newest.toInstant(),now).compareTo(Duration.ofDays(7))<0)return true;
        if(now.isBefore(mappingRetry)){issue="DART 기업 코드 갱신 재시도 대기";return false;}
        mappingRetry=now.plus(Duration.ofMinutes(30));
        byte[] compressed=get("corpCode.xml","crtfc_key="+key,12000000);
        byte[] xml=null;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(compressed))){
            var entry=zip.getNextEntry();if(entry==null||!entry.getName().equalsIgnoreCase("CORPCODE.xml"))throw new IOException();
            xml=zip.readNBytes(64000001);if(xml.length>64000000||zip.getNextEntry()!=null)throw new IOException();
        }
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var doc=builder.parse(new ByteArrayInputStream(xml));if(!doc.getDocumentElement().getTagName().equals("result"))throw new IOException();
        Map<String,Corp> all=new LinkedHashMap<>();Set<String> identifiers=new HashSet<>();NodeList items=doc.getElementsByTagName("list");
        for(int i=0;i<items.getLength();i++){
            Element e=(Element)items.item(i);String stock=field(e,"stock_code").strip();if(!stock.matches("[0-9]{6}"))continue;
            String code=field(e,"corp_code").strip(),name=clean(field(e,"corp_name"));
            if(!code.matches("[0-9]{8}")||name.isBlank()||all.containsKey(stock)||!identifiers.add(code))throw new IOException();
            LocalDate modified=LocalDate.parse(field(e,"modify_date").strip(),DateTimeFormatter.BASIC_ISO_DATE);
            if(modified.isAfter(now.atZone(SEOUL).toLocalDate()))throw new IOException();
            all.put(stock,new Corp(code,name,modified));
        }
        if(all.size()<1000)throw new IOException();
        tx.executeWithoutResult(s->{jdbc.update("DELETE FROM dart_corporation");
            jdbc.batchUpdate("INSERT INTO dart_corporation(stock_code,corp_code,corp_name,modified_date,fetched_at) VALUES (?,?,?,?,?)",
                all.entrySet(),500,(ps,e)->{ps.setString(1,e.getKey());ps.setString(2,e.getValue().code());ps.setString(3,e.getValue().name());
                    ps.setObject(4,e.getValue().modified());ps.setObject(5,utc(now));});});
        return true;
    }
    private Corp corporation(String stock){return jdbc.query("SELECT corp_code,corp_name,modified_date FROM dart_corporation WHERE stock_code=?",
        (rs,n)->new Corp(rs.getString(1),rs.getString(2),rs.getObject(3,LocalDate.class)),stock).stream().findFirst().orElse(null);}
    private Fetch fetch(String key,String stock,String corp,RequestSpec spec,String fs)throws Exception{
        String status;List<Account> accounts=new ArrayList<>();
        try{
            JsonNode root=mapper.readTree(get("fnlttSinglAcntAll.json","crtfc_key="+key+"&corp_code="+corp+"&bsns_year="+spec.year()+"&reprt_code="+spec.report()+"&fs_div="+fs,3000000));
            status=root.path("status").asText();if(!status.matches("[0-9]{3}"))throw new IOException();
            if(status.equals("000")){
                JsonNode rows=root.path("list");if(!rows.isArray()||rows.isEmpty()||rows.size()>3000)throw new IOException();
                Set<String> receipts=new HashSet<>();
                LocalDate today=clock.instant().atZone(SEOUL).toLocalDate();
                for(JsonNode row:rows){
                    String receipt=row.path("rcept_no").asText();
                    if(!corp.equals(row.path("corp_code").asText())||!String.valueOf(spec.year()).equals(row.path("bsns_year").asText())
                        ||!spec.report().equals(row.path("reprt_code").asText())||!receipt.matches("[0-9]{14}")
                        ||LocalDate.parse(receipt.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE).isAfter(today))throw new IOException();
                    receipts.add(receipt);
                    accounts.add(new Account(receipt,clean(row.path("sj_div").asText()),clean(row.path("account_id").asText()),
                        clean(row.path("account_nm").asText()),clean(row.path("account_detail").asText()),clean(row.path("currency").asText()),
                        clean(row.path("thstrm_nm").asText()),rawAmount(row,"thstrm_amount"),rawAmount(row,"thstrm_add_amount"),
                        clean(row.path("frmtrm_nm").asText()),rawAmount(row,"frmtrm_amount"),rawAmount(row,"frmtrm_add_amount"),rawAmount(row,"frmtrm_q_amount")));
                }
                if(receipts.size()!=1)throw new IOException();
            }
        }catch(InterruptedException e){throw e;}catch(Exception failure){status="ERROR";accounts.clear();}
        Instant at=clock.instant();String json=mapper.writeValueAsString(accounts),hash=PublicInformationService.hash(json);
        Long id=jdbc.queryForObject("INSERT INTO dart_financial_fetch(stock_code,corp_code,business_year,report_code,fs_div,fetched_at,status,accounts,content_hash) VALUES (?,?,?,?,?,?,?,?::jsonb,?) RETURNING id",
            Long.class,stock,corp,spec.year(),spec.report(),fs,utc(at),status,json,hash);
        return new Fetch(id,spec.year(),spec.report(),fs,at,status,List.copyOf(accounts),hash);
    }
    byte[] get(String endpoint,String query,int limit)throws Exception{
        // Credential-bearing URI stays in process memory. Do not log requests or exceptions.
        var request=HttpRequest.newBuilder(URI.create("https://opendart.fss.or.kr/api/"+endpoint+"?"+query))
            .timeout(Duration.ofSeconds(12)).header("User-Agent","PersonalFinancialReader/1.0").GET().build();
        var pending=http.sendAsync(request,info->new LimitedBody(limit));
        try{var response=pending.get(16,TimeUnit.SECONDS);if(response.statusCode()!=200)throw new IOException();return response.body();}
        catch(Exception failure){pending.cancel(true);throw failure;}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer input:buffers){int count=input.remaining();if(count>limit-buffer.size()){
                subscription.cancel();result.completeExceptionally(new IOException());return;}
                byte[] bytes=new byte[count];input.get(bytes);buffer.writeBytes(bytes);}
            subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(new IOException());}
        public void onComplete(){result.complete(buffer.toByteArray());}
    }
    private Map<String,Fetch> fetches(String stock){
        Map<String,Fetch> result=new HashMap<>();
        jdbc.query("SELECT DISTINCT ON(business_year,report_code,fs_div) * FROM dart_financial_fetch WHERE stock_code=? ORDER BY business_year,report_code,fs_div,id DESC",
            (rs,n)->{try{return new Fetch(rs.getLong("id"),rs.getInt("business_year"),rs.getString("report_code"),rs.getString("fs_div"),
                rs.getObject("fetched_at",OffsetDateTime.class).toInstant(),rs.getString("status"),
                List.of(mapper.readValue(rs.getString("accounts"),Account[].class)),rs.getString("content_hash"));}
                catch(Exception e){throw new IllegalStateException("재무 원본 읽기 실패");}},stock)
            .forEach(f->result.put(f.year()+"/"+f.report()+"/"+f.fs(),f));
        return result;
    }
    private Fetch selected(Map<String,Fetch> cache,RequestSpec spec){
        Fetch cfs=cache.get(spec.cacheKey("CFS"));
        if(cfs!=null&&cfs.status().equals("000"))return cfs;
        if(cfs!=null&&cfs.status().equals("013")){Fetch ofs=cache.get(spec.cacheKey("OFS"));if(ofs!=null&&ofs.status().equals("000"))return ofs;}
        return null;
    }
    private String requestState(Map<String,Fetch> cache,RequestSpec spec){
        Fetch cfs=cache.get(spec.cacheKey("CFS"));if(cfs==null)return "WAITING";
        if(cfs.status().equals("013")){Fetch ofs=cache.get(spec.cacheKey("OFS"));return ofs==null?"WAITING_OFS":ofs.status().equals("013")?"NO_DATA":ofs.status().equals("000")?"READY":"ERROR";}
        return cfs.status().equals("000")?"READY":"ERROR";
    }
    private Company analyze(Target target,Map<String,Fetch> cache,Instant now){
        int year=now.atZone(SEOUL).getYear();List<Period> annuals=new ArrayList<>();List<Coverage> coverage=new ArrayList<>();
        for(RequestSpec spec:plan(year)){
            Fetch f=selected(cache,spec);Fetch attempted=cache.get(spec.cacheKey("CFS"));
            coverage.add(new Coverage(spec.year(),spec.report(),periodName(spec.year(),spec.report()),requestState(cache,spec),f==null?null:f.fs(),
                f!=null?f.at():attempted==null?null:attempted.at()));
            if(spec.report().equals("11011"))annuals.add(f==null?empty(spec,requestState(cache,spec)):period(f,now));
        }
        Period interim=null;
        for(String code:List.of("11014","11012","11013")){
            var spec=new RequestSpec(year,code);Fetch found=selected(cache,spec);
            if(found!=null){interim=period(found,now);break;}
            // A newer missing/failed attempt cannot silently certify an older quarter as latest.
            if(!requestState(cache,spec).equals("NO_DATA"))break;
        }
        annuals.sort(Comparator.comparingInt(Period::year));
        boolean financial=target.name().matches(".*(금융|은행|보험|증권|카드).*" )
            ||cache.values().stream().flatMap(f->f.accounts().stream()).anyMatch(a->Set.of("IS","CIS").contains(a.section())&&Set.of("보험영업수익","보험서비스수익","순이자손익").contains(normalize(a.name())));
        List<Insight> insights=new ArrayList<>();
        List<Period> ready=annuals.stream().filter(p->p.state().equals("READY")).toList();
        Period last=annuals.getLast();
        if(last.state().equals("READY")){
            Metric rev=metric(last,"REVENUE"),op=metric(last,"OPERATING_PROFIT"),net=metric(last,"NET_PROFIT"),cf=metric(last,"OPERATING_CASH_FLOW");
            insights.add(ratio("영업이익률",op,rev,"영업이익 ÷ 매출 × 100"));
            if(financial){insights.add(new Insight("업종별 해석","REVIEW_REQUIRED",null,"금융업 후보: 부채비율·영업현금흐름·FCF를 제조업 기준으로 평가하지 않음. 업종별 자본/건전성·지급여력 분석은 미연결",List.of()));}
            else{
                insights.add(ratio("부채비율",metric(last,"LIABILITIES"),metric(last,"EQUITY"),"부채총계 ÷ 자본총계 × 100 · 차입금 비율과 다름"));
                Metric equity=metric(last,"EQUITY");BigDecimal prior=equity.previous();
                if(net.value()!=null&&equity.value()!=null&&prior!=null&&prior.signum()>0&&equity.value().signum()>0&&sameCurrency(net,equity))
                    insights.add(new Insight("총자본 ROE 참고치","CALCULATED",pct(net.value(),equity.value().add(prior).divide(BigDecimal.valueOf(2))),
                        "총당기순이익 ÷ 기초·기말 총자본 평균 × 100 · 지배주주 ROE 아님",refs(net,equity)));
                else insights.add(unknown("총자본 ROE 참고치","순이익·기초/기말 자본·동일 통화 입력 부족"));
                if(cf.value()!=null&&net.value()!=null&&sameCurrency(cf,net)){
                    String reason="영업현금흐름 "+amount(cf.value())+"원 / 순이익 "+amount(net.value())+"원";
                    insights.add(new Insight("이익과 현금","OBSERVATION",null,reason+(cf.value().signum()<0?" · 영업현금 유출, 원문 원인 검토 필요":" · 현금흐름표와 이익의 차이 원문 검토 필요"),refs(cf,net)));
                }else insights.add(unknown("이익과 현금","영업현금흐름 또는 순이익·통화 확인 부족"));
                Metric ppe=metric(last,"PPE_PURCHASE"),intang=metric(last,"INTANGIBLE_PURCHASE");
                if(cf.value()!=null&&ppe.value()!=null&&intang.value()!=null&&sameCurrency(cf,ppe)&&sameCurrency(cf,intang))
                    insights.add(new Insight("단순 FCF 참고액","CALCULATED",cf.value().subtract(ppe.value().abs()).subtract(intang.value().abs()),
                        "영업현금흐름 − 유형자산 취득 지출 − 무형자산 취득 지출 · 인수/리스 등 미반영, 소유주이익 아님",refs(cf,ppe,intang)));
                else insights.add(unknown("단순 FCF 참고액","영업현금흐름·유형/무형자산 취득 중 입력 부족 · 누락액을 0으로 채우지 않음"));
            }
            if(annuals.size()>=2){Period previous=annuals.get(annuals.size()-2);
                insights.add(growth("전년 매출 변화",last,previous,"REVENUE"));insights.add(growth("전년 영업이익 변화",last,previous,"OPERATING_PROFIT"));}
        }
        if(interim!=null){Metric rev=metric(interim,"REVENUE");
            if(rev.value()!=null&&rev.previous()!=null&&rev.previous().signum()>0)
                insights.add(new Insight("최신 분기 누적 매출 변화","CALCULATED",pct(rev.value().subtract(rev.previous()),rev.previous()),
                    periodName(interim.year(),interim.report())+" 누적 대 전년 동기간 누적 · 한 분기와 연간을 비교하지 않음",refs(rev)));
            else insights.add(unknown("최신 분기 누적 매출 변화","전년 동기간 누적 금액 확인 부족"));
        }
        boolean complete=ready.size()==5;
        int netPos=0,cashPos=0;
        for(Period p:ready){if(metric(p,"NET_PROFIT").value()!=null&&metric(p,"NET_PROFIT").value().signum()>0)netPos++;
            if(metric(p,"OPERATING_CASH_FLOW").value()!=null&&metric(p,"OPERATING_CASH_FLOW").value().signum()>0)cashPos++;}
        if(!ready.isEmpty())insights.add(new Insight("다년 관측","OBSERVATION",null,"수집 연간 "+ready.size()+"/5개 · 순이익 양수 "+netPos+"개 · 영업현금흐름 양수 "+cashPos+"개 · 미확인 계정은 집계에서 양수로 보지 않음 · 일관된 경쟁력/미래 수익의 증거 아님",List.of()));
        List<String> limits=new ArrayList<>(List.of("사업 구조·감사의견·주석·차입금 만기·주식 수/희석·기업 가치 검토는 미연결",
            "공시 접수일과 실제 API 조회 시각을 분리 · 최초 공시 시점의 값은 재구성하지 않음",
            "매일 재조회한 현재 제공본을 원본 해시와 함께 보관 · 정정 이전 값/전체 정정 이력 확보를 보장하지 않음",
            "계정 ID·정확한 이름으로 단일 계정만 연결 · 별도 계정이나 세부 항목을 임의 합산하지 않음"));
        if(!complete)limits.add("5개 연간 자료 수집 대기/누락/오류 · 아래 기간별 상태 확인");
        if(interim==null)limits.add("당해 최신 분기 미확인 · 분기 조회 상태 확인");
        if(financial)limits.add("금융업 후보 분류는 이름/재무 계정 기반의 보수적 감지이며 공식 업종 분류 확정 아님");
        Corp corp=corporation(target.code());
        return new Company(target.code(),target.name(),target.held(),corp==null?null:corp.name(),financial,
            corp==null?"MAPPING_WAIT":complete?"DATA_COLLECTED":"COLLECTING",ready.size(),5,List.copyOf(annuals),interim,List.copyOf(insights),List.copyOf(coverage),List.copyOf(limits));
    }
    private Period empty(RequestSpec spec,String state){return new Period(spec.year(),spec.report(),periodName(spec.year(),spec.report()),state,null,null,null,null,null,List.of(),null,false);}
    private Period period(Fetch f,Instant now){
        List<Metric> metrics=new ArrayList<>();
        metrics.add(resolve(f,"REVENUE","매출","INCOME",Set.of("ifrs-full_Revenue","ifrs-full_RevenueFromContractsWithCustomers"),Set.of("매출액","매출","수익(매출액)","영업수익")));
        metrics.add(resolve(f,"OPERATING_PROFIT","영업이익","INCOME",Set.of("dart_OperatingIncomeLoss"),Set.of("영업이익","영업이익(손실)","영업손익")));
        metrics.add(resolve(f,"NET_PROFIT","총당기순이익","INCOME",Set.of("ifrs-full_ProfitLoss"),Set.of("당기순이익","당기순이익(손실)","당기순손익","분기순이익","반기순이익")));
        metrics.add(resolve(f,"OWNER_PROFIT","지배주주 순이익","INCOME",Set.of("ifrs-full_ProfitLossAttributableToOwnersOfParent"),Set.of()));
        metrics.add(resolve(f,"OWNER_EQUITY","지배주주 자본","BS",Set.of("ifrs-full_EquityAttributableToOwnersOfParent"),Set.of()));
        metrics.add(resolve(f,"ASSETS","자산총계","BS",Set.of("ifrs-full_Assets"),Set.of("자산총계")));
        metrics.add(resolve(f,"LIABILITIES","부채총계","BS",Set.of("ifrs-full_Liabilities"),Set.of("부채총계")));
        metrics.add(resolve(f,"EQUITY","자본총계","BS",Set.of("ifrs-full_Equity"),Set.of("자본총계")));
        metrics.add(resolve(f,"CASH","현금및현금성자산","BS",Set.of("ifrs-full_CashAndCashEquivalents"),Set.of("현금및현금성자산")));
        metrics.add(resolve(f,"OPERATING_CASH_FLOW","영업현금흐름","CF",Set.of("ifrs-full_CashFlowsFromUsedInOperatingActivities"),Set.of("영업활동현금흐름","영업활동으로인한현금흐름","영업활동으로부터의현금흐름","영업활동으로인한순현금흐름")));
        metrics.add(resolve(f,"PPE_PURCHASE","유형자산 취득","CF",Set.of("ifrs-full_PurchaseOfPropertyPlantAndEquipment","ifrs-full_PaymentsToAcquirePropertyPlantAndEquipment"),Set.of("유형자산의취득","유형자산취득")));
        metrics.add(resolve(f,"INTANGIBLE_PURCHASE","무형자산 취득","CF",Set.of("ifrs-full_PurchaseOfIntangibleAssets","ifrs-full_PaymentsToAcquireIntangibleAssets"),Set.of("무형자산의취득","무형자산취득")));
        String receipt=f.accounts().getFirst().receipt();LocalDate published=LocalDate.parse(receipt.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE);
        boolean revised=jdbc.queryForObject("SELECT COUNT(DISTINCT content_hash)>1 FROM dart_financial_fetch WHERE stock_code=(SELECT stock_code FROM dart_financial_fetch WHERE id=?) AND business_year=? AND report_code=? AND fs_div=? AND status='000'",Boolean.class,f.id(),f.year(),f.report(),f.fs());
        return new Period(f.year(),f.report(),periodName(f.year(),f.report()),"READY",f.fs(),f.id(),f.at(),published,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,List.copyOf(metrics),f.hash(),revised);
    }
    private Metric resolve(Fetch f,String key,String label,String section,Set<String> ids,Set<String> names){
        String sj=section;if(section.equals("INCOME"))sj=f.accounts().stream().anyMatch(a->a.section().equals("IS"))?"IS":"CIS";
        final String wanted=sj;
        var rows=f.accounts().stream().filter(a->a.section().equals(wanted))
            .filter(a->ids.contains(a.accountId())||names.contains(normalize(a.name()))).toList();
        String basis=section.equals("BS")?"기말 잔액":f.report().equals("11011")?"연간":section.equals("INCOME")?"사업연도 시작부터 누적":"사업연도 시작부터 누적(현금흐름표)";
        if(rows.size()!=1)return new Metric(key,label,rows.isEmpty()?"MISSING":"AMBIGUOUS",null,null,null,null,null,basis,
            rows.isEmpty()?"일치 계정 없음":"일치 계정 여러 개 · 자동 선택 보류");
        Account a=rows.getFirst();boolean cumulative=section.equals("INCOME")&&!f.report().equals("11011");
        BigDecimal value=number(cumulative?a.cumulative():a.current());
        BigDecimal previous=number(cumulative?a.priorCumulative():a.prior());
        String currency=a.currency();
        if(!currency.equals("KRW"))return new Metric(key,label,"CURRENCY_WAIT",null,null,currency,a.accountId(),a.name(),basis,"KRW 금액/단위 확인 필요 · 환산/추정하지 않음");
        return new Metric(key,label,value==null?"MISSING":"AVAILABLE",value,previous,currency,a.accountId(),a.name(),basis,
            value==null?"필요한 당기/누적 금액 없음 · 3개월 금액을 누적으로 대신하지 않음":"OpenDART 전체 재무제표 · KRW 원 단위 · "+a.currentName());
    }
    private static Metric metric(Period p,String key){return p.metrics().stream().filter(m->m.key().equals(key)).findFirst().orElse(new Metric(key,key,"MISSING",null,null,null,null,null,"미확인","입력 없음"));}
    private static boolean sameCurrency(Metric a,Metric b){return a.currency()!=null&&a.currency().equals(b.currency());}
    private static List<String> refs(Metric... metrics){return Arrays.stream(metrics).map(m->m.label()+" "+(m.value()==null?"미확인":amount(m.value())+"원")+" ["+m.accountId()+"]"+(m.previous()==null?"":" · 비교 금액 "+amount(m.previous())+"원")).toList();}
    private static Insight unknown(String label,String why){return new Insight(label,"UNKNOWN",null,why,List.of());}
    private static Insight ratio(String label,Metric a,Metric b,String formula){
        if(a.value()==null||b.value()==null||b.value().signum()<=0||!sameCurrency(a,b))return unknown(label,"분자/양수 분모/동일 통화 확인 부족");
        return new Insight(label,"CALCULATED",pct(a.value(),b.value()),formula,refs(a,b));
    }
    private static Insight growth(String label,Period now,Period old,String key){
        Metric a=metric(now,key),b=metric(old,key);
        if(!old.state().equals("READY")||!Objects.equals(now.fs(),old.fs())||a.value()==null||b.value()==null||!sameCurrency(a,b))
            return unknown(label,"연속 연도·동일 연결/별도·통화·계정 입력 확인 부족");
        if(a.previous()==null||a.previous().compareTo(b.value())!=0)return new Insight(label,"REVIEW_REQUIRED",null,
            "최근 보고서의 전기 비교 금액과 전년도 제공본이 다르거나 비교 금액이 없음 · 정정/재분류/기준 검토 전 성장률 보류",refs(a,b));
        if(b.value().signum()<=0)return new Insight(label,"OBSERVATION",null,"전년 "+amount(b.value())+"원 → 당해 "+amount(a.value())+"원 · 전년 0/적자일 때 성장률 %는 계산하지 않음",refs(a,b));
        return new Insight(label,"CALCULATED",pct(a.value().subtract(b.value()),b.value()),old.year()+"년 대비 "+now.year()+"년 · (당해−전년) ÷ 전년 × 100",refs(a,b));
    }
    private static BigDecimal pct(BigDecimal a,BigDecimal b){return a.multiply(BigDecimal.valueOf(100)).divide(b,2,RoundingMode.HALF_UP);}
    private static String amount(BigDecimal value){return String.format(Locale.KOREA,"%,.0f",value);}
    private static BigDecimal number(String text){if(text==null||text.isBlank()||text.length()>80)return null;String value=text.replace(",","").replace(" ","").strip();
        if(value.matches("\\([0-9]+\\)"))value="-"+value.substring(1,value.length()-1);
        return value.matches("-?[0-9]{1,40}")?new BigDecimal(value):null;}
    private static String rawAmount(JsonNode row,String field){return clean(row.path(field).asText());}
    private static String normalize(String value){return value.replaceAll("\\s","");}
    private static String clean(String value){return PublicInformationService.plain(value,500);}
    private static String field(Element e,String field){var n=e.getElementsByTagName(field);return n.getLength()==0?"":n.item(0).getTextContent();}
    private static String periodName(int year,String report){return year+"년 "+switch(report){case "11011"->"연간";case "11014"->"3분기";case "11012"->"반기";default->"1분기";};}
    private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
    static String key(){try{Path p=Path.of(".env.dart");if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)||Files.size(p)>4096)return null;
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));Properties props=new Properties();props.load(new StringReader(Files.readString(p)));
        String key=props.getProperty("DART_API_KEY","").strip();return key.matches("[A-Za-z0-9]{40}")?key:null;}catch(Exception ignored){return null;}}
    public View view(){
        Instant now=clock.instant();String scope=scope();View v=Objects.equals(scope,latestScope)?latest:null;
        if(v==null){var saved=jdbc.query("SELECT id,generated_at,report FROM financial_review_snapshot WHERE scope_id IS NOT DISTINCT FROM ? ORDER BY id DESC LIMIT 1",
            (rs,n)->{try{return new View(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),false,"저장된 이전 실행 기록 · 새 수집 대기",mapper.readValue(rs.getString(3),Report.class),null);}
                catch(Exception e){throw new IllegalStateException("재무 기록 읽기 실패");}},scope);return saved.isEmpty()?new View(null,null,false,"첫 재무 수집 대기",null,null):saved.getFirst();}
        return new View(v.id(),v.generatedAt(),issue==null&&lastCycleAt!=null&&!lastCycleAt.isAfter(now)&&Duration.between(lastCycleAt,now).getSeconds()<150,issue,v.report(),lastCycleAt);
    }
    public Company company(String code){View v=view();return v.report()==null?null:v.report().companies().stream().filter(c->c.code().equals(code)).findFirst().orElse(null);}
    public Optional<View> find(long id){return jdbc.query("SELECT id,generated_at,report FROM financial_review_snapshot WHERE id=? AND scope_id IS NOT DISTINCT FROM ?",
        (rs,n)->{try{return new View(rs.getLong(1),rs.getObject(2,OffsetDateTime.class).toInstant(),false,"당시 자료 · 현재 판단 아님",mapper.readValue(rs.getString(3),Report.class),null);}
            catch(Exception e){throw new IllegalStateException("재무 기록 읽기 실패");}},id,scope()).stream().findFirst();}
    private record Corp(String code,String name,LocalDate modified){}
    private record Target(String code,String name,boolean held){}
    private record RequestSpec(int year,String report){String cacheKey(String fs){return year+"/"+report+"/"+fs;}}
    private record Fetch(long id,int year,String report,String fs,Instant at,String status,List<Account> accounts,String hash){}
    public record Account(String receipt,String section,String accountId,String name,String detail,String currency,String currentName,
        String current,String cumulative,String priorName,String prior,String priorCumulative,String priorQuarter){}
    public record Metric(String key,String label,String state,BigDecimal value,BigDecimal previous,String currency,String accountId,String accountName,String basis,String note){}
    public record Insight(String label,String state,BigDecimal value,String explanation,List<String> inputs){}
    public record Period(int year,String report,String label,String state,String fs,Long rawId,Instant fetchedAt,LocalDate disclosureDate,
        String sourceUrl,List<Metric> metrics,String contentHash,boolean changedSinceFirstFetch){}
    public record Coverage(int year,String report,String label,String state,String fs,Instant lastAttemptAt){}
    public record Company(String code,String name,boolean held,String officialName,boolean financialSectorReview,String state,int annualCollected,int annualRequired,
        List<Period> annuals,Period latestInterim,List<Insight> insights,List<Coverage> coverage,List<String> limitations){}
    public record Report(String version,Instant generatedAt,boolean keyConfigured,List<Company> companies,String coverage,String scope){}
    public record View(Long id,Instant generatedAt,boolean current,String issue,Report report,Instant checkedAt){}
}
