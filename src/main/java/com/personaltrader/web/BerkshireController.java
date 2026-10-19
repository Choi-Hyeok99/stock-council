package com.personaltrader.web;

import com.personaltrader.research.BerkshireReviewService;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class BerkshireController {
    private final BerkshireReviewService reviews;
    public BerkshireController(BerkshireReviewService reviews) { this.reviews=reviews; }
    @GetMapping("/philosophy") public String page() { return "philosophy"; }
    @GetMapping("/api/philosophy") @ResponseBody
    public ResponseEntity<BerkshireReviewService.View> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.view());
    }
    @GetMapping("/api/philosophy/{id}") @ResponseBody
    public ResponseEntity<BerkshireReviewService.Entry> history(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.find(id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}
