package com.personaltrader.web;
import com.personaltrader.research.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@Controller
public class CompanyThesisController {
 private final CompanyThesisService theses;private final StrategyObservationService comparison;
 public CompanyThesisController(CompanyThesisService theses,StrategyObservationService comparison){this.theses=theses;this.comparison=comparison;}
 @GetMapping("/theses") public String page(){return "theses";}
 @GetMapping("/api/theses") @ResponseBody public ResponseEntity<CompanyThesisService.View> current(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(theses.view());}
 @GetMapping("/api/theses/{id}") @ResponseBody public ResponseEntity<CompanyThesisService.Entry> history(@PathVariable long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(theses.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));}
 @GetMapping("/api/strategy-observations") @ResponseBody public ResponseEntity<StrategyObservationService.View> observations(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparison.view());}
 @GetMapping("/api/strategy-observations/{id}") @ResponseBody public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> origin(@PathVariable long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comparison.origin(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));}
}
