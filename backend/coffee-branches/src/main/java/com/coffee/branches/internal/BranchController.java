package com.coffee.branches.internal;

import com.coffee.branches.api.Branches;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches")
class BranchController {
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
}
