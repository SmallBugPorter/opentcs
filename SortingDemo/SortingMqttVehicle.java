// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
// Adapted from v2_0/simulation/VehicleSimulator: configurable sorting fleet, serialized
// MQTT callbacks, released-base execution, actions, battery and restart checkpoints.
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.*;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.*;

/** Single-threaded vehicle state machine. No Kernel API: all commands/states use MQTT. */
class SortingMqttVehicle {
  static final JsonBinder JSON = new JsonBinder();
  static final String VERSION = "2.0.0";
  final String name, manufacturer, serial, topic;
  final Properties settings;
  final BiConsumer<String, String> output;
  final TreeMap<Long, Node> nodes = new TreeMap<>();
  final TreeMap<Long, Edge> edges = new TreeMap<>();
  final LinkedHashMap<String, ActionState> actions = new LinkedHashMap<>();
  State state;
  long header, baseEnd;
  String baseEndNode;
  double motionElapsed, actionElapsed, heading = Double.NaN;
  String runningAction;
  boolean loaded;
  double motionDuration, rotationDuration, motionDistance, startX, startY, targetHeading;
  long motionSequence = -1;

  SortingMqttVehicle(String name, String manufacturer, String serial, String topic,
                     String initialNode, double x, double y, Properties settings,
                     BiConsumer<String, String> output) {
    this.name = name; this.manufacturer = manufacturer; this.serial = serial;
    this.topic = topic; this.settings = settings; this.output = output;
    state = new State("", 0L, initialNode, 0L, new ArrayList<>(), new ArrayList<>(), false,
        new ArrayList<>(), new BatteryState(100.0, false), OperatingMode.AUTOMATIC,
        new ArrayList<>(), new SafetyState(EStop.NONE, false));
    state.setAgvPosition(new AgvPosition(x, y, 0.0, "Sorting", true));
    state.setPaused(false); state.setLoads(new ArrayList<>());
    baseEndNode = initialNode;
  }

  double setting(String key, double fallback) { return Double.parseDouble(settings.getProperty(key, "" + fallback)); }
  void connection(ConnectionState value) {
    output.accept("connection", JSON.toJson(new Connection(header++, Instant.now(), VERSION, manufacturer, serial, value)));
  }
  boolean busy() { return !nodes.isEmpty() || runningAction != null; }
  void receive(String suffix, String payload) {
    if (suffix.equals("order")) order(JSON.fromJson(payload, Order.class));
    else if (suffix.equals("instantActions")) {
      var message = JSON.fromJson(payload, InstantActions.class);
      if (!manufacturer.equals(message.getManufacturer()) || !serial.equals(message.getSerialNumber()))
        throw new IllegalArgumentException("Wrong instant-action identity for " + name);
      for (var action : message.getActions()) instant(action);
    }
    publish();
  }

