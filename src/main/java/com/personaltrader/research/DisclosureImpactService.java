package com.personaltrader.research;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.w3c.dom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.math.BigDecimal;

/** Bounded official document extraction. Filings/news never instruct the system to act. */
@Service
public class DisclosureImpactService {
 private final DartFinancialService financials;private final PublicInformationService information;
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;
 private volatile String issue;private Instant retry=Instant.EPOCH;private int rotation;
 public DisclosureImpactService(DartFinancialService financials,PublicInformationService information,JdbcTemplate jdbc,ObjectMapper mapper,Clock clock){this.financials=financials;this.information=information;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;}
 @Scheduled(fixedDelay=120000,initialDelay=70000)
 public synchronized void collect(){
  String key=DartFinancialService.key();if(key==null){issue="OpenDART 키 확인 필요";return;}if(clock.instant().isBefore(retry))return;
  try{
   var report=financials.view().report();if(report==null)return;
   List<Target> targets=new ArrayList<>();
   for(var c:report.companies())for(var e:information.evidence(c.code()))if(e.kind().equals("DART_API")&&e.matchBasis().equals("OFFICIAL_ISSUER_CODE")&&e.title().matches(".*(공급계약|판매계약|유상증자|전환사채|신주인수권|잠정실적|영업실적|채무보증|채무인수).*")){
    var receipt=java.util.regex.Pattern.compile("(?:rcpNo|rcept_no)=([0-9]{14})(?:&|$)").matcher(e.url());if(receipt.find())targets.add(new Target(c,e,receipt.group(1)));
   }
   if(targets.isEmpty()){issue=null;return;}
   for(int i=0;i<targets.size();i++){
    Target t=targets.get(Math.floorMod(rotation++,targets.size()));var old=jdbc.query("SELECT fetched_at FROM disclosure_impact_evidence WHERE stock_code=? AND information_id=? ORDER BY id DESC LIMIT 1",(rs,n)->rs.getObject(1,OffsetDateTime.class).toInstant(),t.company().code(),t.evidence().id());
    if(!old.isEmpty()&&Duration.between(old.getFirst(),clock.instant()).compareTo(Duration.ofHours(12))<0)continue;
    var corp=jdbc.queryForList("SELECT corp_code FROM dart_corporation WHERE stock_code=?",String.class,t.company().code());if(corp.isEmpty())continue;
    byte[] zip=financials.get("document.xml","crtfc_key="+key+"&rcept_no="+t.receipt(),12000000);
    List<Field> fields=extract(zip,t.receipt(),corp.getFirst());List<String> analysis=new ArrayList<>();
    if(t.evidence().title().contains("정정"))analysis.add("정정 공시입니다. 이전 계약/발행 조건과 변경 항목을 원문에서 대조해야 합니다.");
    if(t.evidence().title().contains("계약"))analysis.add("계약 금액과 이행 기간을 확인합니다. 계약액을 당일 매출·이익 또는 즉시 현금 유입으로 환산하지 않습니다.");
    if(t.evidence().title().matches(".*(증자|사채|신주인수권).*"))analysis.add("조달은 현금 유입과 주식 희석/상환 부담을 함께 검토합니다. 전환 가능 주식수·현재 주식수·조건 확인 전 희석률 계산을 보류합니다.");
    for(var f:fields)if(f.label().contains("매출액대비")&&f.value().matches("[0-9,. ]+%"))analysis.add("공시가 제시한 최근 매출 대비 계약 비율: "+f.value()+". 이 비율은 계약의 규모이며 수익률/영업이익률이 아닙니다.");
    if(!t.company().annuals().isEmpty()){var annual=t.company().annuals().getLast();analysis.add("재무 비교의 기준: "+annual.label()+" / "+annual.fs()+" · 원본 조회 "+annual.fetchedAt()+". 공시 계약 기간과 연간 매출 기간이 다를 수 있습니다.");}
    t.company().insights().stream().filter(x->x.value()!=null).limit(3).forEach(x->analysis.add("최근 정기 재무 검토: "+x.label()+" "+x.value().toPlainString()+" · "+bounded(x.explanation(),220)));
    var entry=new Entry(t.company().code(),t.company().name(),t.evidence().title(),"https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+t.receipt(),clock.instant(),fields.isEmpty()?"FIELDS_NOT_FOUND":"LIMITED_TABLE_REVIEW",fields,List.copyOf(analysis),
     "공식 발행인·접수번호 검증 / 제한된 표 추출. 표 문구는 원문 값이며 통화·단위·기간·정정 및 수익성 해석은 사람 검토 필요. 주가 예측·매수 허가 아님.");
    String json=mapper.writeValueAsString(entry);jdbc.update("INSERT INTO disclosure_impact_evidence(stock_code,information_id,receipt,fetched_at,state,payload,content_hash) VALUES (?,?,?,?,?,?::jsonb,?)",entry.code(),t.evidence().id(),t.receipt(),utc(entry.at()),entry.state(),json,PublicInformationService.hash(json));issue=null;return; // One bounded document per cycle.
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){issue="주요 공시 원문 수집/형식 확인 실패 · 이전 근거 시각 확인 필요";retry=clock.instant().plus(Duration.ofMinutes(30));}
 }
 private List<Field> extract(byte[] bytes,String receipt,String corp)throws Exception{
  try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){int files=0,total=0;for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
   if(++files>12)throw new IOException();byte[] xml=zip.readNBytes(8000001);total+=xml.length;if(xml.length>8000000||total>24000000)throw new IOException();
   if(!entry.getName().equals(receipt+".xml"))continue;
   String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(xml)).toString();if(source.contains("<!DOCTYPE")||source.contains("<!ENTITY"))throw new IOException();
   var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
   var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));boolean issuer=false;var companies=doc.getElementsByTagName("COMPANY-NAME");for(int n=0;n<companies.getLength();n++)if(((Element)companies.item(n)).getAttribute("AREGCIK").equals(corp))issuer=true;if(!issuer)throw new IOException();
   List<Field> result=new ArrayList<>();var rows=doc.getElementsByTagName("TR");if(rows.getLength()>20000)throw new IOException();
   for(int n=0;n<rows.getLength()&&result.size()<12;n++){
    var cells=((Element)rows.item(n)).getElementsByTagName("TD");if(cells.getLength()<2||cells.getLength()>20)continue;
    for(int c=0;c<cells.getLength()-1&&result.size()<12;c++){
     String label=bounded(cells.item(c).getTextContent(),100).replaceAll("\\s+"," ");
     if(!label.matches(".*(계약금액|최근매출액|매출액대비|자기자본대비|발행금액|발행주식수|전환가액|납입일|계약기간|종료일|조달목적|채무보증금액).*"))continue;
     String value=bounded(cells.item(c+1).getTextContent(),260).replaceAll("\\s+"," ");
     if(!value.isBlank()&&!value.equals(label)){var f=new Field(label,value);if(!result.contains(f))result.add(f);}
    }
   }
   return List.copyOf(result);
  }}throw new IOException();
 }
 public View view(){
  var financial=financials.view().report();if(financial==null)return new View(clock.instant(),issue,List.of());Set<String> codes=new HashSet<>();financial.companies().forEach(c->codes.add(c.code()));
  var entries=jdbc.query("SELECT payload FROM (SELECT DISTINCT ON(stock_code,information_id) * FROM disclosure_impact_evidence ORDER BY stock_code,information_id,id DESC) p WHERE fetched_at>=? ORDER BY fetched_at DESC LIMIT 50",(rs,n)->{try{return mapper.readValue(rs.getString(1),Entry.class);}catch(Exception e){throw new IllegalStateException("공시 근거 변환 실패");}},utc(clock.instant().minus(Duration.ofDays(4))));
  return new View(clock.instant(),issue,entries.stream().filter(x->codes.contains(x.code())).limit(10).toList());
 }
 private static String bounded(String s,int max){String value=s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");return value.length()>max?value.substring(0,max)+"…":value;}
 private static OffsetDateTime utc(Instant t){return t.atOffset(ZoneOffset.UTC);}
 private record Target(DartFinancialService.Company company,PublicInformationService.Evidence evidence,String receipt){}
 public record Field(String label,String value){}
 public record Entry(String code,String name,String title,String url,Instant at,String state,List<Field> fields,List<String> analysis,String note){}
 public record View(Instant checkedAt,String issue,List<Entry> entries){}
}
