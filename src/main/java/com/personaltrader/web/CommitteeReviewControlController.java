package com.personaltrader.web;

import com.personaltrader.committee.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommitteeReviewControlController {
    private final CommitteeReviewControlService controls;
    public CommitteeReviewControlController(CommitteeReviewControlService controls){this.controls=controls;}
    @PostMapping("/api/committee/{id}/control")
    public ResponseEntity<?> control(@PathVariable long id,@RequestBody CommitteeReviewControlService.Decision decision){
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(controls.act(id,decision));}
        catch(CommitteeAiCoordinator.RequestRejected failure){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new InvestmentCommitteeController.AnalyzeFailure(failure.code(),failure.getMessage()));}
        catch(IllegalArgumentException failure){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new InvestmentCommitteeController.AnalyzeFailure("CONTROL_NOT_READY","검토 상태와 선택 내용을 새로 확인해 주세요. 자동 재요청하지 않아요."));}
    }
}
