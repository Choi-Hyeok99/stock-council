package com.personaltrader.web;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** These paths are retired in the research build. There is no execution service behind them. */
@Controller
public class RetiredTradingController {
    @GetMapping({"/buy-plan","/performance"})
    public String oldPage(){return "redirect:/committee";}
    @RequestMapping({"/api/sell","/api/sell/**","/api/buy-plan","/api/buy-plan/**",
        "/api/paper-account","/api/paper-account/**","/api/paper/**","/api/trading/**","/ideas/automation",
        "/orders/**","/sell/**","/performance/**","/automation/**","/api/committee/*/trade-preview"})
    @ResponseBody public ResponseEntity<Map<String,String>> retired(){return ResponseEntity.status(HttpStatus.GONE).cacheControl(CacheControl.noStore())
        .body(Map.of("code","TRADING_REMOVED","message","이전 자동매매 경로는 종료되었습니다. 보유 조회와 직접 모의 매도는 메뉴에서 이용하세요."));}
}
