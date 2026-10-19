package com.personaltrader.research;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Official filings are data. Extracted text never becomes executable instructions. */
@Service
public class CompanySourceService {
 private final DartFinancialService financials;private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
 private volatile String issue;private Instant retryAt=Instant.EPOCH;private int rotation;private String lastPriority;
 public CompanySourceService(DartFinancialService financials,JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.financials=financials;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
 @Scheduled(fixedDelay=30000,initialDelay=18000)
 public synchronized void collect(){
  try{
   var view=financials.view();if(view.report()==null)return;String key=DartFinancialService.key();if(key==null)return;
   if(clock.instant().isBefore(retryAt))return;issue=null;int calls=0;
   var priority=jdbc.queryForList("SELECT b.stock_code FROM committee_ai_job j JOIN committee_manual_request m ON m.job_id=j.id JOIN committee_brief b ON b.id=j.brief_id ORDER BY m.requested_at DESC LIMIT 1",String.class);
   String code=priority.isEmpty()?null:priority.getFirst();if(!Objects.equals(code,lastPriority)){rotation=0;lastPriority=code;}
   var companies=view.report().companies().stream().sorted(Comparator.comparingInt(c->c.code().equals(code)?0:1)).toList();
   for(int offset=0;offset<companies.size();offset++){
    int index=Math.floorMod(rotation+offset,companies.size());var c=companies.get(index);
    var codes=jdbc.queryForList("SELECT corp_code FROM dart_corporation WHERE stock_code=?",String.class,c.code());if(codes.isEmpty())continue;String corp=codes.getFirst();
    if(due(c.code(),"PROFILE",corp,Duration.ofDays(7))){profile(key,c.code(),corp);calls++;}
    var annual=c.annuals().stream().filter(p->p.state().equals("READY")).max(Comparator.comparingInt(DartFinancialService.Period::year)).orElse(null);
    if(annual!=null){
     String receipt=receipt(annual.sourceUrl());
     var current=c.latestInterim()!=null?c.latestInterim():annual;
     // Latest interim carries updated risk/capital facts; annual remains the business baseline.
     for(var period:current==annual?List.of(annual):List.of(current,annual)){
      String r=receipt(period.sourceUrl());
      if(calls<3&&due(c.code(),"DOCUMENT",r,Duration.ofDays(7))){
       calls++;
       try{document(key,c.code(),corp,r,period.label());}
       catch(InterruptedException e){throw e;}
       catch(Exception e){save(c.code(),"DOCUMENT",r,"ERROR",Map.of("extractorVersion",OfficialDocumentExcerpt.VERSION,"issue","공식 원문 형식/수집 확인 필요"));}
      }
     }
     String id=current.year()+"/"+current.report();
     if(calls<3&&due(c.code(),"SHARES",id,Duration.ofHours(24))){shares(key,c.code(),corp,current.year(),current.report());calls++;}
    }
    if(calls>=3){rotation=(index+1)%companies.size();break;}
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){issue="공식 기업자료 수집/형식 확인 실패 · 이전 원본 시각 확인 필요";if(retryAt.isBefore(clock.instant().plus(Duration.ofMinutes(30))))retryAt=clock.instant().plus(Duration.ofMinutes(30));}
 }
 private boolean due(String stock,String kind,String identity,Duration ttl){
  var old=source(stock,kind,identity);if(old==null)return true;
  if(kind.equals("DOCUMENT")&&old.state().equals("READY")&&old.payload().path("extractorVersion").asInt()<OfficialDocumentExcerpt.VERSION)return true;
  var age=Duration.between(old.fetchedAt(),clock.instant());return age.isNegative()||age.compareTo(old.state().equals("READY")?ttl:Duration.ofMinutes(30))>=0;
 }
 private JsonNode json(String endpoint,String query)throws Exception{
  JsonNode root=mapper.readTree(financials.get(endpoint,query,3000000));String status=root.path("status").asText();
  if(Set.of("010","011","012","020","800","901").contains(status)){retryAt=clock.instant().plus(Duration.ofHours(1));throw new IOException();}
  return root;
 }
 private void profile(String key,String stock,String corp)throws Exception{
  JsonNode root=json("company.json","crtfc_key="+key+"&corp_code="+corp);
  if(!root.path("status").asText().equals("000")){save(stock,"PROFILE",corp,root.path("status").asText().equals("013")?"NO_DATA":"ERROR",Map.of("status",root.path("status").asText()));return;}
  if(!stock.equals(root.path("stock_code").asText()))throw new IOException();
  var body=new LinkedHashMap<String,Object>();for(String field:List.of("corp_name","stock_code","induty_code","acc_mt"))body.put(field,clean(root.path(field).asText()));
  body.put("homeUrl",publicUrl(root.path("hm_url").asText()));body.put("irUrl",publicUrl(root.path("ir_url").asText()));
  save(stock,"PROFILE",corp,"READY",body);
 }
 private void shares(String key,String stock,String corp,int year,String report)throws Exception{
  String identity=year+"/"+report;JsonNode root=json("stockTotqySttus.json","crtfc_key="+key+"&corp_code="+corp+"&bsns_year="+year+"&reprt_code="+report);
  if(!root.path("status").asText().equals("000")){save(stock,"SHARES",identity,root.path("status").asText().equals("013")?"NO_DATA":"ERROR",Map.of("status",root.path("status").asText()));return;}
  List<ShareClass> rows=new ArrayList<>();
  for(var item:root.path("list")){
   if(!corp.equals(item.path("corp_code").asText()))throw new IOException();String receipt=item.path("rcept_no").asText();if(!receipt.matches("[0-9]{14}"))throw new IOException();
   String type=clean(item.path("se").asText());if(type.equals("비고"))continue;
   BigDecimal issued=number(item.path("istc_totqy").asText()),treasury=number(item.path("tesstk_co").asText()),circulating=number(item.path("distb_stock_co").asText());
   // Explicit '-' for an unused class is preserved as unknown; never infer missing non-common class = zero.
   boolean verified=issued!=null&&treasury!=null&&circulating!=null&&issued.signum()>=0&&treasury.signum()>=0&&circulating.signum()>=0&&issued.subtract(treasury).compareTo(circulating)==0;
   String date=clean(item.path("stlm_dt").asText());
   rows.add(new ShareClass(type,issued,treasury,circulating,verified,date,"https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt));
  }
  if(rows.isEmpty())throw new IOException();save(stock,"SHARES",identity,"READY",Map.of("year",year,"report",report,"classes",rows));
 }
 private void document(String key,String stock,String corp,String receipt,String period)throws Exception{
  byte[] compressed=financials.get("document.xml","crtfc_key="+key+"&rcept_no="+receipt,18000000);
  var extracted=OfficialDocumentExcerpt.extract(compressed,receipt,corp);
  save(stock,"DOCUMENT",receipt,"READY",Map.of("receipt",receipt,"sourceUrl","https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,
   "documentHash",PublicInformationService.hash(Base64.getEncoder().encodeToString(compressed)),"extractorVersion",OfficialDocumentExcerpt.VERSION,
   "reportName",extracted.reportName(),"period",period,"sections",extracted.sections(),
   "scope","공식 공시의 제한된 본문 발췌 · 단위/열/기간은 원문 확인 · 전체 의미/감사/표 수치 검증 완료 아님"));
 }
 private void save(String stock,String kind,String identity,String state,Object payload)throws Exception{
  String json=mapper.writeValueAsString(payload);jdbc.update("INSERT INTO company_public_source(stock_code,source_kind,source_identity,fetched_at,state,payload,content_hash) VALUES (?,?,?,?,?,?::jsonb,?)",
   stock,kind,identity,clock.instant().atOffset(ZoneOffset.UTC),state,json,PublicInformationService.hash(json));
 }
 public Source source(String stock,String kind,String identity){return jdbc.query("SELECT * FROM company_public_source WHERE stock_code=? AND source_kind=? AND source_identity=? ORDER BY id DESC LIMIT 1",
  (rs,n)->new Source(rs.getLong("id"),rs.getString("source_kind"),rs.getString("source_identity"),rs.getObject("fetched_at",OffsetDateTime.class).toInstant(),rs.getString("state"),read(rs.getString("payload")),rs.getString("content_hash")),stock,kind,identity).stream().findFirst().orElse(null);}
 public Source latest(String stock,String kind){return jdbc.query("SELECT * FROM company_public_source WHERE stock_code=? AND source_kind=? ORDER BY id DESC LIMIT 1",
  (rs,n)->new Source(rs.getLong("id"),kind,rs.getString("source_identity"),rs.getObject("fetched_at",OffsetDateTime.class).toInstant(),rs.getString("state"),read(rs.getString("payload")),rs.getString("content_hash")),stock,kind).stream().findFirst().orElse(null);}
 private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException("기업 자료 읽기 실패");}}
 public String issue(){return issue;}
 public static String receipt(String url){return URI.create(url).getQuery().replace("rcpNo=","");}
 private static String clean(String value){return PublicInformationService.plain(value,20000000).strip();}
 private static BigDecimal number(String text){String x=text.replace(",","").strip();return x.matches("[0-9]{1,20}")?new BigDecimal(x):null;}
 private static String publicUrl(String text){try{if(text.isBlank())return "";if(!text.contains("://"))text="https://"+text;URI uri=URI.create(text);
  if(!uri.getScheme().equals("https")||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1||!uri.getHost().matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")||uri.getHost().endsWith(".local")||text.length()>1024)return "";return uri.toString();}catch(Exception e){return "";}}
 public record Source(long id,String kind,String identity,Instant fetchedAt,String state,JsonNode payload,String contentHash){}
 public record ShareClass(String type,BigDecimal issued,BigDecimal treasury,BigDecimal circulating,boolean arithmeticVerified,String asOfDate,String sourceUrl){}
}
