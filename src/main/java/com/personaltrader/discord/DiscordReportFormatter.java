package com.personaltrader.discord;

import com.personaltrader.analysis.*;
import com.personaltrader.reports.DailyReport;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Whitelisted market/rule summaries only: no account fields or complete private reports. */
@Component
public class DiscordReportFormatter {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final Map<String,String> ROLES=Map.ofEntries(
            Map.entry("Universe Bot","종목선정 봇"),Map.entry("Market Bot","시장 봇"),Map.entry("Stock Bot","종목분석 봇"),
            Map.entry("News & Event Bot","공시·뉴스 봇"),Map.entry("Risk Bot","위험관리 봇"),Map.entry("Investor Playbook","투자원칙 봇"),
            Map.entry("사업·경쟁력 봇","사업·경쟁력 봇"),Map.entry("재무 검토 봇","재무 검토 봇"),Map.entry("가치평가 봇","가치평가 봇"),
            Map.entry("자본배분 봇","자본배분 봇"),Map.entry("보유 논거 봇","보유 논거 봇"));

    public Packet connection(Instant now){return new Packet("투자비서",
            "**Discord 투자비서 연결 완료**\n"+time(now)+"\n\n"
            +"관심 기업의 핵심 변화·차트 근거·기사/공시 출처를 간결하게 보고합니다.\n"
            +"**평일 08:30 장전 보고 · 주말/휴장일 20:30 다음 장 준비 요약**. 앱이 켜져 있어야 합니다. 보고마다 공식 거래 달력의 확인 상태를 표시합니다.\n"
            +"실제 RSS 제목·공시 피드와 차트 규칙을 보고합니다. 기사 본문·AI 해석은 미연결이며 점수는 상승 확률이 아닙니다.\n"
            +"장전 지수는 기록의 기준일·시각을 표시합니다. 이 보고 모듈은 주문을 실행하지 않습니다.");}

    public List<Packet> ideas(com.personaltrader.research.IdeaBoardService.Entry entry,Instant now,String title,String session){
        String heading="**"+clean(title,70)+"**\n"+time(now)+"\n";
        if(entry==null)return List.of(new Packet("투자비서",heading+"\n⏳ **후보 분석 중**\n최신 기업 자료를 기다리고 있어요. 준비된 후보가 없어 매수 추천을 보내지 않습니다.\n\n"+sessionLine(session)));
        boolean stale=entry.generatedAt().isAfter(now)||Duration.between(entry.generatedAt(),now).toMinutes()>=20;
        var signals=entry.report().signals();long ready=signals.stream().filter(x->x.paperEligible()).count();
        String summary=heading+"\n**한눈에 보기**\n• 관찰 "+signals.size()+"개 · 연구 통과 "+ready+"개\n• "+"AI는 연구 도구 · 자동매매 OFF · 거래 결정은 사용자"+"\n• "+sessionLine(session)+"\n"+(stale?"⚠️ 이전 연구입니다. 현재 후보로 보기 전 재확인이 필요해요.\n":"");
        List<String> blocks=new ArrayList<>();blocks.add(summary);
        int rank=0;
        for(var signal:signals.stream().limit(3).toList()){
            String state=signal.paperEligible()?"🟢 수치 선별 통과":signal.state().equals("DOWNSIDE_RISK")?"🔴 위험 재확인":"🟡 추가 검토";
            StringBuilder card=new StringBuilder("**"+(++rank)+". "+clean(signal.name(),30)+"** · "+state+"\n");
            card.append("**이유**  ").append(clean(signal.reasons().isEmpty()?"추세 확인 대기":signal.reasons().getFirst(),90)).append("\n")
                .append("**수치**  5일 ").append(percent(signal.change5())).append(" · 시장 대비 ").append(signal.relativeStrength()==null?"미확인":number(signal.relativeStrength())+"%p").append(" · 연구 ").append(signal.score()).append("점\n");
            String wait=signal.risks().isEmpty()?"사업 전망·자료 기준일 재확인":signal.risks().getFirst();
            if(!signal.paperEligible()&&signal.explanation()!=null)wait=String.join(" / ",signal.explanation().entryChecks().stream().filter(c->!c.pass()).limit(3).map(c->c.label()).toList());
            card.append("**주의 / 대기**  ").append(clean(wait,100)).append("\n");
            if(!signal.evidence().isEmpty()){var e=signal.evidence().getFirst();card.append(sourceLink(e.source()+" · "+e.title(),e.url())).append("\n");}
            blocks.add(card.toString());
        }
        if(entry.report().riskSignals()!=null&&!entry.report().riskSignals().isEmpty())blocks.add("🔴 **위험 제목 발견**  "+clean(String.join(" · ",entry.report().riskSignals().stream().map(x->x.name()).toList()),100));
        blocks.add("연구 기준 "+time(entry.generatedAt())+" · 차트 "+(signals.isEmpty()?"미확인":signals.getFirst().basisDate())+"\n점수는 상승 확률이 아니며 뉴스는 제목 관찰입니다. 실제 AI 토론·수치·출처는 웹 투자 대화방에서 직접 요청해 확인하세요.");
        return pack("투자비서",blocks);
    }

