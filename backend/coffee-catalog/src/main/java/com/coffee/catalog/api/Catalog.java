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
      boolean active,
      List<OptionGroup> optionGroups) {}

  record OptionItem(
      String id,
      String groupId,
      String name,
      int priceDelta,
      int costDelta,
      boolean active,
      int sortOrder) {}

  record OptionGroup(
      String id,
      String name,
      String selection,
      int minSelect,
      int maxSelect,
      boolean active,
      int sortOrder,
      List<OptionItem> items) {}

  record ResolvedOption(
      String groupId,
      String groupName,
      String optionId,
      String optionName,
      int priceDelta,
      int costDelta) {}

  record BranchAvailability(
      String branchId,
      String productId,
      String productName,
      String availability,
      Long updatedAt,
      String updatedBy) {}

  List<Product> list(Actor a, boolean manage);

  Product sellable(String id);

  Product save(Actor a, Product p);

  List<BranchAvailability> availability(Actor a, String branchId);

  BranchAvailability setAvailability(
      Actor a, String branchId, String productId, String availability);

  List<OptionGroup> productOptions(String productId);

  List<ResolvedOption> resolveOptions(String productId, List<String> optionIds);

  List<OptionGroup> optionGroups(Actor a);

  OptionGroup saveOptionGroup(Actor a, OptionGroup group);

  OptionItem saveOptionItem(Actor a, OptionItem item);

  void bindProductOptions(Actor a, String productId, List<String> groupIds);
}
