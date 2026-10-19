package com.personaltrader.web;

import com.personaltrader.research.DartFinancialService;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class FinancialController {
    private final DartFinancialService financials;
    public FinancialController(DartFinancialService financials){this.financials=financials;}
    @GetMapping("/financials") public String page(){return "financials";}
    @GetMapping("/api/financials") @ResponseBody
    public ResponseEntity<DartFinancialService.View> current(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(financials.view());}
    @GetMapping("/api/financials/{id}") @ResponseBody
    public ResponseEntity<DartFinancialService.View> history(@PathVariable long id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(financials.find(id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}
