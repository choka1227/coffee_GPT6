package com.coffee.catalog.internal;

import com.coffee.catalog.api.Discounts;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/discounts")
class DiscountController {
  private final Discounts discounts;

  DiscountController(Discounts discounts) {
    this.discounts = discounts;
  }

  @GetMapping
  List<Discounts.Rule> list(@RequestAttribute Actor actor) {
    return discounts.list(actor);
  }

  @PostMapping
  Discounts.Rule save(@RequestAttribute Actor actor, @RequestBody Discounts.Rule rule) {
    return discounts.save(actor, rule);
  }
}
