package com.coffee.catalog.internal;

import com.coffee.catalog.api.Catalog;
import com.coffee.shared.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/menu")
class CatalogController {
  record GroupBinding(List<String> groupIds) {}
  record AvailabilityInput(String branchId, String productId, String availability) {}
  record StockInput(String branchId, String productId, Integer quantity) {}

  private final Catalog s;

  CatalogController(Catalog s) {
    this.s = s;
  }

  @GetMapping
  List<Catalog.Product> list(
      @RequestAttribute Actor actor,
      @RequestParam(defaultValue = "false") boolean manage,
      @RequestParam(required = false) String branchId) {
    return s.list(actor, manage, branchId);
  }

  @PostMapping
  Catalog.Product save(@RequestAttribute Actor actor, @RequestBody Catalog.Product p) {
    return s.save(actor, p);
  }

  @GetMapping("/availability")
  List<Catalog.BranchAvailability> availability(
      @RequestAttribute Actor actor, @RequestParam String branchId) {
    return s.availability(actor, branchId);
  }

  @PostMapping("/availability")
  Catalog.BranchAvailability setAvailability(
      @RequestAttribute Actor actor, @RequestBody AvailabilityInput input) {
    return s.setAvailability(actor, input.branchId(), input.productId(), input.availability());
  }

  @GetMapping("/stock")
  List<Catalog.ProductStock> stock(
      @RequestAttribute Actor actor, @RequestParam String branchId) {
    return s.stock(actor, branchId);
  }

  @PostMapping("/stock")
  Catalog.ProductStock setStock(
      @RequestAttribute Actor actor, @RequestBody StockInput input) {
    return s.setStock(actor, input.branchId(), input.productId(), input.quantity());
  }

  @GetMapping("/options")
  List<Catalog.OptionGroup> options(@RequestAttribute Actor actor) {
    return s.optionGroups(actor);
  }

  @PostMapping("/options/groups")
  Catalog.OptionGroup saveGroup(
      @RequestAttribute Actor actor, @RequestBody Catalog.OptionGroup group) {
    return s.saveOptionGroup(actor, group);
  }

  @PostMapping("/options/items")
  Catalog.OptionItem saveItem(
      @RequestAttribute Actor actor, @RequestBody Catalog.OptionItem item) {
    return s.saveOptionItem(actor, item);
  }

  @PostMapping("/{productId}/options")
  void bind(
      @RequestAttribute Actor actor,
      @PathVariable String productId,
      @RequestBody GroupBinding binding) {
    s.bindProductOptions(actor, productId, binding.groupIds());
  }
}