    public List<Packet> theses(com.personaltrader.research.CompanyThesisService.Entry entry,Instant now,String title){
        if(entry==null)return List.of();List<String> blocks=new ArrayList<>();
        boolean stale=entry.generatedAt().isAfter(now)||Duration.between(entry.generatedAt(),now).toMinutes()>=20;
        blocks.add("**📚 "+clean(title,60)+"**\n"+time(entry.generatedAt())+" · "+(stale?"이전 검토 기록":"기업 검토 기록")+"\n사업·재무 점검 요약입니다. 실제 매수·매도 판단과는 별도예요.");
        for(var c:entry.report().companies().stream().filter(com.personaltrader.research.CompanyThesisService.Company::held).limit(5).toList()){
            StringBuilder card=new StringBuilder("**").append(clean(c.name(),30)).append(" · 보유 기업**\n");
            card.append("**사업**  ").append(clean(c.business().facts(),95)).append("\n")
                .append("**검토 결론**  ").append(clean(c.conclusion(),110)).append("\n")
                .append("**반대 근거**  ").append(clean(c.business().counterEvidence(),100)).append("\n");
            var pending=c.checks().stream().filter(x->!x.state().equals("PASS")).limit(3).map(x->x.label()).toList();
            card.append("**미확인 / 미달**  ").append(pending.isEmpty()?"표시된 수치 기준 통과 · 수익 보장 아님":String.join(" / ",pending)).append("\n");
            if(c.valuation().scenarios().isEmpty())card.append("**가치 계산**  공식 입력 확인 대기\n");
            else card.append("**가치 계산**  가정별 참고값 산출 · 목표주가 아님, 웹 상세 확인\n");
            if(c.document()!=null&&c.document().state().equals("READY"))card.append(sourceLink("DART 공식 원문",c.document().payload().path("sourceUrl").asText())).append("\n");
            blocks.add(card.toString());
        }
        blocks.add("검토 #"+entry.id()+" · 전체 수치·모형 가정·공식 출처: 웹 ‘기업 검토서’\n잠재 강점과 계산 가정은 검증된 투자 성과가 아닙니다.");
        return pack("기업 검토 비서",blocks);
    }

