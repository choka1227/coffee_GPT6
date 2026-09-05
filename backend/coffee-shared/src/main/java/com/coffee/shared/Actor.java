package com.coffee.shared;

import java.util.Set;

public record Actor(
    String id,
    String username,
    String name,
    String role,
    String scope,
    String branchId,
    Set<String> permissions) {
  public boolean can(String permission) {
    return permissions.contains(permission);
  }

  public void require(String permission) {
    if (!can(permission)) throw new Problem(403, "沒有此功能的操作權限");
  }

  public boolean global() {
    return "GLOBAL".equals(scope);
  }

  public boolean customer() {
    return "SELF".equals(scope);
  }

  public void branch(String branch) {
    if (!global() && !java.util.Objects.equals(branchId, branch))
      throw new Problem(403, "只能存取所屬分店資料");
  }
}
