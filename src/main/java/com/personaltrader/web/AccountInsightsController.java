package com.personaltrader.web;
import com.personaltrader.research.DisclosureImpactService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class AccountInsightsController {
 private final DisclosureImpactService disclosures;
 public AccountInsightsController(DisclosureImpactService disclosures){this.disclosures=disclosures;}
 @GetMapping("/api/disclosure-impacts")public ResponseEntity<DisclosureImpactService.View> disclosures(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(disclosures.view());}
}
