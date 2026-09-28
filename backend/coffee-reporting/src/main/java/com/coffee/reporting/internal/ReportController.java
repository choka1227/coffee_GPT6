package com.coffee.reporting.internal;

import com.coffee.reporting.api.Reports;
import com.coffee.shared.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
class ReportController {
  private final Reports s;

  ReportController(Reports s) {
    this.s = s;
  }

  @GetMapping
  Reports.MonthlyReport report(
      @RequestAttribute Actor actor,
      @RequestParam String month,
      @RequestParam(required = false) String branchId) {
    return s.report(actor, month, branchId);
  }
}
