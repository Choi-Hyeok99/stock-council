package com.personaltrader.analysis;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;

/** Read-only archive of the retired rule journal. New real AI dialogues live in committee. */
@Service
public class BotMeetingService {
 private final BotMeetingRepository repository;
 public BotMeetingService(BotMeetingRepository repository){this.repository=repository;}
 public View view(Long before){var latest=repository.latest();return new View(latest==null?null:latest.occurredAt(),"과거 규칙 관찰 기록 · 현재 AI 대화는 투자위원회에서 확인",latest,repository.history(before));}
 public Optional<BotMeetingRepository.Entry> find(long id){return repository.find(id);}
 public record View(Instant checkedAt,String issue,BotMeetingRepository.Entry latest,BotMeetingRepository.History history){}
}