    private static String newsLabel(String state){return switch(state){
        case "TITLE_CATALYST"->"촉매 가능성 제목 · 본문 미검증";case "RISK_TITLE"->"위험 제목";case "CORRECTION"->"정정/철회";
        case "FEED_UNAVAILABLE"->"핵심 피드 확인 필요";case "NO_LINKED_NEWS"->"기업 연결 뉴스 없음";default->"호재 제목 미확인";};}
    /** Uses the saved report snapshot; never substitutes live figures into an older report. */
    public List<Packet> account(DailyReport report){
        if(report.insights()==null)return List.of();var a=report.insights();List<String> blocks=new ArrayList<>();
        blocks.add("**💰 "+report.reportDate()+" "+clean(report.title(),40)+" · 계좌 요약**\n"+time(report.generatedAt()));
        blocks.add("**총자산**  "+money(a.assets())+"\n**봇 매도 완료 손익**  "+signedMoney(a.realizedNetPnl())+"\n**봇 보유 평가손익**  "+signedMoney(a.unrealizedNetPnl())+" · 비용 추정\n잔고 기준 "+time(a.balanceAt())+(a.balanceFresh()?"":" · 이전 기록"));
        if(!a.periods().isEmpty()){var v=a.periods().getFirst();blocks.add("**최근 1주 성과**\n• 실제 집계 "+time(v.from())+" ~ "+time(v.through())+"\n• 자산 변화 "+signedMoney(v.assetChange())+" · 등록 순입금 "+signedMoney(v.netFlow())+"\n• 입출금 조정 손익 "+signedMoney(v.profit())+"\n• 수익률 "+(v.percent()==null?"⏳ 입출금 검토 / 기록 대기":percent(v.percent())+" · 입출금이 있으면 근사"));}
        long confirmed=a.costs().stream().filter(c->c.confirmedCommission()!=null).count();
        var actionable=a.alerts().stream().filter(x->!x.startsWith("모의 정책:")).toList();
        String warnings=actionable.isEmpty()?"별도 관찰 경고 없음 · 안전 보장 아님":String.join("\n",actionable.stream().limit(3).map(x->"• "+clean(x,120)).toList());
        blocks.add("**보고 당시 확인할 사항**\n"+warnings+"\n정산 비용 확인 "+confirmed+"/"+a.costs().size()+"건 · 최근 최대100건");
        if(report.disclosureImpacts()!=null)for(var d:report.disclosureImpacts().entries().stream().limit(1).toList())blocks.add("**관련 공시**  "+clean(d.name()+" · "+d.title(),80)+"\n"+sourceLink("DART 원문 확인",d.url()));
        blocks.add("계좌 자산 변화와 봇 거래 손익은 범위가 달라요. 공시 표는 제한 추출입니다. 전체: 웹 ‘성과 분석’");
        return pack("투자비서",blocks);
    }
    private static String money(java.math.BigDecimal value){return value==null?"미확인":String.format(Locale.KOREA,"%,.0f원",value);}
    private static String signedMoney(java.math.BigDecimal value){return value==null?"미확인":(value.signum()>0?"+":"")+money(value);}
    private static String number(java.math.BigDecimal value){return value==null?"미확인":value.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static String percent(java.math.BigDecimal value){return value==null?"미확인":(value.signum()>0?"+":"")+number(value)+"%";}
    private static String sessionLine(String session){return "장 / 실행 상태  "+clean(session,190).replace("KRX 거래일 확인","오늘 거래일 확인됨").replace("당일 신선한 시세/계좌 조건으로 주문 판단","최신 시세·계좌 검사 후 주문");}
    private static String sourceLink(String title,String url){
        try{var u=java.net.URI.create(url);if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||!Set.of("www.hankyung.com","dart.fss.or.kr").contains(u.getHost())||url.length()>420||url.matches(".*[\\s<>\\(\\)].*"))return "원문 링크 확인 필요";
            return "["+clean(title,55).replace("[","(").replace("]",")")+"](<"+u.toASCIIString()+">)";
        }catch(Exception ignored){return "원문 링크 확인 필요";}
    }
    /** Keep each company paragraph together; no line-based splits through a card/link. */
    private static List<Packet> pack(String author,List<String> blocks){
        List<Packet> result=new ArrayList<>();StringBuilder part=new StringBuilder();
        for(String block:blocks){String bounded=clean(block,1700);if(part.length()+bounded.length()+2>1800&&!part.isEmpty()){result.add(new Packet(author,part.toString().strip()));part.setLength(0);}if(!part.isEmpty())part.append("\n\n");part.append(bounded);}
        if(!part.isEmpty())result.add(new Packet(author,part.toString().strip()));
        if(result.size()>1){for(int i=0;i<result.size();i++){var p=result.get(i);result.set(i,new Packet(author,"**보고 "+(i+1)+"/"+result.size()+"**\n\n"+p.content()));}}
        return List.copyOf(result);
    }

