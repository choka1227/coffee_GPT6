package com.coffee.branches.internal;

import com.coffee.branches.api.Branches;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches")
class BranchController {
  record HoursRequest(List<Branches.Hours> hours) {}

  record HoursResponse(String branchId, boolean openNow, List<Branches.Hours> hours) {}

  private final Branches service;

  BranchController(Branches s) {
    service = s;
  }

  @GetMapping
  List<Branches.Branch> list(
      @RequestAttribute Actor actor, @RequestParam(defaultValue = "false") boolean manage) {
    return service.list(actor, manage);
  }

  @PostMapping
  Branches.Branch save(@RequestAttribute Actor actor, @RequestBody Branches.Branch branch) {
    return service.save(actor, branch);
  }

  @GetMapping("/{id}/hours")
  HoursResponse hours(@PathVariable String id) {
    var hours = service.hours(id);
    return new HoursResponse(id, service.openAt(id, System.currentTimeMillis()), hours);
  }

  @PutMapping("/{id}/hours")
  HoursResponse saveHours(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody HoursRequest request) {
    var hours = service.saveHours(actor, id, request == null ? null : request.hours());
    return new HoursResponse(id, service.openAt(id, System.currentTimeMillis()), hours);
  }
}
