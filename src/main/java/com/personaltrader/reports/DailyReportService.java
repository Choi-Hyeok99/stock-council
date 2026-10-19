package com.personaltrader.reports;
import org.springframework.stereotype.Service;
import java.time.LocalTime;
import java.util.List;

/** Historical report reader. New scheduled research reports use ResearchDigestService. */
@Service
public class DailyReportService {
 private final DailyReportRepository repository;
 private final List<Slot> slots=List.of(new Slot("MORNING","당시 장전",LocalTime.of(8,30)),new Slot("MIDDAY","당시 점심",LocalTime.of(12,0)),new Slot("CLOSE","당시 마감",LocalTime.of(15,40)));
 public DailyReportService(DailyReportRepository repository){this.repository=repository;}
 public View view(Long before){return new View(slots,"Asia/Seoul",null,repository.latest(),repository.history(before));}
 public record Slot(String id,String title,LocalTime time){}
 public record View(List<Slot> schedule,String timezone,String issue,DailyReportRepository.Entry latest,DailyReportRepository.History history){}
}
