package com.coffee.branches.api;

import com.coffee.shared.Actor;
import java.util.List;
import java.util.Map;

public interface Branches {
  record Branch(
      String id, String name, String address, String phone, boolean active, int monthlyTarget) {}

  record Hours(int dayOfWeek, int openMinute, int closeMinute) {}

  List<Branch> list(Actor actor, boolean manage);

  Branch requireOpen(String id);

  List<Hours> hours(String branchId);

  boolean openAt(String branchId, long atEpochMs);

  Map<String, Boolean> openAt(List<String> branchIds, long atEpochMs);

  Branch requireOrderable(String id, long atEpochMs);

  List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours);

  Branch save(Actor actor, Branch branch);
}
