package com.personaltrader.web;

import com.personaltrader.committee.InvestmentCommitteeService;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class InvestmentCommitteeController {
    private final InvestmentCommitteeService committee;
    private final com.personaltrader.committee.CommitteeAiCoordinator ai;
    public InvestmentCommitteeController(InvestmentCommitteeService committee,com.personaltrader.committee.CommitteeAiCoordinator ai){this.committee=committee;this.ai=ai;}
    @GetMapping("/committee") public String page(Model model,CsrfToken token){
        model.addAttribute("csrfHeader",token.getHeaderName());model.addAttribute("csrfValue",token.getToken());return "committee";
    }
    @GetMapping("/api/committee") @ResponseBody public ResponseEntity<InvestmentCommitteeService.View> data(@RequestParam(required=false)Long id,@RequestParam(required=false)String code,@RequestParam(defaultValue="BUY_REVIEW")String agenda,@RequestParam(required=false)Integer quantity){
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(committee.view(id,code,agenda,quantity));}
        catch(IllegalArgumentException failure){throw new ResponseStatusException(HttpStatus.CONFLICT,"위원회 기록과 보유·계산 수량을 확인해 주세요.");}
    }
    @PostMapping("/api/committee/{id}/review") @ResponseBody public ResponseEntity<Feedback> review(@PathVariable long id,@RequestBody ReviewRequest request){
        try{committee.review(id,request.action(),request.note(),request.clientKey());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Feedback("검토 의견을 저장했어요. 주문은 실행하지 않았어요."));}
        catch(IllegalArgumentException failure){throw new ResponseStatusException(HttpStatus.CONFLICT,"주문 승인은 연결 전이에요. 검토 입력을 확인해 주세요.");}
    }
    @PostMapping("/api/committee/{id}/analyze") @ResponseBody public ResponseEntity<?> analyze(@PathVariable long id,@RequestBody AnalyzeRequest request){
        if(!request.confirmed())return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new AnalyzeFailure("CONFIRM_REQUIRED","기업과 유료 검토 시작을 직접 확인해 주세요."));
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(request.acknowledgeUnknownCosts()
            ?ai.enqueue(id,new com.personaltrader.committee.CommitteeNewReviewConsent.Confirmation(true,request.unknownCallIds())):ai.enqueue(id));}
        catch(com.personaltrader.committee.CommitteeAiCoordinator.RequestRejected failure){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new AnalyzeFailure(failure.code(),failure.getMessage()));}
        catch(IllegalArgumentException failure){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new AnalyzeFailure("REVIEW_NOT_READY","자료 준비·하루 회의 한도·진행 중 회의를 확인해 주세요. 검토는 시작되지 않았어요."));}
    }
    public record AnalyzeRequest(boolean confirmed,boolean acknowledgeUnknownCosts,java.util.List<Long> unknownCallIds){
        public AnalyzeRequest(boolean confirmed){this(confirmed,false,java.util.List.of());}
    }
    public record AnalyzeFailure(String code,String message){}
    @GetMapping("/committee/{id}.md") @ResponseBody public ResponseEntity<String> markdown(@PathVariable long id){
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=committee-"+id+".md").body(committee.markdown(id));}
        catch(IllegalArgumentException failure){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"기록 확인 필요");}
    }
    public record ReviewRequest(String action,String note,String clientKey){}
    public record Feedback(String message){}
}
