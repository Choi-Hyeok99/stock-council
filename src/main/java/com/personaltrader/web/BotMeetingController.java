package com.personaltrader.web;

import com.personaltrader.analysis.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class BotMeetingController {
    private final BotMeetingService meetings;
    public BotMeetingController(BotMeetingService meetings) { this.meetings = meetings; }
    @GetMapping("/api/meetings")
    public ResponseEntity<BotMeetingService.View> list(@RequestParam(required = false) Long before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(meetings.view(before));
    }
    @GetMapping("/api/meetings/{id}")
    public ResponseEntity<BotMeetingRepository.Entry> detail(@PathVariable long id) {
        return meetings.find(id).map(entry -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(entry))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
