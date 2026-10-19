package com.personaltrader.web;

import com.personaltrader.committee.CommitteeReviewLibrary;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class CommitteeReviewLibraryController {
    private final CommitteeReviewLibrary library;
    public CommitteeReviewLibraryController(CommitteeReviewLibrary library){this.library=library;}
    @GetMapping("/committee/reviews") public String page(Model model,CsrfToken token){
        model.addAttribute("csrfHeader",token.getHeaderName());model.addAttribute("csrfValue",token.getToken());model.addAttribute("reviewLibrary",true);return "committee";
    }
    @GetMapping("/api/committee/reviews") @ResponseBody public ResponseEntity<CommitteeReviewLibrary.Inbox> data(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(library.view());
    }
    @PostMapping("/api/committee/reviews/{id}/seen") @ResponseBody public ResponseEntity<Feedback> seen(@PathVariable long id){
        try{library.acknowledge(id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Feedback("결과를 확인한 것으로 표시했어요. 매수·매도 승인은 아니에요."));}
        catch(IllegalArgumentException failure){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new Feedback("현재 계좌의 완료된 검토를 선택해 주세요."));}
    }
    public record Feedback(String message){}
}
