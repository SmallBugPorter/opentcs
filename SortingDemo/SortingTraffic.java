import java.io.File;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Complete-trip space/time planning. No movement dependency cycles are permitted. */
final class SortingTraffic {
  static final String VERSION = "battery-sorting-v5-random";
  static final int HORIZON = 4096;
  record Edge(String to, long length) {}
  record Step(String point, String operation) {}
  record Goal(String point, String operation) {}
  record Plan(int vehicle, int cycle, long start, List<Step> steps) {
    long end() { return start + steps.size() - 1; }
    String at(long tick) {
      int index = (int) Math.max(0, Math.min(steps.size() - 1, tick - start));
      return steps.get(index).point();
    }
    Step action(long tick) { return steps.get((int) (tick - start)); }
  }
  record Key(String point, long tick, int goal) {}
  record Search(Key key, long distance, long estimate, Search previous, String operation) {}

  final List<String> dropoffs = new ArrayList<>();
  final long randomSeed;

  final Map<String, List<Edge>> graph = new TreeMap<>();
  final Map<String, String> homes = new TreeMap<>();
  final Map<String, Map<String, Integer>> distances = new HashMap<>();

  SortingTraffic(File file) throws Exception { this(file, 20260914L); }

  SortingTraffic(File file, long randomSeed) throws Exception {
    this.randomSeed = randomSeed;
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var document = factory.newDocumentBuilder().parse(file);
    var points = document.getElementsByTagName("point");
    for (int i = 0; i < points.getLength(); i++) {
      var node = (Element) points.item(i);
      graph.put(node.getAttribute("name"), new ArrayList<>());
    }
    var locations = document.getElementsByTagName("location");
    for (int i = 0; i < locations.getLength(); i++) {
      var location = (Element) locations.item(i);
      if (!location.getAttribute("type").equals("Dropoff")) continue;
      var links = location.getElementsByTagName("link");
      for (int j = 0; j < links.getLength(); j++) {
        String point = ((Element) links.item(j)).getAttribute("point");
        if (!dropoffs.contains(point)) dropoffs.add(point);
      }
    }
    dropoffs.sort(Comparator.comparingInt((String point) -> Integer.parseInt(point.substring(point.lastIndexOf('_') + 1)))
        .thenComparing(Comparator.naturalOrder()));
    if (dropoffs.isEmpty()) throw new IllegalArgumentException("No dropoff locations");
    var paths = document.getElementsByTagName("path");
    for (int i = 0; i < paths.getLength(); i++) {
      var path = (Element) paths.item(i);
      if (Boolean.parseBoolean(path.getAttribute("locked"))) continue;
      String a = path.getAttribute("sourcePoint"), b = path.getAttribute("destinationPoint");
      long length = Long.parseLong(path.getAttribute("length"));
      if (Integer.parseInt(path.getAttribute("maxVelocity")) > 0) graph.get(a).add(new Edge(b, length));
      if (Integer.parseInt(path.getAttribute("maxReverseVelocity")) > 0) graph.get(b).add(new Edge(a, length));
    }
    for (int i = 1; i <= SortingSimulation.VEHICLES; i++) {
      if (!graph.containsKey("init_" + i)) throw new IllegalArgumentException("Missing init_" + i);
      homes.put(name(i), "init_" + i);
    }
    for (var edges : graph.values()) edges.sort(Comparator.comparing(Edge::to));
  }

  static String name(int vehicle) { return String.format("Sorting-AGV-%02d", vehicle); }
  List<Goal> goals(int vehicle, int cycle) {
    int pickup = (vehicle - 1) % 3 + 1;
    var goals = new ArrayList<Goal>();
    for (int slot = 4; slot >= 1; slot--) {
      goals.add(new Goal("waite_" + ((pickup - 1) * 4 + slot), "MOVE"));
    }
    goals.add(new Goal("pickup_" + pickup, "LOAD"));
    goals.add(new Goal(destination(vehicle, cycle), "UNLOAD"));
    return goals;
  }

  String destination(int vehicle, int cycle) {
    // Stable per parcel: planning retries and restart must not redraw its destination.
    long key = ((long) vehicle << 32) | Integer.toUnsignedLong(cycle);
    long mixed = key + 0x9e3779b97f4a7c15L;
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
    mixed ^= mixed >>> 31;
    return dropoffs.get(new SplittableRandom(randomSeed ^ mixed).nextInt(dropoffs.size()));
  }

