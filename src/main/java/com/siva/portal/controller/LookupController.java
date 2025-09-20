// controller/LookupController.java
package com.siva.portal.controller;

import com.siva.portal.service.LookupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lookup")
public class LookupController {
  private final LookupService service;

  public LookupController(LookupService service) {
    this.service = service;
  }

  // ---- SUGGEST ----
  @GetMapping({"", "/{key}"})
  public List<String> suggest(@PathVariable(name = "key", required = false) String key,
                              @RequestParam(defaultValue = "") String q,
                              @RequestParam(defaultValue = "8") int limit) {
    String k = key == null ? LookupService.DEFAULT_KEY : key;
    return service.suggest(k, q, Math.max(1, Math.min(50, limit)));
  }

  // ---- ADD ----
  static record AddRequest(String value) {}
  static record AddResponse(boolean accepted) {}

  @PostMapping({"", "/{key}"})
  public ResponseEntity<AddResponse> add(@PathVariable(name = "key", required = false) String key,
                                         @RequestBody AddRequest req) {
    if (req == null || req.value() == null || req.value().trim().isEmpty())
      return ResponseEntity.badRequest().build();
    String k = key == null ? LookupService.DEFAULT_KEY : key;
    service.addIfAbsent(k, req.value());
    return ResponseEntity.ok(new AddResponse(true));
  }

  // ---- DELETE ----
  @DeleteMapping({"", "/{key}"})
  public ResponseEntity<Void> delete(@PathVariable(name = "key", required = false) String key,
                                     @RequestParam String value) {
    if (value == null || value.trim().isEmpty())
      return ResponseEntity.badRequest().build();
    String k = key == null ? LookupService.DEFAULT_KEY : key;
    service.deleteValue(k, value);
    return ResponseEntity.noContent().build();
  }

  @GetMapping({"/suggest", "/{key}/suggest"})
  public List<String> suggestAction(@PathVariable(required = false) String key,
                                    @RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "8") int limit) {
    return suggest(key, q, limit);
  }

  @PostMapping(path = {"/add", "/{key}/add"})
  public ResponseEntity<AddResponse> addAction(@PathVariable(required = false) String key,
                                               @RequestBody AddRequest req) {
    return add(key, req);
  }

  @DeleteMapping({"/delete", "/{key}/delete"})
  public ResponseEntity<Void> deleteAction(@PathVariable(required = false) String key,
                                           @RequestParam String value) {
    return delete(key, value);
  }

}

