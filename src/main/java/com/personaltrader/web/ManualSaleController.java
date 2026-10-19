package com.personaltrader.web;
import com.personaltrader.orders.ManualTradingService;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@Controller
public class ManualSaleController {
    private final ManualTradingService orders;
    private final com.personaltrader.orders.ExecutionTimingService timing;
    public ManualSaleController(ManualTradingService orders,com.personaltrader.orders.ExecutionTimingService timing){this.orders=orders;this.timing=timing;}
    @GetMapping("/sell") public String page(Model model,CsrfToken token){model.addAttribute("csrfHeader",token.getHeaderName());model.addAttribute("csrfValue",token.getToken());return "sell";}
    @GetMapping("/api/manual-sales") @ResponseBody public ResponseEntity<ManualTradingService.ManualSaleView> view(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.manualSaleView());}
    @PostMapping("/api/manual-sales/prepare") @ResponseBody public ResponseEntity<?> prepare(@RequestBody Prepare body){
        return measured("MANUAL_PREPARE",false,()->orders.prepareManualSale(body.code(),body.quantity()));
    }
    @PostMapping("/api/manual-sales/confirm") @ResponseBody public ResponseEntity<?> confirm(@RequestBody Confirm body){
        return measured("MANUAL_CONFIRM",true,()->orders.confirmManualSale(body.intentId(),body.confirmed(),body.lossAcknowledged()));
    }
    @GetMapping("/api/manual-sales/timings") @ResponseBody public ResponseEntity<?> timings(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(timing.recent(orders.ledgerScope()));
    }
    @PostMapping("/api/manual-sales/measure") @ResponseBody public ResponseEntity<?> measure(){
        return measured("RECONCILE_READ",false,()->{orders.refresh();return new Feedback("증권사 조회·대조 시간만 측정했어요. 주문을 전송하지 않았어요.",true);});
    }
    private ResponseEntity<?> measured(String operation,boolean bounded,java.util.function.Supplier<?> action){
        String selectedScope;try{selectedScope=orders.ledgerScope();}catch(RuntimeException unavailable){selectedScope=null;}
        try(var trace=com.personaltrader.orders.OrderLatencyContext.begin(bounded)){
            Object result;HttpStatus status;
            try{result=action.get();status=HttpStatus.OK;
                if(result instanceof ManualTradingService.ManualSaleResult r){trace.outcome(r.status());com.personaltrader.orders.OrderLatencyContext.order(r.orderId());}
                else trace.outcome("READY");
            }catch(IllegalArgumentException|IllegalStateException invalid){
                status=HttpStatus.CONFLICT;trace.outcome(trace.dispatched()?"UNKNOWN":"NOT_SENT");
                String message=invalid instanceof com.personaltrader.orders.OrderLatencyContext.DispatchDeadline?invalid.getMessage():
                    trace.dispatched()?"전송 결과 확인 필요 · 자동 재전송하지 않아요. 보유·주문 화면을 먼저 확인해 주세요.":
                    "주문을 보내지 않았어요. 잔고 대조 중이거나 확인이 만료됐거나 시세·수량이 바뀌었어요. 잠시 뒤 최신 금액을 다시 확인해 주세요.";
                result=new Feedback(message,!trace.dispatched());
            }
            com.personaltrader.orders.OrderLatencyContext.mark("FINISHED");
            timing.save(selectedScope,operation,trace);
            var response=ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Order-Server-Ms",String.valueOf(trace.elapsedMs()));
            if(trace.dispatchMs()!=null)response.header("X-Order-Dispatch-Ms",String.valueOf(trace.dispatchMs()));
            return response.body(result);
        }
    }
    public record Prepare(String code,int quantity){}
    public record Confirm(UUID intentId,boolean confirmed,boolean lossAcknowledged){}
    public record Feedback(String message,boolean notSubmitted){}
}
