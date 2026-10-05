package com.coffee.catalog.internal;

import com.coffee.catalog.api.Promotions;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/promotions")
class PromotionController {
  private final Promotions promotions;

  PromotionController(Promotions promotions) {
    this.promotions = promotions;
  }

  @GetMapping
  List<Promotions.Rule> list(@RequestAttribute Actor actor) {
    return promotions.list(actor);
  }

  @GetMapping("/active")
  List<Promotions.ActiveRule> active(
      @RequestAttribute Actor actor,
      @RequestParam(required = false) String branchId) {
    return promotions.active(actor, branchId);
  }

  @PostMapping
  Promotions.Rule save(@RequestAttribute Actor actor, @RequestBody Promotions.Rule rule) {
    return promotions.save(actor, rule);
  }
}
