package com.personaltrader.web;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.Map;

@Controller
public class OperationsController {
    private static final Map<String,String> GUIDES=Map.ofEntries(
        Map.entry("security","docs/SECURITY-REVIEW.md"),Map.entry("manual","docs/WORKING-MANUAL.md"),Map.entry("value","docs/VALUE-REVIEW.md"),
        Map.entry("architecture","docs/ARCHITECTURE.md"),Map.entry("committee","docs/ARCHITECTURE.md"),Map.entry("readme","README.md"),
        Map.entry("berkshire","docs/VALUE-REVIEW.md"),Map.entry("financials","docs/VALUE-REVIEW.md"),Map.entry("value-research","docs/VALUE-REVIEW.md"),
        Map.entry("calendar","docs/MAINTENANCE.md"),Map.entry("weekend","docs/MAINTENANCE.md"),Map.entry("maintenance","docs/MAINTENANCE.md"));
    @GetMapping("/overview") public String overview(){return "overview";}
    @GetMapping("/guide/{slug}") public String guide(@PathVariable String slug,Model model) {
        String file=GUIDES.get(slug);
        if(file==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path=Path.of(file);
        try {
            boolean localDiaryMissing=!Files.exists(path,LinkOption.NOFOLLOW_LINKS);
            if(localDiaryMissing)path=Path.of("docs/ARCHITECTURE.md");
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||Files.size(path)>256000)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            model.addAttribute("content",(localDiaryMissing?"# 로컬 운영 기록 제외\n\n이 개인 운용 문서는 저장소에 포함하지 않습니다. 일반 시스템 구조를 대신 표시합니다.\n\n":"")+Files.readString(path));
            return "guide";
        } catch(java.io.IOException unavailable) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
}
