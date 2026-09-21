package com.coffee.branches.internal;

import com.coffee.branches.api.Branches;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches")
class BranchController {
  record BranchResponse(
      String id,
      String name,
      String address,
      String phone,
      boolean active,
      int monthlyTarget,
      boolean openNow) {}

  record HoursRequest(List<Branches.Hours> hours) {}

  record HoursResponse(String branchId, boolean openNow, List<Branches.Hours> hours) {}

  private final Branches service;

  BranchController(Branches s) {
    service = s;
  }

  @GetMapping
  List<BranchResponse> list(
      @RequestAttribute Actor actor, @RequestParam(defaultValue = "false") boolean manage) {
    var branches = service.list(actor, manage);
    var open =
        service.openAt(
            branches.stream().map(Branches.Branch::id).toList(), System.currentTimeMillis());
    return branches.stream()
        .map(
            branch ->
                new BranchResponse(
                    branch.id(),
                    branch.name(),
                    branch.address(),
                    branch.phone(),
                    branch.active(),
                    branch.monthlyTarget(),
                    open.get(branch.id())))
        .toList();
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
