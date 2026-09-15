import java.util.*;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Vehicle;

class SortingPickupTest {
  static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static Vehicle vehicle(int i, String point) {
    return new Vehicle(SortingTraffic.name(i)).withCurrentPosition(new Point(point).getReference());
  }
  static SortingPickup fixture() throws Exception {
    var config = new Properties(); config.setProperty("pickup.queuePenaltySeconds", "8");
    var a = new SortingPickup(SortingSimulation.MODEL, config); a.graph.clear();
    for (int p = 1; p <= 3; p++) {
      String from = "home";
      for (String to : SortingPickup.zone(p)) {
        a.graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new SortingPickup.Edge(to, p, p)); from = to;
      }
    }
    return a;
  }
  public static void main(String[] args) throws Exception {
    var a = fixture(); var s = new SortingSimulation.State(); s.fingerprint = SortingSimulation.fingerprint();
    var fleet = new TreeMap<Integer,Vehicle>(); for (int i = 1; i <= 24; i++) fleet.put(i, vehicle(i, "home"));
    require(Math.abs(SortingPickup.seconds(1000, 1800, 1000, 1000) - 2) < 1e-9, "Acceleration estimate wrong");
    require(a.choose(1, s, fleet).pickup() == 1, "Nearest empty pickup not selected");
    s.active[2] = "queued"; s.stage[2] = "PICKUP"; s.pickup[2] = 1;
    require(a.choose(1, s, fleet).pickup() == 2, "Queue did not outweigh shorter travel");
    fleet.put(2, vehicle(2, "waite_4"));
    require(a.queued(1, s, fleet, 1) == 1, "Same vehicle counted twice");
    require(a.queued(1, s, fleet, 2) == 0, "Vehicle counted itself");
    s.stage[2] = "DELIVERY";
    require(a.queued(1, s, fleet, 1) == 1, "Vehicle still at pickup ignored");
    fleet.put(2, vehicle(2, "home")); s.active[2] = null;
    var traffic = new SortingTraffic(SortingSimulation.MODEL.toFile());
    for (int i = 1; i <= 24; i++) {
      var choice = a.choose(i, s, fleet); require(choice != null, "Unexpected admission limit");
      s.pickup[i] = choice.pickup(); SortingSimulation.prepare(s, i, traffic);
      require("PICKUP".equals(s.stage[i]), "Selection created non-pickup/parking task");
      require(SortingSimulation.destinations(s, i).getLast().getDestLocationName().equals("LOC_pickup_" + choice.pickup()), "Order ignored selected pickup");
    }
    for (int p = 1; p <= 3; p++) require(a.queued(p, s, fleet, 0) > 4, "Old four-vehicle cap still present");
    var restored = SortingSimulation.decode(SortingSimulation.encode(s), s.fingerprint);
    for (int i = 1; i <= 24; i++) require(restored.pickup[i] == s.pickup[i] && restored.active[i].equals(s.active[i]), "Restart changed task choice");
    var legacy = SortingSimulation.encode(s); legacy.remove("pickup.3");
    require(SortingSimulation.decode(legacy, s.fingerprint).pickup[3] == 3, "Legacy fixed task migrated incorrectly");
    var real = new SortingPickup(SortingSimulation.MODEL, SortingSimulation.SETTINGS);
    var starts = new ArrayList<>(traffic.dropoffs); for (int i = 1; i <= 24; i++) starts.add("init_" + i);
    for (String start : starts) for (int p = 1; p <= 3; p++) {
      String from = start;
      for (String to : SortingPickup.zone(p)) {
        require(real.route(from, to) != null, "Unreachable dynamic pickup: " + start + " -> " + to); from = to;
      }
    }
    var invalid = new Properties(); invalid.setProperty("pickup.queuePenaltySeconds", "NaN");
    try { new SortingPickup(SortingSimulation.MODEL, invalid); throw new AssertionError("Invalid weight accepted"); }
    catch (IllegalArgumentException expected) { }
    System.out.println("PASS: travel/queue selection; no admission limit or standby; queue deduplication; persistent and legacy choices; all dynamic pickup routes");
  }
}
