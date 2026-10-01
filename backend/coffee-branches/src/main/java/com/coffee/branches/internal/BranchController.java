package com.coffee.branches.internal;

import com.coffee.branches.api.Branches;
import com.coffee.shared.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches")
class BranchController {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  record BranchResponse(
      String id,
      String name,
      String address,
      String phone,
      boolean active,
      int monthlyTarget,
      boolean openNow,
      boolean orderableNow,
      Integer minutesUntilLastOrder) {}

  record HoursRequest(List<Branches.Hours> hours) {}

  record HoursResponse(
      String branchId,
      boolean openNow,
      List<Branches.Hours> hours,
      int lastOrderMinutes,
      boolean orderableNow,
      Integer minutesUntilLastOrder) {}

  record DayOverrideResponse(
      int onDate, int dayOfWeek, boolean closed, String note, List<Branches.Hours> hours) {}

  record OverridesResponse(
      String branchId, int from, int to, List<DayOverrideResponse> overrides) {}

  record OverrideRequest(Integer onDate, boolean closed, String note, List<Branches.Hours> hours) {}

  private final Branches service;

  BranchController(Branches s) {
    service = s;
  }

  @GetMapping
  List<BranchResponse> list(
      @RequestAttribute Actor actor, @RequestParam(defaultValue = "false") boolean manage) {
    var branches = service.list(actor, manage);
    var states =
        service.stateAt(
            branches.stream().map(Branches.Branch::id).toList(), System.currentTimeMillis());
    return branches.stream()
        .map(
            branch ->
                new BranchResponse(
                    branch.id(),
                    branch.name(),
                    branch.address(),
                    branch.phone(),
                    branch.active(),
                    branch.monthlyTarget(),
                    states.get(branch.id()).openNow(),
                    states.get(branch.id()).orderableNow(),
                    states.get(branch.id()).minutesUntilLastOrder()))
        .toList();
  }

  @PostMapping
  Branches.Branch save(@RequestAttribute Actor actor, @RequestBody Branches.Branch branch) {
    return service.save(actor, branch);
  }

  @GetMapping("/{id}/hours")
  HoursResponse hours(@PathVariable String id) {
    var hours = service.hours(id);
    var state = service.stateAt(id, System.currentTimeMillis());
    return new HoursResponse(
        id,
        state.openNow(),
        hours,
        service.lastOrderMinutes(id),
        state.orderableNow(),
        state.minutesUntilLastOrder());
  }

  @PutMapping("/{id}/hours")
  HoursResponse saveHours(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody HoursRequest request) {
    var hours = service.saveHours(actor, id, request == null ? null : request.hours());
    var state = service.stateAt(id, System.currentTimeMillis());
    return new HoursResponse(
        id,
        state.openNow(),
        hours,
        service.lastOrderMinutes(id),
        state.orderableNow(),
        state.minutesUntilLastOrder());
  }

  @GetMapping("/{id}/hour-overrides")
  OverridesResponse overrides(
      @PathVariable String id,
      @RequestParam(required = false) Integer from,
      @RequestParam(required = false) Integer to) {
    int actualFrom = from == null ? dateInt(Instant.now().atZone(TAIPEI).toLocalDate()) : from;
    LocalDate fromDate = parseDate(actualFrom);
    int actualTo = to == null ? dateInt(fromDate.plusDays(90)) : to;
    return new OverridesResponse(
        id,
        actualFrom,
        actualTo,
        service.overrides(id, actualFrom, actualTo).stream()
            .map(BranchController::response)
            .toList());
  }

  @PutMapping("/{id}/hour-overrides/{onDate}")
  DayOverrideResponse saveOverride(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @PathVariable int onDate,
      @RequestBody(required = false) OverrideRequest request) {
    Branches.DayOverride saved =
        service.saveOverride(
            actor,
            id,
            request == null
                ? null
                : new Branches.DayOverride(
                    onDate, request.closed(), request.note(), request.hours()));
    return response(saved);
  }

  @DeleteMapping("/{id}/hour-overrides/{onDate}")
  ResponseEntity<Void> deleteOverride(
      @RequestAttribute Actor actor, @PathVariable String id, @PathVariable int onDate) {
    service.deleteOverride(actor, id, onDate);
    return ResponseEntity.noContent().build();
  }

  private static DayOverrideResponse response(Branches.DayOverride override) {
    return new DayOverrideResponse(
        override.onDate(),
        parseDate(override.onDate()).getDayOfWeek().getValue(),
        override.closed(),
        override.note(),
        override.hours());
  }

  private static LocalDate parseDate(int value) {
    try {
      return LocalDate.parse(String.valueOf(value), BASIC_DATE);
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new com.coffee.shared.Problem(400, "日期格式不正確");
    }
  }

  private static int dateInt(LocalDate date) {
    return Integer.parseInt(date.format(BASIC_DATE));
  }
}