  void order(Order order) {
    if (!manufacturer.equals(order.getManufacturer()) || !serial.equals(order.getSerialNumber())
        || !VERSION.equals(order.getVersion())) throw new IllegalArgumentException("Wrong order identity/version");
    if (order.getNodes().isEmpty()) throw new IllegalArgumentException("Order has no start node");
    boolean same = state.getOrderId().equals(order.getOrderId());
    if (same && order.getOrderUpdateId() <= state.getOrderUpdateId()) return; // QoS redelivery.
    Node first = order.getNodes().get(0);
    if (!first.isReleased()) throw new IllegalArgumentException("Unreleased start node");
    if (same) {
      if (first.getSequenceId() != baseEnd || !first.getNodeId().equals(baseEndNode))
        throw new IllegalArgumentException("Order update must stitch at previous base end");
    } else if (busy() || !first.getNodeId().equals(state.getLastNodeId())) {
      throw new IllegalArgumentException("Vehicle busy or order starts at wrong node");
    }
    // This sorting simulator deliberately accepts only node actions implemented below.
    for (var node : order.getNodes()) for (var action : node.getActions())
      if (!Set.of("Load cargo", "Unload cargo", "CHARGE", "NOP").contains(action.getActionType()))
        throw new IllegalArgumentException("Unsupported node action: " + action.getActionType());
    for (var edge : order.getEdges()) if (!edge.getActions().isEmpty())
      throw new IllegalArgumentException("Edge actions are not supported by SortingDemo");
    if (!same) {
      nodes.clear(); edges.clear(); actions.clear(); motionSequence = -1; motionElapsed = 0;
      state.setOrderId(order.getOrderId());
      state.setLastNodeSequenceId(first.getSequenceId());
    }
    state.setOrderUpdateId(order.getOrderUpdateId());
    for (var node : order.getNodes()) {
      if (!node.isReleased()) continue; // Never traverse horizon / unallocated resources.
      // An executed stitching node must not execute its actions a second time.
      if (!same || node.getSequenceId() > state.getLastNodeSequenceId() || nodes.containsKey(node.getSequenceId())) {
        nodes.put(node.getSequenceId(), node);
        for (var action : node.getActions()) actions.putIfAbsent(action.getActionId(),
            new ActionState(action.getActionId(), ActionStatus.WAITING).setActionType(action.getActionType()));
      }
      baseEnd = node.getSequenceId(); baseEndNode = node.getNodeId();
    }
    for (var edge : order.getEdges()) if (Boolean.TRUE.equals(edge.isReleased())
        && edge.getSequenceId() > state.getLastNodeSequenceId()) edges.put(edge.getSequenceId(), edge);
    System.out.println(Instant.now() + " ORDER " + name + " id=" + state.getOrderId()
        + " update=" + state.getOrderUpdateId() + " releasedNodes=" + nodes.size());
  }

  void instant(Action action) {
    if (actions.containsKey(action.getActionId())) return;
    var result = new ActionState(action.getActionId(), ActionStatus.FINISHED).setActionType(action.getActionType());
    switch (action.getActionType()) {
      case "stateRequest" -> { }
      case "startPause" -> { state.setPaused(true); state.setDriving(false); }
      case "stopPause" -> state.setPaused(false);
      case "cancelOrder" -> {
        nodes.clear(); edges.clear(); runningAction = null; motionSequence = -1;
        state.setDriving(false);
        actions.values().stream().filter(a -> a.getActionStatus() == ActionStatus.WAITING
            || a.getActionStatus() == ActionStatus.RUNNING).forEach(a -> a.setActionStatus(ActionStatus.FAILED));
      }
      default -> result.setActionStatus(ActionStatus.FAILED);
    }
    actions.put(action.getActionId(), result);
  }

