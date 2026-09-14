import java.io.File;
import java.util.*;

class SortingTrafficTest {
  static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

  static void validate(SortingTraffic traffic, SortingTraffic.Plan plan, List<SortingTraffic.Goal> goals) {
    int goal = 0;
    for (int i = 1; i < plan.steps().size(); i++) {
      var previous = plan.steps().get(i - 1); var step = plan.steps().get(i);
      if (!previous.point().equals(step.point())) {
        require(step.operation().equals("MOVE"), "Operation executed while moving");
        require(traffic.graph.get(previous.point()).stream().anyMatch(e -> e.to().equals(step.point())), "Illegal directed edge");
      }
      if (goal < goals.size() && step.point().equals(goals.get(goal).point()) && step.operation().equals(goals.get(goal).operation())) goal++;
    }
    require(goal == goals.size(), "Skipped/reordered operation: " + goal + "/" + goals.size());
  }

  static void checkFrame(Collection<SortingTraffic.Plan> plans, long tick) {
    var current = new HashMap<String, Integer>(); var next = new HashMap<String, Integer>();
    for (var plan : plans) {
      require(current.put(plan.at(tick), plan.vehicle()) == null, "Current vertex conflict");
      require(next.put(plan.at(tick + 1), plan.vehicle()) == null, "Next vertex conflict");
    }
    for (var plan : plans) {
      Integer occupant = current.get(plan.at(tick + 1));
      require(occupant == null || occupant == plan.vehicle(), "Entered occupied point");
    }
  }

  static void metrics() {
    var stats = new SortingMetrics(); stats.startMillis = 1_000_000;
    stats.record(stats.startMillis - 1, 1, false);
    stats.record(stats.startMillis, 1, false);
    stats.record(stats.startMillis + SortingMetrics.HOUR_MS - 1, 2, false);
    stats.record(stats.startMillis + SortingMetrics.HOUR_MS, 3, false);
    stats.record(stats.startMillis + 5, 1, true);
    require(Arrays.stream(stats.hours.get(0L), 0, 24).sum() == 2, "Wrong first-hour boundary");
    require(stats.hours.get(1L)[2] == 1 && stats.hours.get(0L)[24] == 1, "Charge included as sort");
    var props = new Properties(); stats.save(props); var restored = SortingMetrics.load(props);
    require(restored.csv(stats.startMillis + 100).equals(stats.csv(stats.startMillis + 100)), "Counters changed after checkpoint");
    require(stats.csv(stats.startMillis + SortingMetrics.HOUR_MS).contains("FINALIZING"), "Unacknowledged hour reported complete");
    stats.confirmedThroughMillis = stats.startMillis + SortingMetrics.HOUR_MS;
    require(stats.csv(stats.confirmedThroughMillis).contains("COMPLETE,3600.000,2,1,2.00"), "Full-hour result not exact");
    require(stats.summary(stats.startMillis + 100).contains("INCOMPLETE"), "Partial hour reported as actual hour");
  }

