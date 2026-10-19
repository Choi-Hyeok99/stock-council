package com.personaltrader.research;

import com.fasterxml.jackson.databind.*;
import com.personaltrader.broker.ExecutionBrokerClient;
import com.personaltrader.broker.ExecutionBrokerClient.Environment;
import com.personaltrader.marketdata.TimedMarketQuote;
import com.personaltrader.orders.PositionQuoteCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.util.*;

/** Account-scoped, immutable research. This service has no order submission method. */
@Service
public class CompanyThesisService {
    @org.springframework.beans.factory.annotation.Autowired private ResearchWorkspace workspace;

 public static final String VERSION="COMPANY_VALUE_RESEARCH_V3";
 public static final String POLICY="VALUE_SHADOW_SCREEN_V1";
 private final DartFinancialService financials;private final CompanySourceService sources;private final EquityScenarioModel model;
 private final PublicInformationService information;private final PositionQuoteCollector quotes;private final ExecutionBrokerClient broker;
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Clock clock;private final Environment environment;
 private volatile Report liveReport;private final Map<String,JsonNode> seeds=new HashMap<>();private volatile Instant checkedAt;private volatile String issue;
 public CompanyThesisService(DartFinancialService financials,CompanySourceService sources,EquityScenarioModel model,PublicInformationService information,
  PositionQuoteCollector quotes,ExecutionBrokerClient broker,JdbcTemplate jdbc,ObjectMapper mapper,Clock clock,@Value("${execution.environment:PAPER}") String environment){
  this.financials=financials;this.sources=sources;this.model=model;this.information=information;this.quotes=quotes;this.broker=broker;this.jdbc=jdbc;this.mapper=mapper;this.clock=clock;this.environment=Environment.valueOf(environment);
  try(var in=getClass().getResourceAsStream("/research/company-review-seeds.json")){for(var c:mapper.readTree(in).path("companies"))seeds.put(c.path("code").asText(),c);}catch(Exception e){throw new IllegalStateException("기업 검토 기준 파일 확인 필요");}
 }
 String scope(){return workspace.id();}
 @Scheduled(fixedDelay=60000,initialDelay=23000)
 public synchronized void review(){
  try{
   var f=financials.view();Instant now=clock.instant();if(f.report()==null){issue="공식 재무자료 대기";return;}
   List<Company> companies=new ArrayList<>();for(var c:f.report().companies())companies.add(company(c,now,f.current()&&f.report().keyConfigured()&&f.issue()==null));
   List<String> rules=List.of("공식 사업 원문과 연결된 사업 사실, 연결 지배주주 이익·현금 검토, 주식 수와 가격 근거를 순서대로 확인한다.",
    "관찰 비교 통과: 일치하는 사업보고서 + 최근 3개 연속 연간 지배주주 흑자/영업현금 양수 + 보수 시나리오 대비 25% 안전마진 + 당일 검증 시세 + 최근 수집된 핵심 피드 및 위험 제목 없음. 금융업은 자본 적정성 추가 확인까지 대기.",
    "이는 자동 수치 선별 규칙이다. 경쟁력·경영진·정상화 이익·감사·차입 만기·공시 이후 주식 변경과 뉴스 본문 영향은 별도 검토가 필요하며 매수 허가로 취급하지 않는다.",
    "AI는 기업 연구만 수행한다. 자동 매수·매도·익절·취소는 없고 직접 모의 매도는 별도 사용자 확인 화면에서 요청한다. 시장 하락·20일선 하회는 단독 반대 이유가 아니며 사업·재무와 가치 훼손 여부를 따로 확인한다.",
    "이익·현금 악화·사업/공시 위험은 투자 가설 재검토 사유. 공식 수치와 가정의 기간·단위·출처를 표시하고 최종 투자 결정은 사용자에게 맡긴다.",
    "전략 비교는 같은 실제 시세에서 두 선별 규칙을 기록한다. 거래 체결·포트폴리오 수익과 구분하고 시작 후 미래 데이터만 측정한다.");
   var report=new Report(VERSION,POLICY,now,f.id(),BerkshireMandate.entriesPaused(environment),companies,rules,
    "공시 사실 / 검토 가설 / 모형 가정 / 실시간 시세 / 투자 결정을 구분 · 공개 공식 자료와 허용된 RSS만 사용");
   String fingerprint=fingerprint(companies);Entry old=latest();
   if(old==null||!fingerprint.equals(old.fingerprint())||Duration.between(old.generatedAt(),now).toHours()>=6){
    jdbc.update("INSERT INTO company_thesis_snapshot(scope_id,generated_at,version,fingerprint,report) VALUES (?,?,?,?,?::jsonb)",scope(),utc(now),VERSION,fingerprint,mapper.writeValueAsString(report));
   }
   liveReport=report;checkedAt=now;issue=null;
  }catch(Exception e){issue="기업 검토서 정리 실패 · 이전 원본과 시각 확인 필요";}
 }
 private Company company(DartFinancialService.Company c,Instant now,boolean collectorCurrent){
  var annual=c.annuals().stream().filter(p->p.state().equals("READY")).max(Comparator.comparingInt(DartFinancialService.Period::year)).orElse(null);
  String receipt=annual==null?null:CompanySourceService.receipt(annual.sourceUrl());var doc=receipt==null?null:sources.source(c.code(),"DOCUMENT",receipt);
  var current=c.latestInterim()==null?annual:c.latestInterim();var shares=current==null?null:sources.source(c.code(),"SHARES",current.year()+"/"+current.report());var profile=sources.latest(c.code(),"PROFILE");
  JsonNode seed=seeds.get(c.code());boolean seeded=doc!=null&&"READY".equals(doc.state())&&seed!=null&&seed.path("receipt").asText().equals(receipt);
  String businessExcerpt="";
  if(doc!=null&&"READY".equals(doc.state())&&doc.payload().path("extractorVersion").asInt()>=OfficialDocumentExcerpt.VERSION)
   for(var section:doc.payload().path("sections"))if("BUSINESS".equals(section.path("category").asText())){businessExcerpt=section.path("text").asText();break;}
  boolean linked=seeded||!businessExcerpt.isBlank();
  var valuation=model.evaluate(c,shares);var price=quotes.freshQuote(c.code());
  BigDecimal margin=margin(valuation,price==null?null:price.price());
  List<DartFinancialService.Period> recent=c.annuals().stream().filter(p->p.state().equals("READY")).sorted(Comparator.comparingInt(DartFinancialService.Period::year)).toList();
  recent=recent.size()>=3?recent.subList(recent.size()-3,recent.size()):recent;
  List<Check> checks=new ArrayList<>();checks.add(check("BUSINESS","공식 사업 근거",linked?"PASS":"WAIT",linked?"기업코드·접수번호가 일치하는 공식 사업 설명 연결 · 경쟁력 검증 완료는 아님":"현재 원문과 대응하는 사업 본문 수집 필요"));
  boolean continuous=recent.size()==3;for(int i=1;i<recent.size();i++)continuous&=recent.get(i).year()==recent.get(i-1).year()+1;
  boolean profitable=continuous&&recent.stream().allMatch(p->positive(p,"OWNER_PROFIT"));
  boolean cash=continuous&&recent.stream().allMatch(p->positive(p,"OPERATING_CASH_FLOW"));
  checks.add(check("PROFIT","최근 3개 연속 연간 지배주주 이익",profitable?"PASS":recent.size()<3?"WAIT":"FAIL",numbers(recent,"OWNER_PROFIT")));
  checks.add(check("CASH","최근 3개 연속 연간 영업현금",c.financialSectorReview()?"WAIT":cash?"PASS":recent.size()<3?"WAIT":"FAIL",c.financialSectorReview()?"금융업 자본·유동성 적정성 미검토, 제조업 CF 기준 적용 보류":numbers(recent,"OPERATING_CASH_FLOW")));
  checks.add(check("VALUE","주당 시나리오 입력",valuation.state().equals("SCENARIOS")?"PASS":"WAIT",valuation.shareNote()+" · "+String.join(" / ",valuation.missing())));
  checks.add(check("MARGIN","보수 시나리오 대비 안전마진 ≥25%",margin==null?"WAIT":margin.compareTo(new BigDecimal("25"))>=0?"PASS":"FAIL",margin==null?"당일 검증 시세 또는 주당 시나리오 미확인":"1 − 당시 가격/보수 시나리오 = "+margin.setScale(2,RoundingMode.HALF_UP)+"% · 상승률 아님"));
  var evidence=information.evidence(c.code()).stream().filter(e->!e.discoveredAt().isAfter(now)&&(e.publishedAt()==null||!e.publishedAt().isAfter(now))).toList();
  boolean risk=evidence.stream().anyMatch(e->InvestmentSignalEngine.classify(e).classification().equals("RISK_TITLE")||e.title().contains("정정")||e.title().contains("철회"));
  checks.add(check("NEWS","연결 위험 제목·수집 상태",!information.view().collectionHealthy()?"WAIT":risk?"FAIL":"PASS",risk?"위험/정정 제목 발견, 본문 확인 필요":"연결 제목 "+evidence.size()+"건 · 위험 제목 미발견이 실제 위험 없음의 증거는 아님"));
  boolean dates=collectorCurrent&&sources.issue()==null&&annual!=null&&annual.year()==now.atZone(TimedMarketQuote.SEOUL).getYear()-1&&current!=null&&shares!=null&&doc!=null
   &&freshSource(shares,now,Duration.ofHours(26))&&freshSource(doc,now,Duration.ofDays(8));
  checks.add(check("FRESH","현재 입력 확인",dates&&price!=null?"PASS":"WAIT","사업보고서·주식 수 원본 TTL 및 최신 사업연도 / 재무 수집기 / 120초 당일 시세 확인"));
  String action=checks.stream().allMatch(x->x.state().equals("PASS"))?"OBSERVE_PASS":"WAIT";
  List<String> missing=new ArrayList<>(seed==null?(linked?List.of("사업 본문 연결 · 고객 유지율·경쟁력은 별도 검토 필요"):List.of("사업 구조·고객·경쟁력에 대한 공식 원문 검토 대기")):readList(seed.path("questions")));
  missing.addAll(List.of("가격결정력·고객 유지율·정상화 이익·차입 만기·감사의견·자본배분은 아직 확정하지 않음","뉴스는 연결 제목 관찰이며 호재 실적 영향이나 상승 확률 미확정","공시 이후 증자·소각·분할 등 주식 변경을 완전 검증하지 못함"));
  List<String> invalidation=List.of("지배주주 이익이 연속 2개 연간 적자면 기존 이익 지속 가설 재검토","비금융 기업 영업현금이 연속 2개 연간 음수면 현금전환 가설 재검토","핵심 계약 철회·허가/임상 실패·감사 문제·희석을 실제 원문으로 확인하면 사업/가격 가설 재검토","기업 연구상의 재판단 조건이며 매매 지시가 아님");
  boolean losses=lastTwoNonPositive(recent,"OWNER_PROFIT"),cashLoss=!c.financialSectorReview()&&lastTwoNonPositive(recent,"OPERATING_CASH_FLOW");
  String thesisState=losses||cashLoss||risk?"REVIEW_REQUIRED":"EVIDENCE_REVIEW";
  var facts=seeded?new Business(seed.path("business").asText(),seed.path("potentialStrength").asText(),seed.path("counterEvidence").asText(),"2026-10-07 원문 사업의 개요 확인 · 잠재 강점/반대 사유는 분석 가설",seed.path("irUrl").asText()):linked?
   new Business(businessExcerpt.substring(0,Math.min(600,businessExcerpt.length())),"공식 사업 설명에서 경쟁력 가설을 검토 · 경쟁우위 확정 아님","사업 설명은 회사 공시이며 독립적 사실 검증·사업 지속성 보증 아님","공식 사업보고서 본문 일부 발췌",annual.sourceUrl()):
   new Business("현재 사업보고서에 연결된 요약 확인 대기","지속적 경쟁력 미확정","수치만으로 사업 우위·저평가를 확정하지 않음","공식 원문 연결 범위 확인 필요",seed==null?"":seed.path("irUrl").asText());
  return new Company(c.code(),c.name(),c.held(),thesisState,action,linked,facts,profile,doc,shares,c.annualCollected(),c.financialSectorReview(),valuation,price,margin,checks,missing,invalidation,evidence,
   losses||cashLoss?"실제 연간 수치에서 연속 손실/현금 악화 조건 발견 · 원인과 일회성 여부 검토":risk?"위험 제목 원문 재검토 필요":"수치 관찰 중 · 기업 논거 확정 아님");
 }
 private static Check check(String key,String label,String state,String detail){return new Check(key,label,state,detail);}
 private static boolean positive(DartFinancialService.Period p,String key){var m=EquityScenarioModel.metric(p,key);return m!=null&&m.value().signum()>0;}
 private static String numbers(List<DartFinancialService.Period> periods,String key){return String.join(" / ",periods.stream().map(p->{var m=EquityScenarioModel.metric(p,key);return p.year()+": "+(m==null?"미확인":m.value().toPlainString()+"원");}).toList());}
 private static boolean lastTwoNonPositive(List<DartFinancialService.Period> list,String key){return list.size()>=2&&list.subList(list.size()-2,list.size()).stream().allMatch(p->{var m=EquityScenarioModel.metric(p,key);return m!=null&&m.value().signum()<0;});}
 private static boolean freshSource(CompanySourceService.Source s,Instant now,Duration ttl){return s.state().equals("READY")&&!s.fetchedAt().isAfter(now)&&Duration.between(s.fetchedAt(),now).compareTo(ttl)<=0;}
 public static BigDecimal margin(EquityScenarioModel.Result v,BigDecimal price){if(price==null||v.scenarios().isEmpty())return null;var value=v.scenarios().getFirst().perCommonShare();return value==null||value.signum()<=0?null:BigDecimal.ONE.subtract(price.divide(value,MathContext.DECIMAL128)).multiply(new BigDecimal("100"));}
 private static List<String> readList(JsonNode n){List<String> result=new ArrayList<>();n.forEach(x->result.add(x.asText()));return result;}
 private String fingerprint(List<Company> companies)throws Exception{
  List<Object> semantic=new ArrayList<>();for(var c:companies){semantic.add(Arrays.asList(c.code(),c.state(),c.shadowAction(),c.business(),c.valuation(),c.profile()==null?null:c.profile().contentHash(),c.document()==null?null:c.document().contentHash(),c.shares()==null?null:c.shares().contentHash(),c.valuation().inputs(),c.checks().stream().map(x->x.key()+":"+x.state()).toList(),c.evidence().stream().map(PublicInformationService.Evidence::id).sorted().toList()));}
  return PublicInformationService.hash(mapper.writeValueAsString(Map.of("version",VERSION,"policy",POLICY,"companies",semantic)));
 }
 public Company currentCompany(String code){Report r=liveReport;return r==null||checkedAt==null||Duration.between(checkedAt,clock.instant()).toSeconds()>150?null:r.companies().stream().filter(c->c.code().equals(code)).findFirst().orElse(null);}
 public boolean paperEnvironment(){return environment==Environment.PAPER;}
 public Entry latest(){return jdbc.query("SELECT * FROM company_thesis_snapshot WHERE scope_id IS NOT DISTINCT FROM ? ORDER BY id DESC LIMIT 1",(rs,n)->read(rs.getLong("id"),rs.getObject("generated_at",OffsetDateTime.class).toInstant(),rs.getString("fingerprint"),rs.getString("report")),scope()).stream().findFirst().orElse(null);}
 public Optional<Entry> find(long id){return jdbc.query("SELECT * FROM company_thesis_snapshot WHERE id=? AND scope_id IS NOT DISTINCT FROM ?",(rs,n)->read(rs.getLong("id"),rs.getObject("generated_at",OffsetDateTime.class).toInstant(),rs.getString("fingerprint"),rs.getString("report")),id,scope()).stream().findFirst();}
 private Entry read(long id,Instant at,String hash,String json){try{return new Entry(id,at,hash,mapper.readValue(json,Report.class));}catch(Exception e){throw new IllegalStateException("검토서 원본 형식 확인 필요");}}
 public View view(){Entry e=latest();Map<String,TimedMarketQuote> latestQuotes=new LinkedHashMap<>();if(e!=null)for(var c:e.report().companies()){var q=quotes.latestQuote(c.code());if(q!=null)latestQuotes.put(c.code(),q);}
  return new View(e,checkedAt,checkedAt!=null&&!checkedAt.isAfter(clock.instant())&&Duration.between(checkedAt,clock.instant()).toSeconds()<150&&issue==null,issue!=null?issue:sources.issue(),latestQuotes);}
 private static OffsetDateTime utc(Instant at){return at.atOffset(ZoneOffset.UTC);}
 public record Check(String key,String label,String state,String detail){}
 public record Business(String facts,String potentialStrength,String counterEvidence,String reviewBasis,String irUrl){}
 public record Company(String code,String name,boolean held,String state,String shadowAction,boolean businessLinked,Business business,CompanySourceService.Source profile,CompanySourceService.Source document,
  CompanySourceService.Source shares,int annualCount,boolean financialSectorReview,EquityScenarioModel.Result valuation,TimedMarketQuote priceAtReview,BigDecimal marginAtReview,List<Check> checks,List<String> missing,List<String> invalidation,List<PublicInformationService.Evidence> evidence,String conclusion){}
 public record Report(String version,String policyVersion,Instant generatedAt,Long financialSnapshotId,boolean entriesPaused,List<Company> companies,List<String> rules,String scope){}
 public record Entry(long id,Instant generatedAt,String fingerprint,Report report){}
 public record View(Entry entry,Instant checkedAt,boolean current,String issue,Map<String,TimedMarketQuote> latestQuotes){}
}