  // Rest-to-rest triangular/trapezoidal profile, shared by translation and rotation.
  static double duration(double distance, double speed, double acceleration, double deceleration) {
    double peak = Math.min(speed, Math.sqrt(2 * distance / (1 / acceleration + 1 / deceleration)));
    return peak / acceleration + peak / deceleration
        + Math.max(0, distance - peak * peak / (2 * acceleration) - peak * peak / (2 * deceleration)) / peak;
  }
  static double travelled(double time, double distance, double speed, double acceleration, double deceleration) {
    if (distance <= 0) return 0;
    double peak = Math.min(speed, Math.sqrt(2 * distance / (1 / acceleration + 1 / deceleration)));
    double up = peak / acceleration, down = peak / deceleration;
    double cruise = Math.max(0, (distance - peak * up / 2 - peak * down / 2) / peak);
    if (time < up) return acceleration * time * time / 2;
    if (time < up + cruise) return peak * up / 2 + peak * (time - up);
    double remaining = Math.max(0, up + cruise + down - time);
    return distance - deceleration * remaining * remaining / 2;
  }
  double speed() {
    Edge edge = edges.get(motionSequence - 1);
    return Math.max(0.01, Math.min(setting("motion.maxSpeed", 1.8), edge != null && edge.getMaxSpeed() != null
        ? edge.getMaxSpeed() : 1.8));
  }
  void tick(double seconds) {
    if (Boolean.TRUE.equals(state.isPaused())) return;
    var battery = state.getBatteryState();
    if (battery.isCharging()) {
      battery.setBatteryCharge(Math.min(100, battery.getBatteryCharge() + seconds * setting("battery.chargePercentPerSecond", 1)));
      if (battery.getBatteryCharge() >= setting("battery.resumePercent", 100)) battery.setCharging(false);
      return;
    }
    if (nodes.isEmpty()) { state.setDriving(false); return; }
    Node node = nodes.firstEntry().getValue();
    if (!node.getNodeId().equals(state.getLastNodeId()) || node.getSequenceId() != state.getLastNodeSequenceId()) {
      if (motionSequence != node.getSequenceId()) {
        startX = state.getAgvPosition().getX(); startY = state.getAgvPosition().getY();
        if (node.getNodePosition() == null) throw new IllegalArgumentException("Node needs coordinates");
        double dx = node.getNodePosition().getX() - startX, dy = node.getNodePosition().getY() - startY;
        motionDistance = Math.hypot(dx, dy); targetHeading = Math.atan2(dy, dx);
        double angle = Double.isNaN(heading) ? 0 : Math.abs(Math.atan2(Math.sin(targetHeading-heading), Math.cos(targetHeading-heading)));
        rotationDuration = !Boolean.parseBoolean(settings.getProperty("rotation.enabled", "true")) || angle < 1e-9 ? 0
            : duration(Math.toDegrees(angle), setting("rotation.maxVelocityDegPerSec", 90),
                setting("rotation.maxAccelerationDegPerSec2", 180), setting("rotation.maxDecelerationDegPerSec2", 180));
        motionSequence = node.getSequenceId(); motionElapsed = 0;
        motionDuration = motionDistance < 1e-9 ? 0 : duration(motionDistance, speed(), 1, 1);
      }
      motionElapsed += seconds; state.setDriving(true);
      double distance = travelled(Math.max(0, motionElapsed - rotationDuration), motionDistance, speed(), 1, 1);
      double ratio = motionDistance == 0 ? 1 : Math.min(1, distance / motionDistance);
      state.getAgvPosition().setX(startX + (node.getNodePosition().getX() - startX) * ratio);
      state.getAgvPosition().setY(startY + (node.getNodePosition().getY() - startY) * ratio);
      if (motionElapsed + 1e-8 < rotationDuration + motionDuration) return;
      heading = targetHeading; state.getAgvPosition().setTheta(heading);
      state.setLastNodeId(node.getNodeId()); state.setLastNodeSequenceId(node.getSequenceId());
      state.setDriving(false); motionSequence = -1;
      Edge edge = edges.remove(node.getSequenceId() - 1);
      double meters = edge != null && edge.getLength() != null ? edge.getLength() : motionDistance;
      battery.setBatteryCharge(Math.max(0, battery.getBatteryCharge() - meters * setting("battery.consumptionPercentPerMeter", .01)));
    }
    // Keep WAITING/RUNNING actions visible until acknowledged as FINISHED.
    for (var action : node.getActions()) {
      var status = actions.get(action.getActionId());
      if (status.getActionStatus() == ActionStatus.FINISHED || status.getActionStatus() == ActionStatus.FAILED) continue;
      if (!action.getActionId().equals(runningAction)) {
        runningAction = action.getActionId(); actionElapsed = 0; status.setActionStatus(ActionStatus.RUNNING);
      }
      actionElapsed += seconds;
      if (actionElapsed + 1e-8 < setting("motion.operationSeconds", 1)) return;
      switch (action.getActionType()) {
        case "Load cargo" -> loaded = true;
        case "Unload cargo" -> loaded = false;
        case "CHARGE" -> battery.setCharging(true);
      }
      status.setActionStatus(ActionStatus.FINISHED); runningAction = null;
    }
    nodes.remove(node.getSequenceId());
  }

