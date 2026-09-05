package com.coffee.identity.internal;

import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
class IdentityController {
  private final Identity s;

  IdentityController(Identity s) {
    this.s = s;
  }

  @GetMapping("/accounts")
  List<Identity.Account> accounts(@RequestAttribute Actor actor) {
    return s.accounts(actor);
  }

  @PostMapping("/accounts")
  Identity.Account save(@RequestAttribute Actor actor, @RequestBody Identity.AccountInput input) {
    return s.saveAccount(actor, input);
  }

  @GetMapping("/roles")
  Map<String, Object> roles(@RequestAttribute Actor actor) {
    return Map.of("roles", s.roles(actor), "permissions", Identity.PERMISSIONS);
  }

  @PostMapping("/roles")
  Identity.Role role(@RequestAttribute Actor actor, @RequestBody Identity.Role role) {
    return s.saveRole(actor, role);
  }
}
