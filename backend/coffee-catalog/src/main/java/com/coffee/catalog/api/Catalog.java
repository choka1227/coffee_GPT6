package com.coffee.catalog.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Catalog {
  record Product(
      String id,
      String name,
      String subtitle,
      String category,
      int price,
      int cost,
      String image,
      String badge,
      boolean active) {}

  List<Product> list(Actor a, boolean manage);

  Product sellable(String id);

  Product save(Actor a, Product p);
}
