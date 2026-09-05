package com.coffee.payments.internal;

import com.coffee.shared.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
class EcpayController {
  private final EcpayService s;

  EcpayController(EcpayService s) {
    this.s = s;
  }

  @GetMapping("/config")
  Map<String, Object> config() {
    return s.config();
  }

  @PostMapping("/ecpay/{id}")
  Map<String, Object> checkout(@RequestAttribute Actor actor, @PathVariable String id) {
    return s.checkout(actor, id);
  }

  @PostMapping(
      value = "/ecpay/callback",
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> callback(@RequestParam MultiValueMap<String, String> form) {
    try {
      Problem.check(form.values().stream().allMatch(v -> v.size() == 1), "重複參數");
      return ResponseEntity.ok(s.callback(form.toSingleValueMap()));
    } catch (Problem e) {
      return ResponseEntity.status(e.status).body("0|Invalid notification");
    }
  }
}