  void publish() {
    state.setHeaderId(header++); state.setTimestamp(Instant.now()); state.setVersion(VERSION);
    state.setManufacturer(manufacturer); state.setSerialNumber(serial);
    state.setActionStates(new ArrayList<>(actions.values()));
    state.setNodeStates(nodes.values().stream().filter(n -> n.getSequenceId() > state.getLastNodeSequenceId())
        .map(n -> new NodeState(n.getNodeId(), n.getSequenceId(), true).setNodePosition(n.getNodePosition())).toList());
    state.setEdgeStates(edges.values().stream().map(e -> new EdgeState(e.getEdgeId(), e.getSequenceId(), true)).toList());
    state.setLoads(loaded ? List.of(new Load().setLoadId("cargo-" + serial)) : List.of());
    output.accept("state", JSON.toJson(state));
  }

  void save(Path file, String fingerprint) throws Exception {
    var p = new Properties(); p.setProperty("model", fingerprint); p.setProperty("state", JSON.toJson(state));
    p.setProperty("baseEnd", "" + baseEnd); p.setProperty("baseEndNode", baseEndNode);
    p.setProperty("loaded", "" + loaded); p.setProperty("heading", "" + heading);
    // Persist in-flight motion as well as the released base for a graceful process restart.
    p.setProperty("nodes", "" + nodes.size()); int i=0;
    for (Node n : nodes.values()) p.setProperty("node." + i++, JSON.toJson(n));
    p.setProperty("edges", "" + edges.size()); i=0;
    for (Edge e : edges.values()) p.setProperty("edge." + i++, JSON.toJson(e));
    p.setProperty("motionSequence", ""+motionSequence); p.setProperty("motionElapsed", ""+motionElapsed);
    p.setProperty("motionDistance", ""+motionDistance); p.setProperty("motionDuration", ""+motionDuration);
    p.setProperty("rotationDuration", ""+rotationDuration); p.setProperty("startX", ""+startX); p.setProperty("startY", ""+startY);
    p.setProperty("targetHeading", ""+targetHeading); p.setProperty("actionElapsed", ""+actionElapsed);
    if (runningAction != null) p.setProperty("runningAction", runningAction);
    Files.createDirectories(file.getParent()); Path tmp = file.resolveSibling(file.getFileName()+".tmp");
    try(var out=Files.newOutputStream(tmp)) { p.store(out, "Sorting MQTT vehicle checkpoint"); }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
  void restore(Path file, String fingerprint) throws Exception {
    if (!Files.exists(file)) return;
    var p = new Properties(); try(var in=Files.newInputStream(file)) {p.load(in);}
    if (!fingerprint.equals(p.getProperty("model"))) throw new IllegalStateException("Simulator model changed; run setup before restart");
    state = JSON.fromJson(p.getProperty("state"), State.class);
    if (!serial.equals(state.getSerialNumber()) || !manufacturer.equals(state.getManufacturer()))
      throw new IllegalStateException("Simulator identity changed; run setup");
    header = state.getHeaderId()+1; baseEnd=Long.parseLong(p.getProperty("baseEnd")); baseEndNode=p.getProperty("baseEndNode");
    loaded=Boolean.parseBoolean(p.getProperty("loaded")); heading=Double.parseDouble(p.getProperty("heading"));
    state.getActionStates().forEach(a -> actions.put(a.getActionId(), a));
    for(int i=0;i<Integer.parseInt(p.getProperty("nodes"));i++) {var n=JSON.fromJson(p.getProperty("node."+i),Node.class); nodes.put(n.getSequenceId(),n);}
    for(int i=0;i<Integer.parseInt(p.getProperty("edges"));i++) {var e=JSON.fromJson(p.getProperty("edge."+i),Edge.class); edges.put(e.getSequenceId(),e);}
    motionSequence=Long.parseLong(p.getProperty("motionSequence")); motionElapsed=Double.parseDouble(p.getProperty("motionElapsed"));
    motionDistance=Double.parseDouble(p.getProperty("motionDistance")); motionDuration=Double.parseDouble(p.getProperty("motionDuration"));
    rotationDuration=Double.parseDouble(p.getProperty("rotationDuration")); startX=Double.parseDouble(p.getProperty("startX")); startY=Double.parseDouble(p.getProperty("startY"));
    targetHeading=Double.parseDouble(p.getProperty("targetHeading")); actionElapsed=Double.parseDouble(p.getProperty("actionElapsed")); runningAction=p.getProperty("runningAction");
  }
}
