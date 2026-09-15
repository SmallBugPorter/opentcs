import java.nio.file.Path;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.opentcs.data.model.Vehicle;

/** Selects a pickup without limiting admission or issuing parking tasks. */
final class SortingPickup {
  record Edge(String to, double meters, double seconds) {}
  record Cost(String point, double meters, double seconds) {}
  record Choice(int pickup, int queued, double meters, double travelSeconds, double score) {}
  final Map<String,List<Edge>> graph = new HashMap<>();
  final Map<String,Map<String,Cost>> costs = new HashMap<>();
  final double queueSeconds;

  SortingPickup(Path model, Properties config) throws Exception {
    queueSeconds = Double.parseDouble(config.getProperty("pickup.queuePenaltySeconds", "8"));
    if (!Double.isFinite(queueSeconds) || queueSeconds < 0) throw new IllegalArgumentException("Invalid pickup queue penalty");
    var f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var root = f.newDocumentBuilder().parse(model.toFile()).getDocumentElement();
    var vehicle = (Element) root.getElementsByTagName("vehicle").item(0);
    double forward = Double.parseDouble(vehicle.getAttribute("maxVelocity"));
    double reverse = Double.parseDouble(vehicle.getAttribute("maxReverseVelocity"));
    double acceleration = 1000, deceleration = 1000;
    double simulatorSpeed = Double.parseDouble(config.getProperty("motion.maxSpeed", "1.8")) * 1000;
    if (!Double.isFinite(simulatorSpeed) || simulatorSpeed <= 0) throw new IllegalArgumentException("Invalid simulator speed");
    forward = Math.min(forward, simulatorSpeed); reverse = Math.min(reverse, simulatorSpeed);
    var paths = root.getElementsByTagName("path");
    for (int i = 0; i < paths.getLength(); i++) {
      var p = (Element) paths.item(i);
      if (Boolean.parseBoolean(p.getAttribute("locked"))) continue;
      String a = p.getAttribute("sourcePoint"), b = p.getAttribute("destinationPoint");
      double length = Double.parseDouble(p.getAttribute("length"));
      double vf = Math.min(forward, Double.parseDouble(p.getAttribute("maxVelocity")));
      double vr = Math.min(reverse, Double.parseDouble(p.getAttribute("maxReverseVelocity")));
      if (vf > 0) graph.computeIfAbsent(a, k -> new ArrayList<>()).add(new Edge(b, length / 1000, seconds(length, vf, acceleration, deceleration)));
      if (vr > 0) graph.computeIfAbsent(b, k -> new ArrayList<>()).add(new Edge(a, length / 1000, seconds(length, vr, acceleration, deceleration)));
    }
    for (int p = 1; p <= 3; p++) for (String point : zone(p))
      if (!graph.containsKey(point)) throw new IllegalArgumentException("Missing pickup queue route: " + point);
  }

  static double seconds(double distance, double speed, double acceleration, double deceleration) {
    if (distance < 0 || speed <= 0 || acceleration <= 0 || deceleration <= 0) throw new IllegalArgumentException("Invalid motion limits");
    double peak = Math.min(speed, Math.sqrt(2 * distance / (1 / acceleration + 1 / deceleration)));
    return peak / acceleration + peak / deceleration
        + Math.max(0, distance - peak * peak / 2 * (1 / acceleration + 1 / deceleration)) / speed;
  }

  static List<String> zone(int pickup) {
    return List.of("waite_" + pickup * 4, "waite_" + (pickup * 4 - 1), "waite_" + (pickup * 4 - 2),
        "waite_" + (pickup * 4 - 3), "pickup_" + pickup);
  }

  int queued(int pickup, SortingSimulation.State state, Map<Integer,Vehicle> fleet, int self) {
    var ids = new HashSet<Integer>();
    for (int i = 1; i <= SortingSimulation.VEHICLES; i++) {
      if (i == self) continue;
      if (state.active[i] != null && "PICKUP".equals(state.stage[i]) && state.pickup[i] == pickup) ids.add(i);
      var v = fleet.get(i);
      if (v != null && v.getCurrentPosition() != null && zone(pickup).contains(v.getCurrentPosition().getName())) ids.add(i);
    }
    return ids.size();
  }

  Cost route(String from, String to) {
    var distances = costs.computeIfAbsent(from, source -> {
      var best = new HashMap<String,Cost>();
      var queue = new PriorityQueue<Cost>(Comparator.comparingDouble(Cost::seconds).thenComparing(Cost::point));
      var start = new Cost(source, 0, 0); best.put(source, start); queue.add(start);
      while (!queue.isEmpty()) {
        var c = queue.remove();
        if (c.seconds() > best.get(c.point()).seconds()) continue;
        for (var e : graph.getOrDefault(c.point(), List.of())) {
          var next = new Cost(e.to(), c.meters() + e.meters(), c.seconds() + e.seconds());
          if (!best.containsKey(e.to()) || next.seconds() < best.get(e.to()).seconds()) {
            best.put(e.to(), next); queue.add(next);
          }
        }
      }
      return best;
    });
    return distances.get(to);
  }

  Choice choose(int vehicle, SortingSimulation.State state, Map<Integer,Vehicle> fleet) {
    var v = fleet.get(vehicle);
    if (v == null || v.getCurrentPosition() == null) return null;
    Choice selected = null;
    for (int pickup = 1; pickup <= 3; pickup++) {
      String from = v.getCurrentPosition().getName(); double travel = 0, meters = 0; boolean reachable = true;
      for (String to : zone(pickup)) {
        var c = route(from, to);
        if (c == null) { reachable = false; break; }
        travel += c.seconds(); meters += c.meters(); from = to;
      }
      if (!reachable) continue;
      int queued = queued(pickup, state, fleet, vehicle);
      double score = travel + queued * queueSeconds;
      if (selected == null || score < selected.score()) selected = new Choice(pickup, queued, meters, travel, score);
    }
    return selected;
  }
}
