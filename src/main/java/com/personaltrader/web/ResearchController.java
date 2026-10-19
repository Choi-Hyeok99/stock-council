package com.personaltrader.web;

import com.personaltrader.education.*;
import com.personaltrader.reports.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
public class ResearchController {
    private final InvestmentKnowledge knowledge;
    private final LearningReadinessService learning;
    private final DailyReportService reports;
    private final DailyReportRepository repository;
    public ResearchController(InvestmentKnowledge knowledge, LearningReadinessService learning, DailyReportService reports, DailyReportRepository repository) {
        this.knowledge = knowledge; this.learning = learning; this.reports = reports; this.repository = repository;
    }
    @GetMapping("/research")
    public String research(@RequestParam(required = false) Long before, Model model) {
        model.addAttribute("curriculum", knowledge.curriculum());
        model.addAttribute("learning", learning.current());
        model.addAttribute("reports", reports.view(before));
        Map<String, List<InvestmentKnowledge.Term>> levels = new LinkedHashMap<>();
        for (String level : List.of("BASIC", "INTERMEDIATE", "ADVANCED")) levels.put(level,
                knowledge.curriculum().terms().stream().filter(term -> term.level().equals(level)).toList());
        model.addAttribute("levels", levels);
        model.addAttribute("levelNames", Map.of("BASIC", "기본 · 거래와 돈", "INTERMEDIATE", "중급 · 판단과 위험", "ADVANCED", "심화 · 성과 검증과 학습"));
        return "research";
    }
    @GetMapping("/api/research") @ResponseBody
    public ResponseEntity<ResearchView> researchData() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ResearchView(knowledge.curriculum(), knowledge.hash(), learning.current(), reports.view(null)));
    }
    @GetMapping("/api/reports") @ResponseBody
    public ResponseEntity<DailyReportService.View> reportData(@RequestParam(required = false) Long before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.view(before));
    }
    @GetMapping("/api/reports/{id}") @ResponseBody
    public ResponseEntity<DailyReportRepository.Entry> reportData(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(find(id));
    }
    @GetMapping("/reports/{id}")
    public String report(@PathVariable long id, Model model) {
        var entry = find(id); model.addAttribute("entry", entry); model.addAttribute("report", entry.report());
        model.addAttribute("scheduledKst", kst(entry.report().scheduledAt()));
        model.addAttribute("generatedKst", kst(entry.report().generatedAt()));
        return "report";
    }
    @GetMapping("/reports/{id}.md") @ResponseBody
    public ResponseEntity<byte[]> markdown(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bot-report-" + id + ".md")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(ReportMarkdown.render(find(id)).getBytes(StandardCharsets.UTF_8));
    }
    private DailyReportRepository.Entry find(long id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private String kst(java.time.Instant value) { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'").format(value.atZone(ZoneId.of("Asia/Seoul"))); }
    public record ResearchView(InvestmentKnowledge.Curriculum curriculum, String curriculumHash,
                               LearningReadinessService.Readiness learning, DailyReportService.View reports) {}
}