  Map<String, Integer> distanceTo(String target) {
    return distances.computeIfAbsent(target, destination -> {
      var reverse = new HashMap<String, List<String>>();
      for (String p : graph.keySet()) reverse.put(p, new ArrayList<>());
      graph.forEach((a, edges) -> edges.forEach(e -> reverse.get(e.to()).add(a)));
      var result = new HashMap<String, Integer>();
      var queue = new ArrayDeque<String>();
      queue.add(destination); result.put(destination, 0);
      while (!queue.isEmpty()) {
        String p = queue.remove();
        for (String previous : reverse.get(p)) {
          if (result.putIfAbsent(previous, result.get(p) + 1) == null) queue.add(previous);
        }
      }
      return result;
    });
  }

  int normalize(String point, int goal, List<Goal> goals) {
    while (goal < goals.size() && goals.get(goal).operation().equals("MOVE")
        && goals.get(goal).point().equals(point)) goal++;
    return goal;
  }

  long[] remaining(List<Goal> goals) {
    long[] result = new long[goals.size()];
    for (int i = goals.size() - 1; i >= 0; i--) {
      result[i] = goals.get(i).operation().equals("MOVE") ? 0 : 1;
      if (i + 1 < goals.size()) result[i] += result[i + 1]
          + distanceTo(goals.get(i + 1).point()).getOrDefault(goals.get(i).point(), 100000);
    }
    return result;
  }

  long estimate(String point, int goal, List<Goal> goals, long[] remaining) {
    return goal == goals.size() ? 0 : remaining[goal]
        + distanceTo(goals.get(goal).point()).getOrDefault(point, 100000);
  }

  List<Goal> batteryGoals(int vehicle, int cycle, String source, double energy,
                          double consumption, double threshold) {
    var result = new ArrayList<Goal>();
    String point = source;
    // Plan through the next low-battery return, not a charger visit after every parcel.
    for (int parcel = 0; energy > threshold + 1e-8 && parcel < 100; parcel++) {
      for (var goal : goals(vehicle, cycle + parcel)) {
        energy -= distanceTo(goal.point()).getOrDefault(point, 100000) * consumption;
        result.add(goal); point = goal.point();
      }
    }
    result.add(new Goal("init_" + vehicle, "CHARGE"));
    return result;
  }

  Plan batteryPlan(int vehicle, int cycle, String source, double energy, double consumption,
                   double threshold, long now, Collection<Plan> existing) {
    var goals = batteryGoals(vehicle, cycle, source, energy, consumption, threshold);
    var pieces = new ArrayList<Plan>();
    long[] earliest = new long[goals.size()];
    int index = 0, backtracks = 0;
    while (index < goals.size()) {
      String from = index == 0 ? source : pieces.get(index - 1).steps().getLast().point();
      long start = index == 0 ? now : pieces.get(index - 1).end();
      var piece = planGoals(vehicle, cycle, from, List.of(goals.get(index)), start, existing, earliest[index]);
      if (piece == null) {
        if (index == 0 || ++backtracks > 100) return null;
        index--;
        earliest[index] = pieces.get(index).end() + 1;
        while (pieces.size() > index) pieces.removeLast();
        for (int i = index + 1; i < earliest.length; i++) earliest[i] = 0;
      }
      else { pieces.add(piece); index++; }
    }
    var steps = new ArrayList<Step>();
    steps.add(new Step(source, "WAIT"));
    for (var piece : pieces) steps.addAll(piece.steps().subList(1, piece.steps().size()));
    return new Plan(vehicle, cycle, now, List.copyOf(steps));
  }

  Plan chargePlan(int vehicle, int cycle, String source, long now, Collection<Plan> existing) {
    return planGoals(vehicle, cycle, source, List.of(new Goal("init_" + vehicle, "CHARGE")), now, existing);
  }

  /** Vertex conflicts, swaps AND longer circular movement dependencies are forbidden. */
  static boolean compatible(String from, String to, Set<String> occupiedNext,
                            Map<String, String> otherMoves) {
    if (occupiedNext.contains(to)) return false;
    if (from.equals(to)) return true;
    // Leave one frame for the kernel to physically release a point. This also
    // avoids long same-frame convoys serializing behind one another.
    if (otherMoves.containsKey(to) || occupiedNext.contains(from)) return false;
    String cursor = to;
    var visited = new HashSet<String>();
    while (visited.add(cursor) && otherMoves.containsKey(cursor)) {
      cursor = otherMoves.get(cursor);
      if (cursor.equals(from)) return false;
    }
    return true;
  }

