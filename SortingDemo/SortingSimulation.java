import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.opentcs.access.KernelServicePortal;
import org.opentcs.access.rmi.KernelServicePortalBuilder;
import org.opentcs.access.to.order.DestinationCreationTO;
import org.opentcs.access.to.order.TransportOrderCreationTO;
import org.opentcs.common.GuestUserCredentials;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.util.persistence.ModelParser;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterDescriptionImpl;

/** Checkpointed, physically acknowledged continuous sorting with battery and hourly accounting. */
class SortingSimulation {
  static final String VERSION = "mqtt-scheduler-v1";
  static final Path MODEL = Path.of("SortingDemo/Sorting.xml");
  static final Path STATE = Path.of("SortingDemo/runtime/traffic-state.properties");
  static final int VEHICLES;
  static final Path HOURLY = Path.of("SortingDemo/runtime/hourly.csv");
  static final Path DRAIN_REQUEST = Path.of("SortingDemo/runtime/drain.request");
  static final Path MEASURE_REQUEST = Path.of("SortingDemo/runtime/start-measurement.request");
  static final Properties SETTINGS = new Properties();
  static final double CONSUMPTION;
  static final double CHARGE_THRESHOLD;
  static final double RESUME_ENERGY;
  static {
    try {
      var config = SETTINGS;
      try (var in = Files.newInputStream(Path.of("SortingDemo/simulation.properties"))) { config.load(in); }
      VEHICLES = Integer.parseInt(config.getProperty("fleet.size"));
      if (VEHICLES < 1 || VEHICLES > 99) throw new IllegalArgumentException("fleet.size must be between 1 and 99");
      CONSUMPTION = Double.parseDouble(config.getProperty("battery.consumptionPercentPerMeter", "0.01"));
      CHARGE_THRESHOLD = Double.parseDouble(config.getProperty("battery.chargeThresholdPercent", "20"));
      RESUME_ENERGY = Double.parseDouble(config.getProperty("battery.resumePercent", "100"));
      if (!(Double.isFinite(CONSUMPTION) && CONSUMPTION > 0 && CHARGE_THRESHOLD > 0 && CHARGE_THRESHOLD < RESUME_ENERGY && RESUME_ENERGY <= 100)) {
        throw new IllegalArgumentException("Invalid battery parameters");
      }
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }

  static void report(State state) throws Exception {
    Path temporary = HOURLY.resolveSibling("hourly.tmp");
    Files.writeString(temporary, state.metrics.csv(System.currentTimeMillis()));
    Files.move(temporary, HOURLY, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  static class State {
    String run = Long.toString(System.currentTimeMillis());
    long randomSeed = new java.security.SecureRandom().nextLong();
    String fingerprint;
    final int[] completed = new int[VEHICLES + 1], charges = new int[VEHICLES + 1], sequence = new int[VEHICLES + 1];
    final double[] energy = new double[VEHICLES + 1];
    final boolean[] loaded = new boolean[VEHICLES + 1], charging = new boolean[VEHICLES + 1], submitted = new boolean[VEHICLES + 1];
    final String[] active = new String[VEHICLES + 1], stage = new String[VEHICLES + 1], target = new String[VEHICLES + 1];
    final int[] pickup = new int[VEHICLES + 1];
    final long[] accounted = new long[VEHICLES + 1];
    SortingMetrics metrics = new SortingMetrics();
    State() { Arrays.fill(energy, 100); }
  }

  static String fingerprint() throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(MODEL)));
  }

