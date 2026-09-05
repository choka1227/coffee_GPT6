package com.coffee.identity.api;

import com.coffee.shared.Actor;
import java.util.*;

public interface Identity {
  List<String> PERMISSIONS =
      List.of(
          "ORDER_CREATE",
          "POS_ORDER",
          "ORDER_MANAGE",
          "REPORT_STORE",
          "REPORT_ALL",
          "BRANCH_MANAGE",
          "ACCOUNT_MANAGE",
          "ROLE_MANAGE",
          "MENU_MANAGE");

  record Account(
      String id, String username, String name, String role, String branchId, boolean active) {}

  record AccountInput(
      String id,
      String username,
      String name,
      String role,
      String branchId,
      boolean active,
      String password) {}

  record Role(String code, String name, String scope, Set<String> permissions) {}

  int sessionVersion(String id);

  Actor authenticate(String username, String password);

  Actor find(String id);

  List<Account> accounts(Actor a);

  Account saveAccount(Actor a, AccountInput input);

  List<Role> roles(Actor a);

  Role saveRole(Actor a, Role r);

  void password(Actor a, String oldPassword, String newPassword);
}