  Plan plan(int vehicle, int cycle, long now, Collection<Plan> existing) {
    String home = "init_" + vehicle;
    var goals = new ArrayList<>(goals(vehicle, cycle));
    goals.add(new Goal(home, "CHARGE"));
    return planGoals(vehicle, cycle, home, goals, now, existing);
  }

  Plan planGoals(int vehicle, int cycle, String source, List<Goal> goals, long now,
                 Collection<Plan> existing) {
    return planGoals(vehicle, cycle, source, goals, now, existing, 0);
  }

  Plan planGoals(int vehicle, int cycle, String source, List<Goal> goals, long now,
                 Collection<Plan> existing, long earliestFinish) {
    String home = "init_" + vehicle;
    var remaining = remaining(goals);
    long horizon = Math.min(HORIZON, Math.max(320, Math.max(earliestFinish - now + 64, estimate(source, 0, goals, remaining) + 320)));
    var occupied = new HashMap<Long, Set<String>>();
    var moves = new HashMap<Long, Map<String, String>>();
    for (long t = now + 1; t <= now + horizon; t++) {
      var positions = new HashSet<String>();
      var moving = new HashMap<String, String>();
      for (var plan : existing) {
        positions.add(plan.at(t));
        String from = plan.at(t - 1), to = plan.at(t);
        if (!from.equals(to)) moving.put(from, to);
      }
      occupied.put(t, positions); moves.put(t, moving);
    }
    var best = new HashMap<Key, Long>();
    var queue = new PriorityQueue<Search>(Comparator.comparingLong(Search::estimate)
        .thenComparingLong(Search::distance).thenComparingLong(s -> -s.key().tick())
        .thenComparing(s -> s.key().point()));
    var start = new Key(source, now, 0);
    queue.add(new Search(start, 0, now + estimate(source, 0, goals, remaining), null, "WAIT"));
    best.put(start, 0L);
    int expanded = 0;
    while (!queue.isEmpty()) {
      var current = queue.remove();
      var key = current.key();
      if (current.distance() != best.get(key)) continue;
      if (key.goal() == goals.size() && key.tick() >= earliestFinish
          && (key.point().equals(home) || !occupied.getOrDefault(key.tick() + 1, Set.of()).contains(key.point()))) {
        var result = new ArrayList<Step>();
        for (Search s = current; s != null; s = s.previous()) {
          result.add(new Step(s.key().point(), s.operation()));
        }
        Collections.reverse(result);
        return new Plan(vehicle, cycle, now, List.copyOf(result));
      }
      // Bound a failed admission attempt; existing complete plans always continue to run.
      if (++expanded > 400000 || key.tick() >= now + horizon) return null;
      long tick = key.tick() + 1;
      if (key.goal() == goals.size()) {
        push(current, key.point(), tick, key.goal(), "WAIT", 0,
             goals, remaining, best, queue, occupied.get(tick), moves.get(tick));
        continue;
      }
      var goal = goals.get(key.goal());
      if (key.point().equals(goal.point()) && !goal.operation().equals("MOVE")) {
        push(current, key.point(), tick, key.goal() + 1, goal.operation(), 0,
             goals, remaining, best, queue, occupied.get(tick), moves.get(tick));
      }
      else {
        for (var edge : graph.get(key.point())) {
          if (edge.to().startsWith("init_") && !edge.to().equals(home)) continue;
          push(current, edge.to(), tick, key.goal(), "MOVE", edge.length(),
               goals, remaining, best, queue, occupied.get(tick), moves.get(tick));
        }
        push(current, key.point(), tick, key.goal(), "WAIT", 0,
             goals, remaining, best, queue, occupied.get(tick), moves.get(tick));
      }
    }
    return null;
  }

  void push(Search current, String point, long tick, int goal, String operation, long distance,
            List<Goal> goals, long[] remaining, Map<Key, Long> best, PriorityQueue<Search> queue,
            Set<String> occupied, Map<String, String> moves) {
    if (!compatible(current.key().point(), point, occupied, moves)) return;
    int nextGoal = normalize(point, goal, goals);
    var key = new Key(point, tick, nextGoal);
    long travelled = current.distance() + distance;
    if (travelled >= best.getOrDefault(key, Long.MAX_VALUE)) return;
    best.put(key, travelled);
    queue.add(new Search(key, travelled, tick + estimate(point, nextGoal, goals, remaining), current, operation));
  }
}
