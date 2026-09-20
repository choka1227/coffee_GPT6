package com.coffee.orders.internal;

import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
class OrderController {
  private final Orders s;

  OrderController(Orders s) {
    this.s = s;
  }

  record Cash(int tendered) {}

  record Status(String status) {}

  @PostMapping
  Orders.Order create(
      @RequestAttribute Actor actor,
      @RequestBody Orders.Create q,
      @RequestHeader("Idempotency-Key") String key) {
    return s.create(actor, q, key);
  }

  @GetMapping
  List<Orders.Order> list(@RequestAttribute Actor actor) {
    return s.list(actor);
  }

  @GetMapping("/page")
  Orders.Page page(
      @RequestAttribute Actor actor,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String branchId,
      @RequestParam(required = false) Long from,
      @RequestParam(required = false) Long to,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "0") int limit) {
    return s.page(actor, new Orders.Query(status, branchId, from, to, q, cursor, limit));
  }

  @GetMapping("/{id}")
  Orders.Order get(@RequestAttribute Actor actor, @PathVariable String id) {
    return s.get(actor, id);
  }

  @PostMapping("/{id}/cash")
  Orders.Order cash(@RequestAttribute Actor actor, @PathVariable String id, @RequestBody Cash q) {
    return s.cash(actor, id, q.tendered());
  }

  @PatchMapping("/{id}/status")
  Orders.Order status(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody Status q) {
    return s.transition(actor, id, q.status());
  }
}
