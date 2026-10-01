package com.coffee.branches.internal;

import com.coffee.audit.api.Audit;
import com.coffee.branches.api.Branches;
import com.coffee.shared.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchService implements Branches {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final JdbcTemplate db;
  private final Audit audit;

  public BranchService(JdbcTemplate db, Audit audit) {
    this.db = db;
    this.audit = audit;
  }

  private Branch row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Branch(
        r.getString("id"),
        r.getString("name"),
        r.getString("address"),
        r.getString("phone"),
        r.getBoolean("active"),
        r.getInt("monthly_target"));
  }

  public List<Branch> list(Actor a, boolean manage) {
    if (manage) {
      a.require("BRANCH_MANAGE");
    }
    return db.query(
        "select * from branches " + (manage ? "" : "where active=true ") + " order by name",
        this::row);
  }

  public Branch requireOpen(String id) {
    return db.query("select * from branches where id=? and active=true", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new Problem(400, "分店不存在或已暫停營業"));
  }

  public List<Hours> hours(String branchId) {
    if (db.queryForObject("select count(*) from branches where id=?", Integer.class, branchId) == 0)
      throw new Problem(404, "找不到分店");
    return loadHours(branchId);
  }

  private List<Hours> loadHours(String branchId) {
    return db.query(
        "select day_of_week,open_minute,close_minute from branch_hours"
            + " where branch_id=? order by day_of_week,open_minute",
        (r, n) -> new Hours(r.getInt(1), r.getInt(2), r.getInt(3)),
        branchId);
  }

  public List<DayOverride> overrides(String branchId, int fromDate, int toDate) {
    if (db.queryForObject("select count(*) from branches where id=?", Integer.class, branchId) == 0)
      throw new Problem(404, "找不到分店");
    LocalDate from = parseDate(fromDate, "日期格式不正確");
    LocalDate to = parseDate(toDate, "日期格式不正確");
    if (from.isAfter(to)) throw new Problem(400, "日期範圍不正確");
    if (ChronoUnit.DAYS.between(from, to) > 400) throw new Problem(400, "日期範圍最多 400 天");
    return loadOverrides(branchId, fromDate, toDate);
  }

  private List<DayOverride> loadOverrides(String branchId, int fromDate, int toDate) {
    Map<Integer, MutableOverride> rows = new LinkedHashMap<>();
    db.query(
        "select o.on_date,o.closed,o.note,h.open_minute,h.close_minute"
            + " from branch_day_overrides o left join branch_day_override_hours h"
            + " on h.branch_id=o.branch_id and h.on_date=o.on_date"
            + " where o.branch_id=? and o.on_date between ? and ?"
            + " order by o.on_date,h.open_minute",
        (RowCallbackHandler) result -> addOverrideRow(rows, result),
        branchId,
        fromDate,
        toDate);
    return rows.values().stream().map(MutableOverride::value).toList();
  }

  public boolean openAt(String branchId, long atEpochMs) {
    return stateAt(branchId, atEpochMs).openNow();
  }

  public int lastOrderMinutes(String branchId) {
    return db.query(
            "select last_order_minutes from branches where id=?",
            (result, row) -> result.getInt(1),
            branchId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new Problem(404, "找不到分店"));
  }

  public OpenState stateAt(String branchId, long atEpochMs) {
    List<Hours> weekly = hours(branchId);
    LocalDate today = localDate(atEpochMs);
    Map<LocalDate, DayOverride> overrides =
        indexOverrides(loadOverrides(branchId, dateInt(today.minusDays(1)), dateInt(today)));
    return state(windowAt(resolver(weekly, overrides), atEpochMs), lastOrderMinutes(branchId));
  }

  public Map<String, Boolean> openAt(List<String> branchIds, long atEpochMs) {
    Map<String, OpenState> states = stateAt(branchIds, atEpochMs);
    Map<String, Boolean> result = new LinkedHashMap<>();
    states.forEach((branchId, state) -> result.put(branchId, state.openNow()));
    return result;
  }

  public Map<String, OpenState> stateAt(List<String> branchIds, long atEpochMs) {
    if (branchIds.isEmpty()) return Map.of();
    Map<String, List<Hours>> schedules = new HashMap<>();
    Map<String, Integer> lastOrders = new HashMap<>();
    db.query(
        "select b.id,b.last_order_minutes,h.day_of_week,h.open_minute,h.close_minute"
            + " from branches b left join branch_hours h on h.branch_id=b.id"
            + " order by b.id,h.day_of_week,h.open_minute",
        result -> {
          String branchId = result.getString(1);
          lastOrders.put(branchId, result.getInt(2));
          Integer day = (Integer) result.getObject(3);
          if (day != null)
            schedules
                .computeIfAbsent(branchId, ignored -> new ArrayList<>())
                .add(new Hours(day, result.getInt(4), result.getInt(5)));
        });
    Map<String, Map<LocalDate, DayOverride>> overrides = new HashMap<>();
    LocalDate today = localDate(atEpochMs);
    db.query(
          "select o.branch_id,o.on_date,o.closed,o.note,h.open_minute,h.close_minute"
              + " from branch_day_overrides o left join branch_day_override_hours h"
              + " on h.branch_id=o.branch_id and h.on_date=o.on_date"
              + " where o.on_date in (?,?) order by o.branch_id,o.on_date,h.open_minute",
          (RowCallbackHandler)
              result -> {
                String branchId = result.getString(1);
                Map<LocalDate, DayOverride> branch =
                    overrides.computeIfAbsent(branchId, ignored -> new LinkedHashMap<>());
                LocalDate date = parseDate(result.getInt(2), "日期格式不正確");
                DayOverride current = branch.get(date);
                List<Hours> periods =
                    current == null ? new ArrayList<>() : new ArrayList<>(current.hours());
                Integer open = (Integer) result.getObject(5);
                if (open != null)
                  periods.add(new Hours(date.getDayOfWeek().getValue(), open, result.getInt(6)));
                branch.put(
                    date,
                    new DayOverride(
                        result.getInt(2), result.getBoolean(3), result.getString(4), periods));
              },
          dateInt(today),
          dateInt(today.minusDays(1)));
    Map<String, OpenState> result = new LinkedHashMap<>();
    for (String branchId : branchIds) {
      result.put(
          branchId,
          state(
              windowAt(
                  resolver(
                      schedules.getOrDefault(branchId, List.of()),
                      overrides.getOrDefault(branchId, Map.of())),
                  atEpochMs),
              lastOrders.getOrDefault(branchId, 0)));
    }
    return result;
  }

  public Branch requireOrderable(String id, long atEpochMs) {
    Branch branch = requireOpen(id);
    List<Hours> weekly = hours(id);
    LocalDate today = localDate(atEpochMs);
    Map<LocalDate, DayOverride> overrides =
        indexOverrides(loadOverrides(id, dateInt(today.minusDays(1)), dateInt(today)));
    Resolver resolver = resolver(weekly, overrides);
    DaySchedule todaySchedule = resolver.resolve(today);
    OpenWindow window = windowAt(resolver, atEpochMs);
    int last = lastOrderMinutes(id);
    if (window instanceof OpenWindow.NotOpen) throw new Problem(400, closedMessage(todaySchedule));
    if (window instanceof OpenWindow.ClosesIn closes && closes.minutes() <= last)
      throw new Problem(400, lastOrderMessage(atEpochMs, closes.minutes(), last, todaySchedule));
    return branch;
  }

  public static boolean isOpenAt(List<Hours> schedule, long atEpochMs) {
    return isOpenAt(resolver(schedule, Map.of()), atEpochMs);
  }

  public sealed interface DaySchedule {
    record AlwaysOpen() implements DaySchedule {}

    record Closed(String note) implements DaySchedule {}

    record Periods(List<Hours> periods, boolean fromOverride) implements DaySchedule {}
  }

  @FunctionalInterface
  public interface Resolver {
    DaySchedule resolve(LocalDate date);
  }

  public static DaySchedule resolveDay(
      LocalDate date, boolean weeklyEmpty, List<Hours> weeklyForThatWeekday, DayOverride override) {
    if (override != null && override.closed()) return new DaySchedule.Closed(override.note());
    // An override is a complete replacement, not an addition to the weekly schedule.
    if (override != null) return new DaySchedule.Periods(override.hours(), true);
    if (weeklyEmpty) return new DaySchedule.AlwaysOpen();
    return new DaySchedule.Periods(weeklyForThatWeekday, false);
  }

  public static boolean isOpenAt(Resolver resolver, long atEpochMs) {
    return !(windowAt(resolver, atEpochMs) instanceof OpenWindow.NotOpen);
  }

  public sealed interface OpenWindow {
    record NotOpen() implements OpenWindow {}

    record NoClosingTime() implements OpenWindow {}

    record ClosesIn(int minutes) implements OpenWindow {}
  }

  public static OpenWindow windowAt(Resolver resolver, long atEpochMs) {
    var local = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI);
    LocalDate today = local.toLocalDate();
    LocalDate previous = today.minusDays(1);
    int minute = local.getHour() * 60 + local.getMinute();
    DaySchedule current = resolver.resolve(today);
    if (current instanceof DaySchedule.Closed) return new OpenWindow.NotOpen();
    if (current instanceof DaySchedule.AlwaysOpen) return new OpenWindow.NoClosingTime();
    DaySchedule.Periods periods = (DaySchedule.Periods) current;
    for (Hours period : periods.periods()) {
      if (period.closeMinute() > period.openMinute()
          && period.openMinute() <= minute
          && minute < period.closeMinute())
        return new OpenWindow.ClosesIn(period.closeMinute() - minute);
      if (period.closeMinute() <= period.openMinute() && minute >= period.openMinute())
        return new OpenWindow.ClosesIn(period.closeMinute() + 1440 - minute);
    }
    if (periods.fromOverride()) return new OpenWindow.NotOpen();
    DaySchedule prior = resolver.resolve(previous);
    if (!(prior instanceof DaySchedule.Periods priorPeriods)) return new OpenWindow.NotOpen();
    // G19 §13.4 forbids override periods from crossing midnight; only weekly tails can match.
    for (Hours period : priorPeriods.periods()) {
      if (period.closeMinute() <= period.openMinute() && minute < period.closeMinute())
        return new OpenWindow.ClosesIn(period.closeMinute() - minute);
    }
    return new OpenWindow.NotOpen();
  }

  public static OpenState state(OpenWindow window, int lastOrderMinutes) {
    if (window instanceof OpenWindow.NotOpen) return new OpenState(false, false, null);
    if (window instanceof OpenWindow.NoClosingTime) return new OpenState(true, true, null);
    int remaining = ((OpenWindow.ClosesIn) window).minutes();
    return remaining > lastOrderMinutes
        ? new OpenState(true, true, remaining - lastOrderMinutes)
        : new OpenState(true, false, null);
  }

  private static String closedMessage(DaySchedule day) {
    if (day instanceof DaySchedule.Closed closed)
      return closed.note() == null || closed.note().isBlank()
          ? "分店今日公休"
          : "分店今日公休（" + closed.note() + "）";
    if (day instanceof DaySchedule.AlwaysOpen) throw new IllegalStateException("always open");
    List<Hours> schedule = ((DaySchedule.Periods) day).periods();
    String periods =
        schedule.stream()
            .map(
                period ->
                    formatMinute(period.openMinute())
                        + "–"
                        + (period.closeMinute() <= period.openMinute() ? "隔日 " : "")
                        + formatMinute(period.closeMinute()))
            .reduce((left, right) -> left + "、" + right)
            .orElse(null);
    return periods == null ? "分店今日未營業" : "分店目前未營業（今日營業時間 " + periods + "）";
  }

  private static String lastOrderMessage(
      long atEpochMs, int minutesUntilClose, int lastOrderMinutes, DaySchedule today) {
    var local = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI);
    int nowMinute = local.getHour() * 60 + local.getMinute();
    int cutoff = Math.floorMod(nowMinute + minutesUntilClose - lastOrderMinutes, 1440);
    Integer next = nextOrderableStartToday(today, nowMinute, lastOrderMinutes);
    String tail = next == null ? "請於下一個營業時段再下單" : "請於今日 " + formatMinute(next) + " 起的營業時段再下單";
    return "分店已停止接單（最後點餐時間 " + formatMinute(cutoff) + "），" + tail;
  }

  static Integer nextOrderableStartToday(DaySchedule today, int nowMinute, int lastOrderMinutes) {
    if (!(today instanceof DaySchedule.Periods periods)) return null;
    return periods.periods().stream()
        .filter(period -> period.openMinute() > nowMinute)
        .filter(period -> periodLength(period) > lastOrderMinutes)
        .map(Hours::openMinute)
        .min(Integer::compare)
        .orElse(null);
  }

  static int periodLength(Hours period) {
    return period.closeMinute() > period.openMinute()
        ? period.closeMinute() - period.openMinute()
        : period.closeMinute() + 1440 - period.openMinute();
  }

  private static String formatMinute(int minute) {
    if (minute == 1440) return "24:00";
    return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }

  @Transactional
  public List<Hours> saveHours(Actor actor, String branchId, List<Hours> schedule) {
    actor.require("BRANCH_MANAGE");
    if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
    return saveHours(actor, branchId, schedule, lastOrderMinutes(branchId));
  }

  @Transactional
  public List<Hours> saveHours(
      Actor actor, String branchId, List<Hours> schedule, int lastOrderMinutes) {
    actor.require("BRANCH_MANAGE");
    if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
    Problem.check(schedule != null, "請提供營業時段");
    Problem.check(lastOrderMinutes >= 0 && lastOrderMinutes <= 120, "最後點餐提前時間需為 0–120 分鐘");
    if (db.queryForList("select id from branches where id=? for update", String.class, branchId)
        .isEmpty()) throw new Problem(404, "找不到分店");
    validateHours(schedule);
    db.update("update branches set last_order_minutes=? where id=?", lastOrderMinutes, branchId);
    db.update("delete from branch_hours where branch_id=?", branchId);
    for (Hours period : schedule) {
      db.update(
          "insert into branch_hours(id,branch_id,day_of_week,open_minute,close_minute)"
              + " values(?,?,?,?,?)",
          Ids.next(),
          branchId,
          period.dayOfWeek(),
          period.openMinute(),
          period.closeMinute());
    }
    audit.record(
        actor,
        "BRANCH_HOURS_SAVE",
        branchId,
        branchId,
        "更新營業時間（" + schedule.size() + " 段，最後點餐提前 " + lastOrderMinutes + " 分）");
    return loadHours(branchId);
  }

  static void validateHours(List<Hours> schedule) {
    Problem.check(schedule.size() <= 28, "每天最多 4 個時段");
    Map<Integer, Integer> counts = new HashMap<>();
    Map<Integer, List<MinuteRange>> ranges = new HashMap<>();
    for (Hours period : schedule) {
      Problem.check(
          period != null && period.dayOfWeek() >= 1 && period.dayOfWeek() <= 7, "星期格式不正確");
      Problem.check(period.openMinute() >= 0 && period.openMinute() <= 1439, "開始時間不正確");
      Problem.check(period.closeMinute() >= 1 && period.closeMinute() <= 1440, "結束時間不正確");
      Problem.check(counts.merge(period.dayOfWeek(), 1, Integer::sum) <= 4, "每天最多 4 個時段");
      if (period.closeMinute() > period.openMinute()) {
        addRange(ranges, period.dayOfWeek(), period.openMinute(), period.closeMinute());
      } else {
        addRange(ranges, period.dayOfWeek(), period.openMinute(), 1440);
        int next = period.dayOfWeek() == 7 ? 1 : period.dayOfWeek() + 1;
        addRange(ranges, next, 0, period.closeMinute());
      }
    }
    for (List<MinuteRange> day : ranges.values()) {
      day.sort(Comparator.comparingInt(MinuteRange::start));
      int previousEnd = -1;
      for (MinuteRange range : day) {
        Problem.check(range.start() >= previousEnd, "同一天的營業時段不能重疊");
        previousEnd = Math.max(previousEnd, range.end());
      }
    }
  }

  static void validateDayPeriods(List<Hours> periods) {
    Problem.check(periods.size() <= 4, "每天最多 4 個時段");
    List<MinuteRange> ranges = new ArrayList<>();
    for (Hours period : periods) {
      Problem.check(
          period != null && period.openMinute() >= 0 && period.openMinute() <= 1439, "開始時間不正確");
      Problem.check(period.closeMinute() >= 1 && period.closeMinute() <= 1440, "結束時間不正確");
      Problem.check(period.closeMinute() > period.openMinute(), "例外日的時段不能跨夜");
      ranges.add(new MinuteRange(period.openMinute(), period.closeMinute()));
    }
    ranges.sort(Comparator.comparingInt(MinuteRange::start));
    int previousEnd = -1;
    for (MinuteRange range : ranges) {
      Problem.check(range.start() >= previousEnd, "同一天的營業時段不能重疊");
      previousEnd = range.end();
    }
  }

  @Transactional
  public DayOverride saveOverride(Actor actor, String branchId, DayOverride override) {
    actor.require("BRANCH_MANAGE");
    if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
    Problem.check(override != null, "請提供例外日設定");
    parseDate(override.onDate(), "日期格式不正確");
    String note = override.note() == null ? "" : override.note();
    Problem.check(note.length() <= 40, "備註請在 40 字內");
    lockBranch(branchId);
    List<Hours> periods = override.hours();
    if (override.closed()) {
      Problem.check(periods == null || periods.isEmpty(), "公休日不能同時設定營業時段");
      periods = List.of();
    } else {
      Problem.check(periods != null && !periods.isEmpty(), "請至少設定一個營業時段，或改為整天公休");
      validateDayPeriods(periods);
    }
    db.update(
        "delete from branch_day_override_hours where branch_id=? and on_date=?",
        branchId,
        override.onDate());
    long updatedAt = System.currentTimeMillis();
    if (db.update(
            "update branch_day_overrides set closed=?,note=?,updated_at=?,updated_by=?"
                + " where branch_id=? and on_date=?",
            override.closed(),
            note,
            updatedAt,
            actor.id(),
            branchId,
            override.onDate())
        == 0) {
      db.update(
          "insert into branch_day_overrides(branch_id,on_date,closed,note,updated_at,updated_by)"
              + " values(?,?,?,?,?,?)",
          branchId,
          override.onDate(),
          override.closed(),
          note,
          updatedAt,
          actor.id());
    }
    for (Hours period : periods) {
      db.update(
          "insert into branch_day_override_hours(id,branch_id,on_date,open_minute,close_minute)"
              + " values(?,?,?,?,?)",
          Ids.next(),
          branchId,
          override.onDate(),
          period.openMinute(),
          period.closeMinute());
    }
    String summary =
        override.closed()
            ? "設定 " + override.onDate() + " 公休" + (note.isBlank() ? "" : "（" + note + "）")
            : "設定 " + override.onDate() + " 例外時段（" + periods.size() + " 段）";
    // target_id is at most 36 + 1 + 8 = 45 chars (audit_log.target_id is VARCHAR(80)).
    audit.record(
        actor, "BRANCH_HOURS_OVERRIDE_SAVE", branchId + ":" + override.onDate(), branchId, summary);
    return loadOverrides(branchId, override.onDate(), override.onDate()).get(0);
  }

  @Transactional
  public void deleteOverride(Actor actor, String branchId, int onDate) {
    actor.require("BRANCH_MANAGE");
    if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
    parseDate(onDate, "日期格式不正確");
    lockBranch(branchId);
    db.update(
        "delete from branch_day_override_hours where branch_id=? and on_date=?", branchId, onDate);
    if (db.update(
            "delete from branch_day_overrides where branch_id=? and on_date=?", branchId, onDate)
        > 0)
      audit.record(
          actor,
          "BRANCH_HOURS_OVERRIDE_DELETE",
          branchId + ":" + onDate,
          branchId,
          "刪除 " + onDate + " 例外日");
  }

  private void lockBranch(String branchId) {
    if (db.queryForList("select id from branches where id=? for update", String.class, branchId)
        .isEmpty()) throw new Problem(404, "找不到分店");
  }

  private static Resolver resolver(List<Hours> weekly, Map<LocalDate, DayOverride> overrides) {
    boolean weeklyEmpty = weekly.isEmpty();
    return date ->
        resolveDay(
            date,
            weeklyEmpty,
            weekly.stream().filter(h -> h.dayOfWeek() == date.getDayOfWeek().getValue()).toList(),
            overrides.get(date));
  }

  private static Map<LocalDate, DayOverride> indexOverrides(List<DayOverride> list) {
    Map<LocalDate, DayOverride> result = new HashMap<>();
    for (DayOverride override : list) result.put(parseDate(override.onDate(), "日期格式不正確"), override);
    return result;
  }

  private static LocalDate localDate(long atEpochMs) {
    return Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI).toLocalDate();
  }

  private static int dateInt(LocalDate date) {
    return Integer.parseInt(date.format(BASIC_DATE));
  }

  private static LocalDate parseDate(int value, String message) {
    try {
      return LocalDate.parse(String.valueOf(value), BASIC_DATE);
    } catch (DateTimeParseException invalid) {
      throw new Problem(400, message);
    }
  }

  private static void addOverrideRow(Map<Integer, MutableOverride> rows, java.sql.ResultSet result)
      throws java.sql.SQLException {
    int onDate = result.getInt(1);
    MutableOverride row =
        rows.computeIfAbsent(
            onDate,
            ignored ->
                new MutableOverride(onDate, resultBoolean(result, 2), resultString(result, 3)));
    Integer open = (Integer) result.getObject(4);
    if (open != null)
      row.hours.add(
          new Hours(
              parseDate(onDate, "日期格式不正確").getDayOfWeek().getValue(), open, result.getInt(5)));
  }

  private static boolean resultBoolean(java.sql.ResultSet result, int column) {
    try {
      return result.getBoolean(column);
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String resultString(java.sql.ResultSet result, int column) {
    try {
      return result.getString(column);
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static final class MutableOverride {
    private final int onDate;
    private final boolean closed;
    private final String note;
    private final List<Hours> hours = new ArrayList<>();

    private MutableOverride(int onDate, boolean closed, String note) {
      this.onDate = onDate;
      this.closed = closed;
      this.note = note;
    }

    private DayOverride value() {
      return new DayOverride(onDate, closed, note, List.copyOf(hours));
    }
  }

  private static void addRange(
      Map<Integer, List<MinuteRange>> ranges, int day, int start, int end) {
    ranges.computeIfAbsent(day, ignored -> new ArrayList<>()).add(new MinuteRange(start, end));
  }

  private record MinuteRange(int start, int end) {}

  @Transactional
  public Branch save(Actor a, Branch b) {
    a.require("BRANCH_MANAGE");
    if (!a.global()) throw new Problem(403, "此功能限總部範圍");
    Problem.check(
        b.name() != null && !b.name().isBlank() && b.name().length() <= 80, "請填寫分店名稱（80 字內）");
    Problem.check(b.monthlyTarget() >= 0, "目標不能為負數");
    Problem.check(
        b.address() != null
            && b.address().length() <= 200
            && b.phone() != null
            && b.phone().length() <= 30,
        "地址或電話格式錯誤");
    String id = b.id() == null ? Ids.next() : b.id();
    if (b.id() == null)
      db.update(
          "insert into branches(id,name,address,phone,active,monthly_target) values(?,?,?,?,?,?)",
          id,
          b.name(),
          b.address(),
          b.phone(),
          b.active(),
          b.monthlyTarget());
    else if (db.update(
            "update branches set name=?,address=?,phone=?,active=?,monthly_target=? where id=?",
            b.name(),
            b.address(),
            b.phone(),
            b.active(),
            b.monthlyTarget(),
            id)
        == 0) throw new Problem(404, "找不到分店");
    audit.record(a, "BRANCH_SAVE", id, id, "儲存分店 " + b.name());
    return new Branch(id, b.name(), b.address(), b.phone(), b.active(), b.monthlyTarget());
  }
}
