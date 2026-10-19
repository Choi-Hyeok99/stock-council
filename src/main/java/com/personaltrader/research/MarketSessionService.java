package com.personaltrader.research;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.Map;

/** Official monthly calendar and independently reviewed closures; timed quotes remain mandatory. */
@Component
public class MarketSessionService {
    private final Clock clock;
    private final TradingCalendarService calendar;
    private static final Map<LocalDate,String> CLOSED=Map.of(
        LocalDate.of(2026,10,5),"개천절 대체공휴일",LocalDate.of(2026,10,9),"한글날");
    @Autowired
    public MarketSessionService(Clock clock, TradingCalendarService calendar){this.clock=clock;this.calendar=calendar;}
    public MarketSessionService(Clock clock){this(clock,null);}
    public boolean knownClosed(){return view().closed();}
    public boolean newEntriesAllowed(){View view=view();return !view.closed() && (calendar==null || view.calendarVerified());}
    public String reportLabel(){
        View view=view();
        return view.label()+(view.calendarFetchedAt()==null?"":" · KIND "+view.calendar().coveredFrom().toString().substring(0,7)
            +" · 달력 조회 "+java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'")
                .format(view.calendarFetchedAt().atZone(ZoneId.of("Asia/Seoul"))));
    }
    /** Display only: saved calendar plus server clock, not an exchange operation feed. */
    public Status status(){
        Instant now=clock.instant();
        View session=view();
        LocalTime time=now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime();
        String phase,label,detail;
        if(session.closed()){
            phase="HOLIDAY";label="휴장 · 정규장 닫힘";detail=session.label();
        }else if(!session.calendarVerified()){
            phase="CHECK_REQUIRED";label="장 상태 확인 대기";
            detail=session.calendarIssue()!=null?session.calendarIssue():"오늘 거래일·특수 개장 시간표 확인 필요";
        }else if(time.isBefore(LocalTime.of(9,0))){
            phase="BEFORE_OPEN";label="장 시작 전 · 정규장 닫힘";
            detail="오늘 거래일 확인 · 일반 정규장은 09:00에 시작해요.";
        }else if(time.isBefore(LocalTime.of(15,30))){
            phase="OPEN";label="정규장 열림";
            detail="거래일·정규장 시간 확인 · 매매는 시세·계좌·종목별 조건을 따로 판단해요.";
        }else{
            phase="AFTER_CLOSE";label="장 마감 · 정규장 닫힘";
            detail="오늘 정규장은 15:30에 마감했어요. 시간외 거래는 현재 봇 운용 대상이 아니에요.";
        }
        return new Status(phase,label,detail,now,session.calendarFetchedAt(),session.calendarVerified(),
            "일반 정규장 09:00–15:30 KST", "거래 달력·한국 시각 기준 · 실시간 거래소 운영/종목 거래정지 피드 아님");
    }
    public View view(){
        LocalDate date=clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        boolean weekend=date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY;
        boolean closed=weekend||CLOSED.containsKey(date);
        if(calendar!=null){
            var official=calendar.view();
            closed=closed||official.closed();
            String label=closed?(weekend?"주말 휴장":official.closed()?official.reason()+" · 주문 대기":CLOSED.get(date)+" · 주문 대기"):
                official.verifiedToday()?"KRX 거래일 확인 · 당일 신선한 시세/계좌 조건으로 주문 판단":
                "거래 달력 확인 대기 · 신규 매수 보류";
            String coverage=official.coveredFrom()==null?"KRX KIND 월별 거래 달력 첫 조회 대기":
                official.coveredFrom()+"~"+official.coveredThrough()+" 월별 달력 · 연간 전체/특수 시간표 아님";
            return new View(date,closed,label,official.source(),coverage,official.verifiedToday(),official.fetchedAt(),official.issue(),official);
        }
        return new View(date,closed,closed?(weekend?"주말 휴장":CLOSED.get(date)+" · 주문 대기"):
            "거래일 달력 전체 미연결 · 당일 시각 검증된 시세가 있어야 모의 주문 가능",
            "https://trn.krx.co.kr/index.jsp","2026-10-05 확인한 10월 휴장일 일부 + 주말, 전체 달력 아님",false,null,null,null);
    }
    public record View(LocalDate date,boolean closed,String label,String source,String coverage,boolean calendarVerified,
                       Instant calendarFetchedAt,String calendarIssue,TradingCalendarService.View calendar){}
    public record Status(String phase,String label,String detail,Instant checkedAt,Instant calendarFetchedAt,
                         boolean calendarVerified,String hours,String basis){}
}
