package com.coffee.catalog.internal;

import com.coffee.catalog.api.Catalog;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/menu")
class CatalogController {
  private final Catalog s;

  CatalogController(Catalog s) {
    this.s = s;
  }

  @GetMapping
  List<Catalog.Product> list(
      @RequestAttribute Actor actor, @RequestParam(defaultValue = "false") boolean manage) {
    return s.list(actor, manage);
  }

  @PostMapping
  Catalog.Product save(@RequestAttribute Actor actor, @RequestBody Catalog.Product p) {
    return s.save(actor, p);
  }
}