  static Properties encode(State state) {
    var props = new Properties();
    props.setProperty("version", VERSION);
    props.setProperty("fleet.size", Integer.toString(VEHICLES));
    props.setProperty("battery.config", CONSUMPTION + "," + CHARGE_THRESHOLD + "," + RESUME_ENERGY);
    props.setProperty("model", state.fingerprint);
    props.setProperty("run", state.run);
    props.setProperty("sorting.randomSeed", Long.toString(state.randomSeed));
    state.metrics.save(props);
    for (int i = 1; i <= VEHICLES; i++) {
      props.setProperty("pickup." + i, "" + state.pickup[i]);
      props.setProperty("completed." + i, "" + state.completed[i]);
      props.setProperty("charges." + i, "" + state.charges[i]);
      props.setProperty("sequence." + i, "" + state.sequence[i]);
      props.setProperty("energy." + i, "" + state.energy[i]);
      props.setProperty("loaded." + i, "" + state.loaded[i]);
      props.setProperty("charging." + i, "" + state.charging[i]);
      props.setProperty("accounted." + i, "" + state.accounted[i]);
      props.setProperty("submitted." + i, "" + state.submitted[i]);
      if (state.target[i] != null) props.setProperty("target." + i, state.target[i]);
      if (state.active[i] != null) {
        props.setProperty("active." + i, state.active[i]);
        props.setProperty("stage." + i, state.stage[i]);
      }
    }
    return props;
  }

