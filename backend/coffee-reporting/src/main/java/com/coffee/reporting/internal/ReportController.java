package com.coffee.reporting.internal;

import com.coffee.shared.Actor;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
class ReportController {
  private final ReportService s;

  ReportController(ReportService s) {
    this.s = s;
  }

  @GetMapping
  Map<String, Object> report(
      @RequestAttribute Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) String branchId) {
    return s.report(actor, month, branchId);
  }
}
