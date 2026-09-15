import java.time.Instant;
import java.util.*;

/** Exact, restart-safe hourly counts. Only confirmed unloads count as sorts. */
final class SortingMetrics {
  static final int VEHICLES = SortingSimulation.VEHICLES;
  static final long HOUR_MS = 3_600_000;
  long startMillis;
  long confirmedThroughMillis;
  final TreeMap<Long, long[]> hours = new TreeMap<>(); // Per-vehicle counts followed by a separate charge counter.

  void record(long finishedMillis, int vehicle, boolean charge) {
    if (startMillis == 0 || finishedMillis < startMillis) return;
    var counts = hours.computeIfAbsent((finishedMillis - startMillis) / HOUR_MS, key -> new long[VEHICLES + 1]);
    counts[charge ? VEHICLES : vehicle - 1]++;
  }

  void save(Properties properties) {
    properties.setProperty("stats.startMillis", Long.toString(startMillis));
    properties.setProperty("stats.confirmedThroughMillis", Long.toString(confirmedThroughMillis));
    hours.forEach((hour, counts) -> properties.setProperty("stats.hour." + hour,
        String.join(",", Arrays.stream(counts).mapToObj(Long::toString).toList())));
  }

  static SortingMetrics load(Properties properties) {
    var result = new SortingMetrics();
    result.startMillis = Long.parseLong(properties.getProperty("stats.startMillis", "0"));
    result.confirmedThroughMillis = Long.parseLong(properties.getProperty("stats.confirmedThroughMillis", "0"));
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith("stats.hour.")) {
        var counts = Arrays.stream(properties.getProperty(name).split(",")).mapToLong(Long::parseLong).toArray();
        if (counts.length != VEHICLES + 1) throw new IllegalArgumentException("Invalid hourly counter");
        result.hours.put(Long.parseLong(name.substring("stats.hour.".length())), counts);
      }
    }
    return result;
  }

  String csv(long now) {
    var result = new StringBuilder("hour,start_utc,end_utc,status,elapsed_seconds,completed_sorts,charge_visits,estimated_sorts_per_hour");
    for (int i = 1; i <= VEHICLES; i++) result.append(",vehicle_").append(i);
    result.append('\n');
    if (startMillis == 0) return result.toString();
    long last = Math.max(0, (now - startMillis) / HOUR_MS);
    for (long hour = 0; hour <= last; hour++) {
      long start = startMillis + hour * HOUR_MS, end = start + HOUR_MS;
      long elapsed = Math.max(0, Math.min(HOUR_MS, now - start));
      var counts = hours.getOrDefault(hour, new long[VEHICLES + 1]);
      long sorts = Arrays.stream(counts, 0, VEHICLES).sum();
      result.append(hour + 1).append(',').append(Instant.ofEpochMilli(start)).append(',')
          .append(Instant.ofEpochMilli(end)).append(',').append(confirmedThroughMillis >= end ? "COMPLETE" : now >= end ? "FINALIZING" : "IN_PROGRESS")
          .append(',').append(String.format(Locale.ROOT, "%.3f", elapsed / 1000.0))
          .append(',').append(sorts).append(',').append(counts[VEHICLES]).append(',')
          .append(String.format(Locale.ROOT, "%.2f", elapsed == 0 ? 0 : sorts * (double) HOUR_MS / elapsed));
      for (int i = 0; i < VEHICLES; i++) result.append(',').append(counts[i]);
      result.append('\n');
    }
    return result.toString();
  }

  String summary(long now) {
    if (startMillis == 0) return "Hourly measurement has not started.";
    long elapsed = Math.max(0, now - startMillis);
    long total = hours.values().stream().mapToLong(c -> Arrays.stream(c, 0, VEHICLES).sum()).sum();
    long firstHour = Arrays.stream(hours.getOrDefault(0L, new long[VEHICLES + 1]), 0, VEHICLES).sum();
    return "Measurement start=" + Instant.ofEpochMilli(startMillis)
        + " elapsed=" + String.format(Locale.ROOT, "%.1f", elapsed / 60000.0) + "min confirmed unloads=" + total
        + " first_hour=" + firstHour + (elapsed < HOUR_MS ? " (INCOMPLETE; not a full-hour result)" : confirmedThroughMillis < startMillis + HOUR_MS ? " (FINALIZING)" : " (COMPLETE)")
        + " estimated_per_hour=" + String.format(Locale.ROOT, "%.1f", elapsed == 0 ? 0 : total * (double) HOUR_MS / elapsed);
  }
}