    public List<Packet> daily(DailyReport report,Instant now){
        List<Packet> result=new ArrayList<>();
        String title="**"+report.reportDate()+" "+clean(report.title(),60)+"**\n"
            +"예약: "+time(report.scheduledAt())+"\n작성: "+time(report.generatedAt())+"\n"
            +(report.timing().equals("ON_TIME")?"예약 시각 부근 작성":"지연 작성 · 예약 당시 상태를 복원한 자료가 아닙니다.")
            +"\nDART·경제뉴스 범위는 아래 당시 봇 기록을 따릅니다. 제목 관찰과 본문·재무 검토를 구분합니다.\n"
            +"거래일 상태: "+clean(report.marketPhase(),160)+". 관찰 목록은 매수 권유나 검증된 상승 확률이 아닙니다.";
        result.add(new Packet("보고 비서",title));
        result.addAll(meeting(report.meeting(),now));
        return List.copyOf(result);
    }

    public List<Packet> meeting(BotMeetingRepository.Entry entry,Instant now){
        if(entry==null)return List.of(new Packet("보고 비서","**⏳ 봇 분석 준비 중**\n저장된 판단이 아직 없어요. 실제 입력 수집 후 보고합니다."));
        var report=entry.report();List<String> blocks=new ArrayList<>();
        boolean stale=entry.occurredAt().isAfter(now)||Duration.between(entry.occurredAt(),now).getSeconds()>120;
        blocks.add("**🤖 봇 판단 요약**\n"+time(entry.occurredAt())+(stale?" · 이전 기록":"")+"\n\n**결론**  "+clean(report.summary(),180)+"\n모의 자동매매 "+(report.automationEnabled()?"ON":"OFF")+" · 신선 시세 "+report.freshQuoteCount()+"/"+report.observedCount()+"개");
        StringBuilder roles=new StringBuilder("**역할별 핵심 판단**\n");
        for(var speaker:report.speakers()){String author=ROLES.get(speaker.name());if(author!=null)roles.append("• **").append(author).append("**  ").append(clean(speaker.summary(),85)).append('\n');}blocks.add(roles.toString());
        StringBuilder indices=new StringBuilder("**시장 지수**\n");
        if(report.marketIndices()==null||report.marketIndices().isEmpty())indices.append("지수 확인 대기");
        else for(var index:report.marketIndices()){var q=index.quote();indices.append("• ").append(index.name()).append("  ");if(q==null)indices.append("미확인");else indices.append(number(q.value())).append(" · ").append(time(q.basisAt())).append(q.fresh(now)?"":" · 이전 값");indices.append('\n');}blocks.add(indices.toString());
        if(report.stocks()!=null&&!report.stocks().isEmpty()){StringBuilder stocks=new StringBuilder("**관찰 기업 · 최대3개**\n");for(var stock:report.stocks().stream().limit(3).toList())stocks.append("• **").append(clean(stock.name(),30)).append("**  ").append(clean(stock.reason(),90)).append(" · 일봉 ").append(stock.dailyBasisDate()).append('\n');blocks.add(stocks.toString());}
        blocks.add("관찰 후보는 매수 확정이 아니에요. 역할별 상세 근거·가격 시각: 웹 ‘봇 회의록’");return pack("보고 비서",blocks);
    }

    /** Ignore heartbeat timestamps and changing prices; notify only meaningful rule/role changes. */
    public String fingerprint(BotMeetingRepository.Entry entry){
        if(entry==null)return null;
        StringBuilder value=new StringBuilder(entry.report().phase());
        for(var speaker:entry.report().speakers())if(ROLES.containsKey(speaker.name()))
            value.append('|').append(speaker.name()).append(':').append(speaker.state()).append(':').append(speaker.summary());
        for(var stock:entry.report().stocks())value.append('|').append(stock.code()).append(':').append(stock.marketVote())
            .append(':').append(stock.stockVote()).append(':').append(stock.riskVote()).append(':').append(stock.newsVote());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ignored){throw new IllegalStateException("봇 상태 비교 실패");}
    }
    static String time(Instant value){return value==null?"미확인":DateTimeFormatter.ofPattern("MM-dd HH:mm:ss 'KST'").format(value.atZone(SEOUL));}
    static String clean(String value,int limit){
        if(value==null)return "미확인";
        String text=value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").replace('@','＠');
        if(text.length()<=limit)return text;
        int end=limit-1; if(Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end)+"…";
    }
    public record Packet(String author,String content){
        public Packet{author=clean(author,80);content=clean(content,1900);}
    }
}
