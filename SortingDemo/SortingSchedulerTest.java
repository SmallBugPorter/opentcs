import java.io.File;
import java.util.*;
import org.opentcs.data.order.*;
import org.opentcs.data.model.*;

class SortingSchedulerTest {
  static void require(boolean b, String m) { if (!b) throw new AssertionError(m); }
  public static void main(String[] args) throws Exception {
    var a = new Point("a"); var b = new Point("b"); var c = new Point("c");
    var ab = new org.opentcs.data.model.Path("ab", a.getReference(), b.getReference()).withLength(1000);
    var bc = new org.opentcs.data.model.Path("bc", b.getReference(), c.getReference()).withLength(3000);
    var route = new Route(List.of(new Route.Step(ab, a, b, Vehicle.Orientation.FORWARD, 0, 1),
        new Route.Step(bc, b, c, Vehicle.Orientation.FORWARD, 1, 1)));
    var drive = new DriveOrder("drive", new DriveOrder.Destination(c.getReference())).withRoute(route);
    var order = new TransportOrder("order", List.of(drive)).withCurrentDriveOrderIndex(0).withCurrentRouteStepIndex(-1);
    require(SortingSimulation.travelled(order) == 0, "Untravelled route billed");
    require(SortingSimulation.travelled(order.withCurrentRouteStepIndex(0)) == 1000, "Intermediate step distance incorrect");
    require(SortingSimulation.travelled(order.withState(TransportOrder.State.FINISHED)) == 4000, "Final route distance incorrect");
    var own = new Vehicle("own").withCurrentPosition(a.getReference())
        .withAllocatedResources(List.of(Set.of(b.getReference())));
    var other = new Vehicle("other").withCurrentPosition(b.getReference());
    require(!SortingSimulation.avoidance(own, List.of(own, other), order).contains("b"),
        "Rerouting excluded an allocated future source point");
    var s = new SortingSimulation.State(); s.fingerprint = SortingSimulation.fingerprint();
    var t = new SortingTraffic(new File("SortingDemo/Sorting.xml"), s.randomSeed);
    s.metrics.startMillis = 1000;
    SortingSimulation.prepare(s, 1, t);
    require(s.stage[1].equals("PICKUP"), "Must load first");
    require(SortingSimulation.destinations(s, 1).size() == 5, "Queue waypoints + load");
    String target = s.target[1], name = s.active[1];
    s.submitted[1] = true;
    var recovered = SortingSimulation.decode(SortingSimulation.encode(s), s.fingerprint);
    require(recovered.submitted[1] && recovered.active[1].equals(name) && recovered.target[1].equals(target), "Intent not persistent");
    SortingSimulation.account(s, 1, 3000); SortingSimulation.account(s, 1, 3000);
    require(s.energy[1] == 99.85, "Distance charged twice");
    SortingSimulation.finish(s, 1, 2000);
    require(s.loaded[1] && s.completed[1] == 0, "Pickup counted as sort");
    s.energy[1] = 19;
    SortingSimulation.prepare(s, 1, t);
    require(s.stage[1].equals("DELIVERY") && s.target[1].equals(target), "Cargo must finish before charge");
    require(SortingSimulation.destinations(s, 1).size() == 1, "Delivery must be one complete destination");
    SortingSimulation.finish(s, 1, 3000);
    require(s.completed[1] == 1 && !s.loaded[1], "Missing unload accounting");
    SortingSimulation.prepare(s, 1, t);
    require(s.stage[1].equals("CHARGE"), "Low battery not charging");
    SortingSimulation.finish(s, 1, 4000);
    require(s.completed[1] == 1 && s.charges[1] == 1 && s.charging[1], "Charging counted as sort");
    s.charging[1] = false; s.energy[1] = 100;
    SortingSimulation.prepare(s, 1, t);
    require(s.stage[1].equals("PICKUP"), "Cannot resume after charge");
    SortingSimulation.prepare(s, 2, t);
    require(s.active[1] != null && s.active[2] != null, "Vehicles must have independent orders");
    recovered = SortingSimulation.decode(SortingSimulation.encode(s), s.fingerprint);
    require(recovered.metrics.csv(5000).equals(s.metrics.csv(5000)), "Lost metrics on restart");
    require(recovered.sequence[1] == s.sequence[1] && recovered.randomSeed == s.randomSeed, "Lost sequence or seed");
    var old = SortingSimulation.encode(s); old.setProperty("version", "battery-sorting-v5-random");
    try { SortingSimulation.decode(old, s.fingerprint); throw new AssertionError("Old checkpoint accepted"); }
    catch (IllegalStateException expected) { }
    System.out.println("PASS: native Scheduler order granularity; independent vehicles; intent/seed/counters persistence; cargo-before-charge; distance idempotency; migration guard");
  }
}
