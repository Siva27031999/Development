// controller/LookupController.java
package com.siva.portal.controller;

import com.siva.portal.service.LookupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/lookup")
public class LookupController {
  private final LookupService service;

  public LookupController(LookupService service) {
    this.service = service;
  }

  @GetMapping
  public List<String> suggest(@RequestParam(defaultValue = "") String q,
                              @RequestParam(defaultValue = "8") int limit) {
    return service.suggest(q, Math.max(1, Math.min(50, limit)));
  }

  static record AddRequest(String value) {}
  static record AddResponse(boolean accepted) {}

  @PostMapping
  public ResponseEntity<AddResponse> add(@RequestBody AddRequest req) {
    if (req == null || req.value() == null || req.value().trim().isEmpty())
      return ResponseEntity.badRequest().build();
    service.addIfAbsent(req.value());
    return ResponseEntity.ok(new AddResponse(true));
  }
}
