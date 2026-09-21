package com.coffee.branches.internal;

import com.coffee.audit.api.Audit;
import com.coffee.branches.api.Branches;
import com.coffee.shared.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchService implements Branches {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
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
    if (db.queryForObject(
            "select count(*) from branches where id=?", Integer.class, branchId)
        == 0) throw new Problem(404, "找不到分店");
    return loadHours(branchId);
  }

  private List<Hours> loadHours(String branchId) {
    return db.query(
        "select day_of_week,open_minute,close_minute from branch_hours"
            + " where branch_id=? order by day_of_week,open_minute",
        (r, n) -> new Hours(r.getInt(1), r.getInt(2), r.getInt(3)),
        branchId);
  }

  public boolean openAt(String branchId, long atEpochMs) {
    return isOpenAt(hours(branchId), atEpochMs);
  }

  public Branch requireOrderable(String id, long atEpochMs) {
    Branch branch = requireOpen(id);
    List<Hours> schedule = hours(id);
    if (isOpenAt(schedule, atEpochMs)) return branch;
    throw new Problem(400, closedMessage(schedule, atEpochMs));
  }

  public static boolean isOpenAt(List<Hours> schedule, long atEpochMs) {
    if (schedule.isEmpty()) return true;
    var local = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI);
    int today = local.getDayOfWeek().getValue();
    int previous = today == 1 ? 7 : today - 1;
    int minute = local.getHour() * 60 + local.getMinute();
    return schedule.stream()
        .anyMatch(
            period ->
                (period.dayOfWeek() == today
                        && period.closeMinute() > period.openMinute()
                        && period.openMinute() <= minute
                        && minute < period.closeMinute())
                    || (period.dayOfWeek() == today
                        && period.closeMinute() <= period.openMinute()
                        && minute >= period.openMinute())
                    || (period.dayOfWeek() == previous
                        && period.closeMinute() <= period.openMinute()
                        && minute < period.closeMinute()));
  }

  private static String closedMessage(List<Hours> schedule, long atEpochMs) {
    int today = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI).getDayOfWeek().getValue();
    String periods =
        schedule.stream()
            .filter(period -> period.dayOfWeek() == today)
            .map(
                period ->
                    formatMinute(period.openMinute())
                        + "–"
                        + (period.closeMinute() <= period.openMinute() ? "隔日 " : "")
                        + formatMinute(period.closeMinute()))
            .reduce((left, right) -> left + "、" + right)
            .orElse(null);
    return periods == null
        ? "分店今日未營業"
        : "分店目前未營業（今日營業時間 " + periods + "）";
  }

  private static String formatMinute(int minute) {
    if (minute == 1440) return "24:00";
    return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }

  @Transactional
  public List<Hours> saveHours(Actor actor, String branchId, List<Hours> schedule) {
    actor.require("BRANCH_MANAGE");
    if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
    Problem.check(schedule != null, "請提供營業時段");
    if (db.queryForList("select id from branches where id=? for update", String.class, branchId)
        .isEmpty()) throw new Problem(404, "找不到分店");
    validateHours(schedule);
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
        "更新營業時間（" + schedule.size() + " 段）");
    return loadHours(branchId);
  }

  static void validateHours(List<Hours> schedule) {
    Problem.check(schedule.size() <= 28, "每天最多 4 個時段");
    Map<Integer, Integer> counts = new HashMap<>();
    Map<Integer, List<MinuteRange>> ranges = new HashMap<>();
    for (Hours period : schedule) {
      Problem.check(period != null && period.dayOfWeek() >= 1 && period.dayOfWeek() <= 7,
          "星期格式不正確");
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
