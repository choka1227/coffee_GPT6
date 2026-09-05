package com.coffee.branches.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Branches {
  record Branch(
      String id, String name, String address, String phone, boolean active, int monthlyTarget) {}

  List<Branch> list(Actor actor, boolean manage);

  Branch requireOpen(String id);

  Branch save(Actor actor, Branch branch);
}
