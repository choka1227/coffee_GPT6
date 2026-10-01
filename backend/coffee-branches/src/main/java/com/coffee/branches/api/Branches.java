package com.coffee.branches.api;

import com.coffee.shared.Actor;
import java.util.List;
import java.util.Map;

public interface Branches {
  record Branch(
      String id, String name, String address, String phone, boolean active, int monthlyTarget) {}

  record Hours(int dayOfWeek, int openMinute, int closeMinute) {}

  record DayOverride(int onDate, boolean closed, String note, List<Hours> hours) {}

  record OpenState(boolean openNow, boolean orderableNow, Integer minutesUntilLastOrder) {}

  List<Branch> list(Actor actor, boolean manage);

  Branch requireOpen(String id);

  List<Hours> hours(String branchId);

  List<DayOverride> overrides(String branchId, int fromDate, int toDate);

  boolean openAt(String branchId, long atEpochMs);

  Map<String, Boolean> openAt(List<String> branchIds, long atEpochMs);

  int lastOrderMinutes(String branchId);

  OpenState stateAt(String branchId, long atEpochMs);

  Map<String, OpenState> stateAt(List<String> branchIds, long atEpochMs);

  Branch requireOrderable(String id, long atEpochMs);

  List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours);

  List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours, int lastOrderMinutes);

  DayOverride saveOverride(Actor actor, String branchId, DayOverride override);

  void deleteOverride(Actor actor, String branchId, int onDate);

  Branch save(Actor actor, Branch branch);
}