  public static void main(String[] args) throws Exception {
    long started = System.nanoTime(); var traffic = new SortingTraffic(new File("SortingDemo/Sorting.xml"));
    require(!SortingTraffic.compatible("A", "B", Set.of("A"), Map.of("B", "A")), "Swap allowed");
    require(!SortingTraffic.compatible("A", "B", Set.of("C", "D", "A"), Map.of("B", "C", "C", "D", "D", "A")), "Circular wait allowed");
    for (int vehicle = 1; vehicle <= 24; vehicle++) for (int cycle = 1; cycle <= traffic.dropoffs.size(); cycle++) {
      var goals = new ArrayList<>(traffic.goals(vehicle, cycle));
      goals.set(goals.size() - 1, new SortingTraffic.Goal(traffic.dropoffs.get(cycle - 1), "UNLOAD"));
      goals.add(new SortingTraffic.Goal("init_" + vehicle, "CHARGE"));
      var p = traffic.planGoals(vehicle, cycle, "init_" + vehicle, goals, 0, List.of()); require(p != null, "Unreachable route"); validate(traffic, p, goals);
    }
    var restored = new SortingTraffic(new File("SortingDemo/Sorting.xml"), traffic.randomSeed);
    var different = new SortingTraffic(new File("SortingDemo/Sorting.xml"), traffic.randomSeed + 1);
    int repeats = 0, changes = 0;
    var counts = new HashMap<String, Integer>();
    for (int vehicle = 1; vehicle <= 24; vehicle++) {
      String previous = null;
      for (int cycle = 1; cycle <= 1000; cycle++) {
        String target = traffic.destination(vehicle, cycle);
        require(traffic.dropoffs.contains(target), "Unknown random destination");
        require(target.equals(restored.destination(vehicle, cycle)), "Restart changed destination");
        require(target.equals(traffic.destination(vehicle, cycle)), "Retry redrew destination");
        if (target.equals(previous)) repeats++;
        if (!target.equals(different.destination(vehicle, cycle))) changes++;
        counts.merge(target, 1, Integer::sum); previous = target;
      }
    }
    require(counts.size() == traffic.dropoffs.size(), "Random sample missed a destination");
    require(repeats > 0 && changes > 0, "Selection still follows a fixed rotation");
    double expected = 24000.0 / traffic.dropoffs.size();
    require(counts.values().stream().allMatch(n -> n > expected * .5 && n < expected * 1.5), "Strong destination bias");
    System.out.println("PASS: 24 x " + traffic.dropoffs.size() + " directed routes and queue order");
    System.out.println("PASS: 24000 random destinations; all targets sampled; retries/restarts stable; new seed changes results; consecutive repeats allowed");
    if (Arrays.asList(args).contains("--routes-only")) { metrics(); return; }
    var plans = new TreeMap<Integer, SortingTraffic.Plan>();
    int[] sorted = new int[25], charges = new int[25]; double[] energy = new double[25];
    boolean[] loaded = new boolean[25], toCharge = new boolean[25]; long[] chargeUntil = new long[25];
    Arrays.fill(energy, 100); energy[1] = 20; energy[2] = 20.05; energy[3] = 19;
    int peak = 0;
    for (long tick = 0; tick < 4200; tick++) {
      for (int i = 1; i <= 24; i++) {
        final int number = i;
        if (chargeUntil[i] > 0 && tick >= chargeUntil[i]) { energy[i] = 100; chargeUntil[i] = 0; }
        if (plans.containsKey(i) && energy[i] <= 20 + 1e-8 && !loaded[i] && !toCharge[i]) {
          var old = plans.get(i);
          var others = plans.values().stream().filter(p -> p.vehicle() != number).toList();
          var p = traffic.chargePlan(i, sorted[i] + 1, old.at(tick), tick, others);
          require(p != null, "No charge escape at tick=" + tick + " vehicle=" + i);
          plans.put(i, p); toCharge[i] = true;
        }
        if (!plans.containsKey(i) && chargeUntil[i] == 0) {
          var goals = traffic.batteryGoals(i, sorted[i] + 1, "init_" + i, energy[i], .05, 20);
          var p = traffic.batteryPlan(i, sorted[i] + 1, "init_" + i, energy[i], .05, 20, tick, plans.values());
          if (p == null) continue; // Like the dispatcher, retry admission after other vehicles progress.
          validate(traffic, p, goals); plans.put(i, p);
          if (energy[i] > 99) {
            require(p.steps().stream().filter(s -> s.operation().equals("UNLOAD")).count() > 1, "Return after each parcel");
            require(p.steps().stream().filter(s -> s.operation().equals("CHARGE")).count() == 1, "Premature charge visits");
          }
        }
      }
      checkFrame(plans.values(), tick); peak = Math.max(peak, plans.size());
      for (var plan : plans.values()) {
        int i = plan.vehicle(); var action = plan.action(tick + 1);
        switch (action.operation()) {
          case "MOVE" -> {
            long length = traffic.graph.get(plan.at(tick)).stream().filter(e -> e.to().equals(action.point()))
                .mapToLong(SortingTraffic.Edge::length).min().orElseThrow();
            energy[i] = Math.round((energy[i] - length / 1000.0 * .05) * 1e6) / 1e6;
          }
          case "LOAD" -> { require(energy[i] > 20, "Loaded at critical battery"); loaded[i] = true; }
          case "UNLOAD" -> { require(loaded[i], "Unloaded without cargo"); loaded[i] = false; sorted[i]++; }
          case "CHARGE" -> {
            require(energy[i] <= 20 + 1e-8 && !loaded[i], "Charged early or with cargo");
            charges[i]++; toCharge[i] = false; chargeUntil[i] = tick + (long) Math.ceil(100 - energy[i]);
          }
        }
        require(energy[i] > 0, "Battery exhausted before charger");
      }
      long nextTick = tick + 1;
      for (var p : plans.values().stream().filter(p -> p.end() == nextTick).toList()) plans.remove(p.vehicle());
    }
    require(Arrays.stream(sorted, 1, 25).min().orElse(0) >= 30, "Starved vehicle");
    require(Arrays.stream(charges, 1, 25).min().orElse(0) >= 1, "Charge branch not covered");
    metrics();
    System.out.println("PASS: 24 x " + traffic.dropoffs.size() + " directed routes, queue order and conflict checks");
    System.out.println("PASS: 4200 frames; sorts=" + Arrays.stream(sorted).sum() + "; min/vehicle=" + Arrays.stream(sorted, 1, 25).min().orElse(0)
        + "; charge visits=" + Arrays.stream(charges).sum() + "; peak active=" + peak);
    System.out.println("PASS: 19%, 20%, 20.05% thresholds; cargo completed before charge; charging and sorting coexist");
    System.out.println("PASS: exact hourly boundaries, unload-only counts, persisted counters, partial/full-hour distinction");
    System.out.printf("Test duration: %.1fs%n", (System.nanoTime() - started) / 1e9);
  }
}