  static void save(State state) throws Exception {
    Files.createDirectories(STATE.getParent());
    Path temp = STATE.resolveSibling("traffic-state.tmp");
    try (OutputStream output = Files.newOutputStream(temp)) { encode(state).store(output, "Native Scheduler demo checkpoint"); }
    Files.move(temp, STATE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  static State decode(Properties props, String model) {
    if (!Integer.toString(VEHICLES).equals(props.getProperty("fleet.size")))
      throw new IllegalStateException("Fleet size changed; stop simulators and run setup (old state is backed up)");
    if (!VERSION.equals(props.getProperty("version")) || !model.equals(props.getProperty("model")))
      throw new IllegalStateException("Checkpoint/model differs; run setup to migrate to native Scheduler");
    if (!(CONSUMPTION + "," + CHARGE_THRESHOLD + "," + RESUME_ENERGY).equals(props.getProperty("battery.config")))
      throw new IllegalStateException("Battery parameters changed; run setup");
    var s = new State(); s.fingerprint = model; s.run = props.getProperty("run");
    s.randomSeed = Long.parseLong(props.getProperty("sorting.randomSeed")); s.metrics = SortingMetrics.load(props);
    for (int i = 1; i <= VEHICLES; i++) {
      s.completed[i] = Integer.parseInt(props.getProperty("completed." + i));
      s.charges[i] = Integer.parseInt(props.getProperty("charges." + i));
      s.sequence[i] = Integer.parseInt(props.getProperty("sequence." + i));
      s.energy[i] = Double.parseDouble(props.getProperty("energy." + i));
      s.loaded[i] = Boolean.parseBoolean(props.getProperty("loaded." + i));
      s.charging[i] = Boolean.parseBoolean(props.getProperty("charging." + i));
      s.accounted[i] = Long.parseLong(props.getProperty("accounted." + i));
      s.submitted[i] = Boolean.parseBoolean(props.getProperty("submitted." + i, "false"));
      s.active[i] = props.getProperty("active." + i); s.stage[i] = props.getProperty("stage." + i);
      s.target[i] = props.getProperty("target." + i);
      s.pickup[i] = Integer.parseInt(props.getProperty("pickup." + i,
          "PICKUP".equals(s.stage[i]) ? "" + ((i - 1) % 3 + 1) : "0"));
      if (s.pickup[i] < 0 || s.pickup[i] > 3) throw new IllegalStateException("Invalid pickup selection");
    }
    return s;
  }

  static State load() throws Exception {
    if (!Files.exists(STATE)) throw new IllegalStateException("Run setup first");
    var props = new Properties();
    try (InputStream input = Files.newInputStream(STATE)) { props.load(input); }
    return decode(props, fingerprint());
  }

  static List<DestinationCreationTO> destinations(State s, int vehicle) {
    if (s.stage[vehicle].equals("PARK")) return List.of(new DestinationCreationTO("init_" + vehicle, "MOVE"));
    if (s.stage[vehicle].equals("CHARGE")) return List.of(new DestinationCreationTO("LOC_init_" + vehicle, "CHARGE"));
    if (s.stage[vehicle].equals("DELIVERY")) return List.of(new DestinationCreationTO("LOC_" + s.target[vehicle], "Unload cargo"));
    var result = new ArrayList<DestinationCreationTO>();
    int pickup = s.pickup[vehicle] == 0 ? (vehicle - 1) % 3 + 1 : s.pickup[vehicle];
    for (int slot = 4; slot >= 1; slot--)
      result.add(new DestinationCreationTO("waite_" + ((pickup - 1) * 4 + slot), "MOVE"));
    result.add(new DestinationCreationTO("LOC_pickup_" + pickup, "Load cargo"));
    return result;
  }

  static void prepare(State s, int i, SortingTraffic traffic) {
    s.stage[i] = s.loaded[i] ? "DELIVERY" : s.energy[i] <= CHARGE_THRESHOLD + 1e-8 ? "CHARGE" : "PICKUP";
    if (s.stage[i].equals("PICKUP")) {
      if (s.pickup[i] == 0) s.pickup[i] = (i - 1) % 3 + 1;
      s.target[i] = traffic.destination(i, s.completed[i] + 1);
    }
    s.active[i] = "Sorting-" + s.run + "-v" + i + "-n" + (++s.sequence[i]);
    s.accounted[i] = 0; s.submitted[i] = false;
  }

  static long travelled(TransportOrder order) {
    long result = 0;
    var drives = order.getAllDriveOrders();
    for (int i = 0; i < drives.size(); i++) {
      var drive = drives.get(i);
      if (drive.getRoute() == null) continue;
      boolean done = order.getState() == TransportOrder.State.FINISHED
          || drive.getState() == org.opentcs.data.order.DriveOrder.State.FINISHED;
      for (var step : drive.getRoute().getSteps())
        if (step.getPath() != null && (done || i == order.getCurrentDriveOrderIndex()
            && step.getRouteIndex() <= order.getCurrentRouteStepIndex())) result += step.getPath().getLength();
    }
    return result;
  }

  static void finish(State s, int i, long timestamp) {
    switch (s.stage[i]) {
      case "PARK" -> { /* Maintenance-only parking, not a sort or a charge. */ }
      case "PICKUP" -> s.loaded[i] = true;
      case "DELIVERY" -> { s.loaded[i] = false; s.completed[i]++; s.metrics.record(timestamp, i, false); }
      case "CHARGE" -> { s.charging[i] = true; s.charges[i]++; s.metrics.record(timestamp, i, true); }
      default -> throw new IllegalStateException("Unknown stage");
    }
    s.active[i] = null; s.stage[i] = null; s.accounted[i] = 0;
  }

  static Map<Integer, Vehicle> fleet(KernelServicePortal portal) {
    var result = new TreeMap<Integer, Vehicle>();
    for (var vehicle : portal.getVehicleService().fetch(Vehicle.class)) {
      if (!vehicle.getName().matches("Sorting-AGV-\\d{2}")) {
        throw new IllegalStateException("Unexpected vehicle: " + vehicle.getName());
      }
      result.put(Integer.parseInt(vehicle.getName().substring("Sorting-AGV-".length())), vehicle);
    }
    if (result.size() != VEHICLES || result.firstKey() != 1 || result.lastKey() != VEHICLES) {
      throw new IllegalStateException("Expected all " + VEHICLES + " Sorting vehicles");
    }
    return result;
  }

  static Set<String> avoidance(Vehicle v, Collection<Vehicle> fleet, TransportOrder order) {
    var avoid = new TreeSet<String>();
    for (var other : fleet)
      if (!other.getName().equals(v.getName()) && other.getCurrentPosition() != null) avoid.add(other.getCurrentPosition().getName());
    // Never exclude this order's required destinations; their occupants must be allowed to leave.
    for (var drive : order.getAllDriveOrders()) {
      String name = drive.getDestination().getDestination().getName();
      avoid.remove(name.startsWith("LOC_") ? name.substring(4) : name);
    }
    avoid.remove(v.getCurrentPosition().getName());
    // REGULAR may start at a future point already allocated to this vehicle.
    v.getAllocatedResources().forEach(group -> group.forEach(ref -> avoid.remove(ref.getName())));
    return avoid;
  }

  static void rerouteWaiting(KernelServicePortal portal, State s, int i, Vehicle v) {
    if (v.getState() != Vehicle.State.IDLE || v.isPaused() || s.active[i] == null || v.getCurrentPosition() == null) return;
    var order = portal.getTransportOrderService().fetch(TransportOrder.class, s.active[i]).orElse(null);
    if (order == null || order.getState() != TransportOrder.State.BEING_PROCESSED) return;
    v = portal.getVehicleService().fetch(Vehicle.class, v.getReference()).orElseThrow();
    var avoid = avoidance(v, fleet(portal).values(), order);
    portal.getTransportOrderService().updateObjectProperty(order.getReference(),
        org.opentcs.data.ObjectPropConstants.TRANSPORT_ORDER_RESOURCES_TO_AVOID, String.join(",", avoid));
    // REGULAR retains already executed/allocated steps and lets Scheduler govern the new route.
    try {
      portal.getDispatcherService().reroute(v.getReference(), org.opentcs.data.order.ReroutingType.REGULAR);
    } catch (IllegalStateException e) {
      if (e.getMessage() == null || !e.getMessage().startsWith("Could not get path")) throw e;
      portal.getTransportOrderService().updateObjectProperty(order.getReference(),
          org.opentcs.data.ObjectPropConstants.TRANSPORT_ORDER_RESOURCES_TO_AVOID,
          order.getProperty(org.opentcs.data.ObjectPropConstants.TRANSPORT_ORDER_RESOURCES_TO_AVOID));
      System.err.println(Instant.now() + " REROUTE_DEFERRED " + v.getName() + " " + e.getMessage());
      return;
    }
    System.out.println(Instant.now() + " REROUTE " + v.getName() + " avoiding occupied points=" + avoid.size());
  }

  static boolean readyForOrder(Vehicle vehicle) {
    return !vehicle.isPaused() && vehicle.getState() == Vehicle.State.IDLE
        && vehicle.getCurrentPosition() != null
        && vehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED;
  }

  static void run(KernelServicePortal portal) throws Exception {
    var s = load(); var traffic = new SortingTraffic(MODEL.toFile(), s.randomSeed);
    var pickupSelector = new SortingPickup(MODEL, SETTINGS);
    var owned = new HashSet<>(Arrays.asList(s.active));
    for (var order : portal.getTransportOrderService().fetch(TransportOrder.class))
      if (!order.getState().isFinalState() && !owned.contains(order.getName()))
        throw new IllegalStateException("Foreign unfinished order: " + order.getName());
    // A Kernel restart restores the map but may leave its communication adapters disabled.
    // Validate the whole fleet/checkpoint before enabling vehicles to resume processing.
    var startupFleet = fleet(portal);
    for (var entry : startupFleet.entrySet()) {
      int i = entry.getKey(); var v = entry.getValue();
      if (!(portal.getVehicleService().fetchProcessModel(v.getReference())
          instanceof org.opentcs.commadapter.vehicle.vda5050.v2_0.controlcenter.ProcessModelImplTO))
        throw new IllegalStateException(v.getName() + " needs a VDA5050 2.0 adapter; check the map/setup");
      if (s.active[i] != null && s.submitted[i]
          && portal.getTransportOrderService().fetch(TransportOrder.class, s.active[i]).isEmpty())
        throw new IllegalStateException("Submitted order missing from Kernel: " + s.active[i] + "; reconcile state or run setup");
    }
    int restoredDrivers = 0;
    for (var v : startupFleet.values()) {
      portal.getVehicleService().updateVehicleEnergyLevelThresholdSet(
          v.getReference(), v.getEnergyLevelThresholdSet().withEnergyLevelCritical(0));
      if (!portal.getVehicleService().fetchProcessModel(v.getReference()).isCommAdapterEnabled()) {
        portal.getVehicleService().enableCommAdapter(v.getReference());
        portal.getVehicleService().updateVehicleIntegrationLevel(v.getReference(), Vehicle.IntegrationLevel.TO_BE_UTILIZED);
        restoredDrivers++;
      }
    }
    System.out.println(Instant.now() + " STARTUP restoredDrivers=" + restoredDrivers
        + "; waiting for MQTT vehicle states before dispatching");
    long lastReport = 0;
    var lastReroute = new long[VEHICLES + 1];
    var lastProgress = new long[VEHICLES + 1]; Arrays.fill(lastProgress, System.currentTimeMillis());
    while (true) {
      if (!portal.getPlantModelService().getModelName().equals("Sorting")) throw new IllegalStateException("Kernel model changed");
      long pollStarted = System.currentTimeMillis();
      if (Files.exists(MEASURE_REQUEST)) {
        s.metrics = new SortingMetrics(); s.metrics.startMillis = pollStarted;
        save(s); Files.delete(MEASURE_REQUEST);
      }
      if (s.metrics.startMillis == 0) { s.metrics.startMillis = pollStarted; save(s); }
      var snapshot = fleet(portal);
      for (var entry : snapshot.entrySet()) {
        int i = entry.getKey(); var v = entry.getValue();
        s.energy[i] = v.getEnergyLevel(); // Vehicle battery feedback arrives exclusively through MQTT.
        if (s.charging[i]) {
          if (s.energy[i] >= RESUME_ENERGY && v.getState() == Vehicle.State.IDLE) {
            s.charging[i] = false; save(s);
            System.out.println(Instant.now() + " RECHARGED " + v.getName());
          } else continue;
        }
        if (s.active[i] == null) {
          if (!readyForOrder(v)) continue;
          if (Files.exists(DRAIN_REQUEST) && !s.loaded[i]) {
            if (v.getCurrentPosition() != null && v.getCurrentPosition().getName().equals("init_" + i)) continue;
            prepare(s, i, traffic); s.stage[i] = "PARK";
          } else {
            if (!s.loaded[i] && s.energy[i] > CHARGE_THRESHOLD + 1e-8) {
              var choice = pickupSelector.choose(i, s, snapshot);
              if (choice == null) throw new IllegalStateException("No reachable pickup from " + v.getCurrentPosition());
              s.pickup[i] = choice.pickup();
              System.out.println(Instant.now() + " PICKUP_SELECT " + v.getName() + " pickup=" + choice.pickup()
                  + " routeMeters=" + choice.meters() + " travelSeconds=" + choice.travelSeconds()
                  + " queued=" + choice.queued() + " score=" + choice.score());
            }
            prepare(s, i, traffic);
          }
          save(s); // Persist intent before creating the kernel order.
        }
        var order = portal.getTransportOrderService().fetch(TransportOrder.class, s.active[i]).orElse(null);
        if (order == null) {
          if (s.submitted[i]) throw new IllegalStateException("Submitted order missing from Kernel: " + s.active[i] + "; restore Kernel state or run setup");
          portal.getTransportOrderService().createTransportOrder(new TransportOrderCreationTO(s.active[i], destinations(s, i))
              .withIntendedVehicleName(v.getName()).withProperty("sorting:strategy", VERSION)
              .withProperty("sorting:pickup", Integer.toString(s.pickup[i])).withProperty("sorting:stage", s.stage[i]).withProperty("sorting:cycle", "" + (s.completed[i] + 1)));
          s.submitted[i] = true; save(s);
          portal.getDispatcherService().dispatch();
          lastProgress[i] = pollStarted;
          System.out.println(Instant.now() + " SUBMIT " + v.getName() + " " + s.stage[i] + " target=" + s.target[i]);
          continue;
        }
        if (!s.submitted[i]) { s.submitted[i] = true; save(s); }
        if (order.getState().isFinalState() && order.getState() != TransportOrder.State.FINISHED)
          throw new IllegalStateException("Order failed: " + order.getName() + " " + order.getState());
        long distance = travelled(order);
        if (distance != s.accounted[i]) { s.accounted[i] = distance; save(s); lastProgress[i] = pollStarted; }
        if (order.getState() == TransportOrder.State.FINISHED) {
          String stage = s.stage[i];
          finish(s, i, order.getFinishedTime().toEpochMilli()); save(s);
          System.out.println(Instant.now() + " FINISHED " + v.getName() + " " + stage + " sorts=" + s.completed[i] + " energy=" + s.energy[i]);
          lastProgress[i] = pollStarted;
        }

        if (pollStarted - lastProgress[i] > 5000 && pollStarted - lastReroute[i] > 10000) {
          rerouteWaiting(portal, s, i, v); lastReroute[i] = pollStarted;
        }
        if (pollStarted - lastProgress[i] > 60000) {
          System.err.println(Instant.now() + " WAITING " + v.getName() + " order=" + s.active[i] + " position=" + v.getCurrentPosition()
              + "; inspect Scheduler allocations/traffic conflicts"); lastProgress[i] = pollStarted;
        }
      }
      // All owned orders were polled after this watermark; later completions belong to the next poll.
      s.metrics.confirmedThroughMillis = pollStarted;
      if (pollStarted - lastReport >= 5000) { save(s); report(s); lastReport = pollStarted; }
      if (Files.exists(DRAIN_REQUEST) && Arrays.stream(s.active).allMatch(Objects::isNull)
          && fleet(portal).entrySet().stream().allMatch(e -> e.getValue().getCurrentPosition() != null
              && e.getValue().getCurrentPosition().getName().equals("init_" + e.getKey()))) {
        save(s); report(s);
        System.out.println(Instant.now() + " DRAINED all vehicles parked; safe to restart Kernel");
        return;
      }
      Thread.sleep(100);
    }
  }

  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "status" : args[0];
    if (mode.equals("check")) {
      new ModelParser().readModel(MODEL.toFile());
      SortingTrafficTest.main(new String[]{"--routes-only"});
      SortingSchedulerTest.main(new String[0]); SortingPickupTest.main(new String[0]); return;
    }
    if (mode.equals("stats")) {
      var state = load();
      System.out.println(state.metrics.summary(System.currentTimeMillis()));
      System.out.println("CSV: " + HOURLY.toAbsolutePath()); return;
    }
    if (mode.equals("drain")) {
      Files.writeString(DRAIN_REQUEST, Instant.now().toString());
      System.out.println("Drain requested: finish cargo and park for maintenance; keep dispatcher running."); return;
    }
    if (mode.equals("measure")) {
      Files.writeString(MEASURE_REQUEST, Instant.now().toString());
      System.out.println("Requested a new real-hour measurement; running dispatcher will start it at the next checkpoint."); return;
    }
    var portal = new KernelServicePortalBuilder(GuestUserCredentials.USER, GuestUserCredentials.PASSWORD)
        .setEventFilter(new org.opentcs.util.ClassMatcher()).build();
    portal.login("localhost", 1099);
    try {
      var vehicles = portal.getVehicleService();
      var orders = portal.getTransportOrderService();
      if (mode.equals("setup")) {
        Path generatorPid = Path.of("SortingDemo/runtime/simulation.pid");
        if (Files.exists(generatorPid) && ProcessHandle.of(Long.parseLong(Files.readString(generatorPid).trim())).map(ProcessHandle::isAlive).orElse(false))
          throw new IllegalStateException("Stop simulation.sh before setup");
        Path fleetPid = Path.of("SortingDemo/runtime/mqtt-vehicles.pid");
        if (Files.exists(fleetPid) && ProcessHandle.of(Long.parseLong(Files.readString(fleetPid).trim())).map(ProcessHandle::isAlive).orElse(false))
          throw new IllegalStateException("setup resets the model/state: stop mqtt-vehicles.sh first. For normal startup, skip setup and use simulation.sh start.");
        if (orders.fetch(TransportOrder.class).stream().anyMatch(o -> !o.getState().isFinalState())) {
          throw new IllegalStateException("Unfinished orders exist; finish them before replacing the model");
        }
        String stamp = Long.toString(System.currentTimeMillis());
        Path backup = Path.of("SortingDemo/runtime/model-before-sorting-" + stamp + ".xml");
        Files.createDirectories(backup.getParent());
        Files.copy(Path.of("build/install/opentcs/opentcs-kernel/data/model.xml"), backup);
        if (Files.exists(STATE)) Files.copy(STATE, STATE.resolveSibling("traffic-state-" + stamp + ".backup"));
        if (Files.exists(HOURLY)) Files.copy(HOURLY, HOURLY.resolveSibling("hourly-" + stamp + ".csv"));
        Path mqttState = Path.of("SortingDemo/runtime/mqtt-vehicles");
        if (Files.exists(mqttState)) Files.move(mqttState, mqttState.resolveSibling("mqtt-vehicles-" + stamp + ".backup"));
        portal.getPlantModelService().createPlantModel(new ModelParser().readModel(MODEL.toFile()));
        for (int i = 1; i <= VEHICLES; i++) {
          var vehicle = vehicles.fetch(Vehicle.class, SortingTraffic.name(i)).orElseThrow();
          vehicles.attachCommAdapter(vehicle.getReference(), new CommAdapterDescriptionImpl());
          vehicles.enableCommAdapter(vehicle.getReference());
          vehicles.updateVehicleIntegrationLevel(vehicle.getReference(), Vehicle.IntegrationLevel.TO_BE_UTILIZED);
        }
        var state = new State(); state.fingerprint = fingerprint(); save(state); report(state);
        Files.deleteIfExists(MEASURE_REQUEST);
        Files.deleteIfExists(DRAIN_REQUEST);
        System.out.println("Loaded Sorting and enabled " + VEHICLES + " VDA5050 vehicles. Start mqtt-vehicles.sh to bring them online. Backup: " + backup);
      }
      if (mode.equals("run")) { run(portal); return; }
      System.out.println("Kernel=" + portal.getState() + " model=" + portal.getPlantModelService().getModelName());
      for (var vehicle : vehicles.fetch(Vehicle.class).stream().sorted(Comparator.comparing(Vehicle::getName)).toList()) {
        var adapter = vehicles.fetchProcessModel(vehicle.getReference());
        String mqtt = adapter instanceof org.opentcs.commadapter.vehicle.vda5050.v2_0.controlcenter.ProcessModelImplTO model
            ? " mqttBroker=" + model.isBrokerConnected() + " vehicleConnection="
                + (model.getCurrentConnection() == null ? "UNKNOWN" : model.getCurrentConnection().getConnectionState())
            : " adapter=" + adapter.getClass().getSimpleName();
        System.out.println(vehicle.getName() + " state=" + vehicle.getState() + " position="
            + (vehicle.getCurrentPosition() == null ? "null" : vehicle.getCurrentPosition().getName())
            + " energy=" + vehicle.getEnergyLevel() + "%" + mqtt
            + " allocated=" + vehicle.getAllocatedResources().stream().mapToInt(Set::size).sum()
            + " claimed=" + vehicle.getClaimedResources().stream().mapToInt(Set::size).sum() + " enabled=" + vehicles.fetchProcessModel(vehicle.getReference()).isCommAdapterEnabled());
      }
      if (Files.exists(STATE)) {
        var state = load();
        System.out.println("Scheduler=native active orders=" + Arrays.stream(state.active).filter(Objects::nonNull).count()
            + " completed sorts=" + Arrays.stream(state.completed).sum()
            + " vehicles with sorts=" + Arrays.stream(state.completed).filter(c -> c > 0).count() + "/" + VEHICLES
            + " minimum sorts=" + Arrays.stream(state.completed, 1, VEHICLES + 1).min().orElse(0)
            + " charges=" + Arrays.stream(state.charges).sum());
        var selector = new SortingPickup(MODEL, SETTINGS);
        var snapshot = fleet(portal);
        for (int pickup = 1; pickup <= 3; pickup++) System.out.println("Pickup " + pickup + " queuedOrPresent="
            + selector.queued(pickup, state, snapshot, 0));
        System.out.println(state.metrics.summary(System.currentTimeMillis()));
      }
      var counts = new TreeMap<String, Integer>();
      for (var order : orders.fetch(TransportOrder.class)) counts.merge(order.getState().name(), 1, Integer::sum);
      System.out.println("Transport orders: " + counts);
    } finally { portal.logout(); }
  }
}
