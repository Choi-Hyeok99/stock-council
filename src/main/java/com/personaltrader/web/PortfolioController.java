package com.personaltrader.web;
import com.personaltrader.orders.ManualTradingService;
import com.personaltrader.research.MarketSessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@Controller
public class PortfolioController {
 private final ManualTradingService orders;private final MarketSessionService sessions;
 public PortfolioController(ManualTradingService orders,MarketSessionService sessions){this.orders=orders;this.sessions=sessions;}
 @GetMapping({"/orders","/holdings"})public String page(Model model,CsrfToken token){model.addAttribute("csrfHeader",token.getHeaderName());model.addAttribute("csrfValue",token.getToken());return "holdings";}
 @GetMapping("/api/orders")@ResponseBody public ResponseEntity<?> data(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.displayView());}
 @GetMapping("/api/market-session")@ResponseBody public ResponseEntity<?> session(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.status());}
 @PostMapping("/api/manual-orders/refresh")@ResponseBody public ResponseEntity<?> refresh(){return action(()->orders.refresh(),"증권사 주문·체결·잔고를 다시 확인했어요. 주문은 전송하지 않았어요.");}
 @PostMapping("/api/manual-orders/{id}/cancel")@ResponseBody public ResponseEntity<?> cancel(@PathVariable long id,@RequestBody Confirmation confirmation){
  if(!confirmation.confirmed())return ResponseEntity.status(409).body(Map.of("message","미체결 잔량 취소를 직접 확인해 주세요."));
  return action(()->orders.cancel(id),"취소 요청을 처리했어요. 최종 취소·추가 체결 결과를 확인하세요.");}
 private ResponseEntity<?> action(Runnable task,String message){try{task.run();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("message",message));}
  catch(IllegalStateException|IllegalArgumentException failure){return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("message","계좌·시세·원주문 대조 상태를 다시 확인해 주세요. 결과 미확정 요청은 재전송하지 않아요."));}}
 public record Confirmation(boolean confirmed){}
}
