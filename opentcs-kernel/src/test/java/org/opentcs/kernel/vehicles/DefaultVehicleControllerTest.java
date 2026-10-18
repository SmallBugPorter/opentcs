// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.kernel.vehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opentcs.DataObjectFactory;
import org.opentcs.components.kernel.PositionDeviationPolicy;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.components.kernel.services.DispatcherService;
import org.opentcs.components.kernel.services.InternalTransportOrderService;
import org.opentcs.components.kernel.services.InternalVehicleService;
import org.opentcs.components.kernel.services.NotificationService;
import org.opentcs.data.model.Location;
import org.opentcs.data.model.Path;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Pose;
import org.opentcs.data.model.Triple;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.ReroutingType;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.IncomingPoseTransformer;
import org.opentcs.drivers.vehicle.LoadHandlingDevice;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.MovementCommandTransformer;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleCommAdapterEvent;
import org.opentcs.drivers.vehicle.VehicleDataTransformerFactory;
import org.opentcs.drivers.vehicle.VehicleProcessModel;
import org.opentcs.drivers.vehicle.management.VehicleProcessModelTO;
import org.opentcs.kernel.KernelApplicationConfiguration;
import org.opentcs.kernel.vehicles.transformers.VehicleDataTransformerRegistry;
import org.opentcs.strategies.basic.scheduling.DummyScheduler;
import org.opentcs.util.event.EventBus;
import org.opentcs.util.event.SimpleEventBus;

/**
 * Test cases for StandardVehicleController.
 */
class DefaultVehicleControllerTest {

  private static final String RECHARGE_OP = "recharge";
  /**
   * Creates model objects for us.
   */
  private final DataObjectFactory dataObjectFactory = new DataObjectFactory();
  /**
   * The kernel application's event bus.
   */
  private final EventBus eventBus = new SimpleEventBus();
  /**
   * A vehicle.
   */
  private Vehicle vehicle;
  /**
   * A vehicle model.
   */
  private VehicleProcessModel vehicleModel;
  /**
   * A serializable representation of the vehicle model.
   */
  private VehicleProcessModelTO vehicleModelTO;
  /**
   * A (mocked) communication adapter.
   */
  private VehicleCommAdapter commAdapter;
  /**
   * The (mocked) vehicle service.
   */
  private InternalVehicleService vehicleService;
  /**
   * A dummy scheduler.
   */
  private Scheduler scheduler;
  /**
   * A (mocked) components factory.
   */
  private VehicleControllerComponentsFactory componentsFactory;
  /**
   * A (mocked) vehicle data transformer registry.
   */
  private VehicleDataTransformerRegistry dataTransformerRegistry;
  /**
   * A (mocked) vehicle data transformer factory.
   */
  private VehicleDataTransformerFactory dataTransformerFactory;
  /**
   * A deviation policy registry.
   */
  private PositionDeviationPolicyRegistry positionDeviationPolicyRegistry;
  /**
   * A (mocked) incoming pose transformer.
   */
  private IncomingPoseTransformer poseTransformer;
  /**
   * A (mocked) movement command transformer.
   */
  private MovementCommandTransformer movementCommandTransformer;
  /**
   * A (mocked) peripheral interactor.
   */
  private PeripheralInteractor peripheralInteractor;
  /**
   * The instance we're testing.
   */
  private DefaultVehicleController stdVehicleController;
  private MovementCommandMapper movementCommandMapper;
  private ScheduledExecutorService kernelExecutor;
  private ScheduledFuture<?> retryFuture;
  private final List<Runnable> retries = new ArrayList<>();
  private KernelApplicationConfiguration configuration;

  @BeforeEach
  void setUp() {
    vehicle = dataObjectFactory
        .createVehicle()
        .withProperties(Map.of("tcs:vehicleDataTransformer", "dummyFactory"));
    vehicleModel = new VehicleProcessModel(vehicle);
    vehicleModelTO = new VehicleProcessModelTO();
    commAdapter = mock(VehicleCommAdapter.class);
    vehicleService = mock(InternalVehicleService.class);
    componentsFactory = mock(VehicleControllerComponentsFactory.class);
    peripheralInteractor = mock(PeripheralInteractor.class);
    dataTransformerFactory = mock(VehicleDataTransformerFactory.class);
    poseTransformer = mock(IncomingPoseTransformer.class);
    movementCommandTransformer = mock(MovementCommandTransformer.class);
    dataTransformerRegistry = new VehicleDataTransformerRegistry(Set.of(dataTransformerFactory));
    positionDeviationPolicyRegistry = mock(PositionDeviationPolicyRegistry.class);

    doReturn("dummyFactory").when(dataTransformerFactory).getName();
    doReturn(poseTransformer).when(dataTransformerFactory).createIncomingPoseTransformer(vehicle);
    doReturn(movementCommandTransformer)
        .when(dataTransformerFactory).createMovementCommandTransformer(vehicle);
    doReturn(true).when(dataTransformerFactory).providesTransformersFor(vehicle);
    when(poseTransformer.apply(any(Pose.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(movementCommandTransformer.apply(any(MovementCommand.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    doReturn(RECHARGE_OP).when(commAdapter).getRechargeOperation();
    doReturn(vehicleModel).when(commAdapter).getProcessModel();
    doReturn(vehicleModelTO).when(commAdapter).createTransferableProcessModel();

    doReturn(Optional.of(vehicle)).when(vehicleService).fetch(
        Vehicle.class, vehicle.getReference()
    );
    doReturn(Optional.of(vehicle)).when(vehicleService).fetch(Vehicle.class, vehicle.getName());

    doReturn(peripheralInteractor).when(componentsFactory)
        .createPeripheralInteractor(vehicle.getReference());

    doReturn(new VehiclePositionResolver(vehicleService, new FixedPositionDeviationPolicy(0, 0)))
        .when(componentsFactory)
        .createVehiclePositionResolver(any(PositionDeviationPolicy.class));

    when(positionDeviationPolicyRegistry.getPolicyForVehicle(vehicle))
        .thenReturn(new FixedPositionDeviationPolicy(0, 0));

    scheduler = spy(new DummyScheduler());
    scheduler.initialize();
    movementCommandMapper = mock(MovementCommandMapper.class);
    kernelExecutor = mock(ScheduledExecutorService.class);
    retryFuture = mock(ScheduledFuture.class);
    doAnswer(invocation -> {
      retries.add(invocation.getArgument(0, Runnable.class));
      return retryFuture;
    }).when(kernelExecutor).scheduleWithFixedDelay(
        any(Runnable.class), eq(500L), eq(500L), eq(TimeUnit.MILLISECONDS)
    );
    when(commAdapter.isEnabled()).thenReturn(true);
    configuration = mock(KernelApplicationConfiguration.class);
    when(configuration.vehicleResourceManagementType())
        .thenReturn(KernelApplicationConfiguration.VehicleResourceManagementType.LENGTH_IGNORED);
    stdVehicleController = new DefaultVehicleController(
        vehicle,
        commAdapter,
        vehicleService,
        mock(InternalTransportOrderService.class),
        mock(NotificationService.class),
        mock(DispatcherService.class),
        scheduler,
        eventBus,
        componentsFactory,
        movementCommandMapper,
        configuration,
        new CommandProcessingTracker(),
        dataTransformerRegistry,
        positionDeviationPolicyRegistry,
        kernelExecutor
    );
    stdVehicleController.initialize();
  }

  @AfterEach
  void tearDown() {
    stdVehicleController.terminate();
    scheduler.terminate();
  }

  // Test cases for implementation of interface VehicleManager start here.
  @Test
  void rejectionLogsAcceptanceBeforeAllocationAndAtEnqueue() {
    Point destination = new Point("diagnostic-destination");
    Route.Step step = new Route.Step(null, null, destination, Vehicle.Orientation.FORWARD, 0, 1);
    DriveOrder drive = new DriveOrder(
        "diagnostic-drive", new DriveOrder.Destination(destination.getReference())
    ).withRoute(new Route(List.of(step)));
    TransportOrder order = new TransportOrder("diagnostic-order", List.of(drive))
        .withCurrentDriveOrderIndex(0);
    MovementCommand command = new MovementCommand(
        order, drive, step, "NOP", null, true, null, destination, "NOP", Map.of()
    );
    when(movementCommandMapper.toMovementCommands(any(), any())).thenReturn(List.of(command));
    when(commAdapter.canAcceptNextCommand()).thenReturn(true);
    when(commAdapter.getCommandAcceptanceDiagnostics()).thenReturn("mapReady=true, stateAgeMs=10");
    doNothing().when(scheduler).allocate(any(), any());
    doAnswer(invocation -> {
      invocation.getArgument(1, Runnable.class).run();
      return null;
    }).when(peripheralInteractor).startPreMovementInteractions(any(), any(), any());
    when(commAdapter.enqueueCommand(any())).thenAnswer(invocation -> {
      when(commAdapter.getCommandAcceptanceDiagnostics())
          .thenReturn("mapReady=false, stateAgeMs=2001");
      return false;
    });
    List<String> messages = new ArrayList<>();
    Logger logger = Logger.getLogger(DefaultVehicleController.class.getName());
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        messages.add(record.getMessage());
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    logger.addHandler(handler);
    try {
      stdVehicleController.setTransportOrder(order);
      assertTrue(stdVehicleController.onAllocation(Set.of(destination)));
      String message = messages.stream()
          .filter(text -> text.startsWith("COMMAND_REJECTED_CONTEXT")).findFirst().orElseThrow();
      assertTrue(message.contains("order=diagnostic-order"));
      assertTrue(message.contains("target=diagnostic-destination"));
      assertTrue(message.contains("pendingState=SENDING_PENDING"));
      assertTrue(message.contains("beforeAllocation=[checkedAt="));
      assertTrue(message.contains("mapReady=true, stateAgeMs=10"));
      assertTrue(message.contains("atEnqueue=[mapReady=false, stateAgeMs=2001]"));
      assertTrue(stdVehicleController.getCommandsSent().isEmpty());
      assertEquals(1, retries.size());
    }
    finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void shouldForwardPositionChangeToKernel() {
    Point point = dataObjectFactory.createPoint();
    doReturn(Optional.of(point)).when(vehicleService).fetch(Point.class, point.getName());

    vehicleModel.setPosition(point.getName());

    verify(vehicleService).updateVehiclePosition(
        vehicle.getReference(),
        point.getReference()
    );
  }

  @Test
  void retriesSameCommandOnceWithoutReallocatingOrRepeatingPeripheralOperations() {
    MovementCommand command = prepareCommands("retry-order", 1).getFirst();
    assertTrue(stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint())));
    when(commAdapter.canAcceptNextCommand()).thenReturn(false);
    retries.getFirst().run();
    retries.getFirst().run();
    assertEquals(command, stdVehicleController.getInteractionsPendingCommand().orElseThrow());
    verify(commAdapter).enqueueCommand(command);
    verify(scheduler, never()).free(any(), any());

    when(commAdapter.canAcceptNextCommand()).thenReturn(true);
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    retries.getFirst().run();
    retries.getFirst().run();
    assertEquals(List.of(command), new ArrayList<>(stdVehicleController.getCommandsSent()));
    assertTrue(stdVehicleController.getInteractionsPendingCommand().isEmpty());
    verify(commAdapter, times(2)).enqueueCommand(command);
    verify(scheduler).allocate(any(), any());
    verify(movementCommandTransformer).apply(command);
    verify(peripheralInteractor).startPreMovementInteractions(eq(command), any(), any());
    verify(retryFuture).cancel(false);
  }

  @Test
  void anotherRefusalDuringRetryKeepsOneRetryAndReservation() {
    MovementCommand command = prepareCommands("repeated-refusal", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint()));
    retries.getFirst().run();
    assertEquals(1, retries.size());
    assertTrue(stdVehicleController.getCommandsSent().isEmpty());
    assertEquals(command, stdVehicleController.getInteractionsPendingCommand().orElseThrow());
    verify(scheduler, never()).free(any(), any());
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    retries.getFirst().run();
    verify(commAdapter, times(3)).enqueueCommand(command);
    verify(scheduler).allocate(any(), any());
    verify(movementCommandTransformer).apply(command);
  }

  @Test
  void completionOfEarlierCommandDoesNotAllocatePastRejectedCommand() {
    List<MovementCommand> commands = prepareCommands("three-steps", 3);
    MovementCommand first = commands.get(0);
    MovementCommand second = commands.get(1);
    when(commAdapter.enqueueCommand(first)).thenReturn(true);
    stdVehicleController.onAllocation(Set.of(first.getStep().getDestinationPoint()));
    stdVehicleController.onAllocation(Set.of(second.getStep().getDestinationPoint()));
    doAnswer(invocation -> {
      invocation.getArgument(1, Runnable.class).run();
      return null;
    }).when(peripheralInteractor).startPostMovementInteractions(any(), any(), any());
    vehicleModel.commandExecuted(first);
    verify(scheduler, times(2)).allocate(any(), any());
    verify(scheduler, never()).free(any(), eq(Set.of(second.getStep().getDestinationPoint())));
    assertEquals(second, stdVehicleController.getInteractionsPendingCommand().orElseThrow());
    when(commAdapter.enqueueCommand(second)).thenReturn(true);
    retries.getFirst().run();
    verify(scheduler, times(3)).allocate(any(), any());
    assertEquals(List.of(second), new ArrayList<>(stdVehicleController.getCommandsSent()));
  }

  @Test
  void doesNotBypassPendingPeripheralInteractionAndIgnoresDuplicateCallback() {
    MovementCommand command = prepareCommands("peripheral-order", 1).getFirst();
    List<Runnable> callbacks = new ArrayList<>();
    doAnswer(invocation -> {
      callbacks.add(invocation.getArgument(1, Runnable.class));
      return null;
    }).when(peripheralInteractor).startPreMovementInteractions(any(), any(), any());
    stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint()));
    verify(commAdapter, never()).enqueueCommand(any());
    assertTrue(retries.isEmpty());
    callbacks.getFirst().run();
    callbacks.getFirst().run();
    assertEquals(1, retries.size());
    verify(commAdapter).enqueueCommand(command);
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    retries.getFirst().run();
    callbacks.getFirst().run();
    verify(commAdapter, times(2)).enqueueCommand(command);
  }

  @Test
  void pausedOrDisabledVehicleKeepsRetryDormant() {
    MovementCommand command = prepareCommands("paused-order", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint()));
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    when(vehicleService.fetch(Vehicle.class, vehicle.getReference()))
        .thenReturn(Optional.of(vehicle.withPaused(true)));
    retries.getFirst().run();
    when(vehicleService.fetch(Vehicle.class, vehicle.getReference())).thenReturn(
        Optional.of(vehicle)
    );
    when(commAdapter.isEnabled()).thenReturn(false);
    retries.getFirst().run();
    verify(commAdapter).enqueueCommand(command);
    when(commAdapter.isEnabled()).thenReturn(true);
    retries.getFirst().run();
    verify(commAdapter, times(2)).enqueueCommand(command);
  }

  @Test
  void immediateWithdrawalCancelsRetryAndOldCallbackCannotAffectNewOrder() {
    MovementCommand old = prepareCommands("old-order", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(old.getStep().getDestinationPoint()));
    Runnable oldRetry = retries.getFirst();
    stdVehicleController.abortTransportOrder(true);
    verify(retryFuture).cancel(false);
    assertTrue(stdVehicleController.getInteractionsPendingCommand().isEmpty());
    verify(scheduler).free(any(), eq(Set.of(old.getStep().getDestinationPoint())));
    MovementCommand next = prepareCommands("new-order", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(next.getStep().getDestinationPoint()));
    oldRetry.run();
    verify(commAdapter).enqueueCommand(old);
    assertEquals(next, stdVehicleController.getInteractionsPendingCommand().orElseThrow());
    when(commAdapter.enqueueCommand(next)).thenReturn(true);
    retries.getLast().run();
    assertEquals(List.of(next), new ArrayList<>(stdVehicleController.getCommandsSent()));
  }

  @Test
  void gracefulWithdrawalFinishesOnlyTheAlreadyAllocatedStep() {
    MovementCommand command = prepareCommands("graceful-order", 2).getFirst();
    stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint()));
    stdVehicleController.abortTransportOrder(false);
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    retries.getFirst().run();
    assertEquals(List.of(command), new ArrayList<>(stdVehicleController.getCommandsSent()));
    verify(scheduler).allocate(any(), any());
  }

  @Test
  void terminationCancelsRetryAndQueuedCallbackCannotSend() {
    MovementCommand command = prepareCommands("terminate-order", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(command.getStep().getDestinationPoint()));
    stdVehicleController.terminate();
    when(commAdapter.enqueueCommand(command)).thenReturn(true);
    retries.getFirst().run();
    verify(commAdapter).enqueueCommand(command);
    verify(retryFuture).cancel(false);
  }

  @Test
  void forcedRerouteCancelsRejectedCommandBeforeAllocatingNewRoute() {
    MovementCommand old = prepareCommands("reroute-order", 1).getFirst();
    stdVehicleController.onAllocation(Set.of(old.getStep().getDestinationPoint()));
    Point current = new Point("current");
    when(vehicleService.fetch(Vehicle.class, vehicle.getReference()))
        .thenReturn(Optional.of(vehicle.withCurrentPosition(current.getReference())));
    when(vehicleService.fetch(Point.class, current.getReference())).thenReturn(
        Optional.of(current)
    );
    Point destination = new Point("rerouted-destination");
    Route.Step step = new Route.Step(null, current, destination, Vehicle.Orientation.FORWARD, 0, 1)
        .withReroutingType(ReroutingType.FORCED);
    DriveOrder drive = new DriveOrder(
        "rerouted-drive", new DriveOrder.Destination(destination.getReference())
    ).withRoute(new Route(List.of(step)));
    TransportOrder order = new TransportOrder("reroute-order", List.of(drive))
        .withCurrentDriveOrderIndex(0);
    MovementCommand next = new MovementCommand(
        order, drive, step, "NOP", null, true, null, destination, "NOP", Map.of()
    );
    when(movementCommandMapper.toMovementCommands(any(), any())).thenReturn(List.of(next));
    stdVehicleController.setTransportOrder(order);
    retries.getFirst().run();
    verify(commAdapter).enqueueCommand(old);
    verify(retryFuture).cancel(false);
    when(commAdapter.enqueueCommand(next)).thenReturn(true);
    stdVehicleController.onAllocation(Set.of(destination));
    assertEquals(List.of(next), new ArrayList<>(stdVehicleController.getCommandsSent()));
  }

  private List<MovementCommand> prepareCommands(String orderName, int count) {
    List<Route.Step> steps = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      steps.add(
          new Route.Step(
              null, null, new Point(orderName + "-point-" + i), Vehicle.Orientation.FORWARD, i, 1
          )
      );
    }
    Point destination = steps.getLast().getDestinationPoint();
    DriveOrder drive = new DriveOrder(
        orderName + "-drive", new DriveOrder.Destination(destination.getReference())
    ).withRoute(new Route(steps));
    TransportOrder order = new TransportOrder(orderName, List.of(drive))
        .withCurrentDriveOrderIndex(0).withProcessingVehicle(vehicle.getReference());
    List<MovementCommand> commands = steps.stream().map(
        step -> new MovementCommand(
            order, drive, step, "NOP", null, step == steps.getLast(), null, destination, "NOP", Map
                .of()
        )
    ).toList();
    when(movementCommandMapper.toMovementCommands(any(), any())).thenReturn(commands);
    when(commAdapter.canAcceptNextCommand()).thenReturn(true);
    when(commAdapter.enqueueCommand(any())).thenReturn(false);
    doNothing().when(scheduler).allocate(any(), any());
    doAnswer(invocation -> {
      invocation.getArgument(1, Runnable.class).run();
      return null;
    }).when(peripheralInteractor).startPreMovementInteractions(any(), any(), any());
    stdVehicleController.setTransportOrder(order);
    return commands;
  }

  @Test
  void shouldForwardPoseChangeToKernel() {
    Pose newPose = new Pose(new Triple(211, 391, 0), 7.5);
    vehicleModel.setPose(newPose);

    verify(vehicleService).updateVehiclePose(
        vehicle.getReference(),
        newPose
    );
  }

  @Test
  void shouldTransformPoseWhenUsingDifferentCoordinateSystems() {
    // The initial call to the transformer should have already been made during initialization.
    verify(poseTransformer, times(1)).apply(any(Pose.class));

    vehicleModel.setPose(new Pose(new Triple(211, 391, 0), Double.NaN));
    verify(poseTransformer, times(2)).apply(any(Pose.class));

    vehicleModel.setPose(new Pose(null, 33.0));
    verify(poseTransformer, times(3)).apply(any(Pose.class));
  }

  @Test
  void shouldForwardEnergyLevelChangeToKernel() {
    int newLevel = 80;
    vehicleModel.setEnergyLevel(newLevel);
    verify(vehicleService).updateVehicleEnergyLevel(
        vehicle.getReference(),
        newLevel
    );
  }

  @Test
  void shouldForwardLoadHandlingDevicesChangeToKernel() {
    List<LoadHandlingDevice> devices
        = List.of(new LoadHandlingDevice("MyLoadHandlingDevice", true));
    vehicleModel.setLoadHandlingDevices(devices);

    verify(vehicleService).updateVehicleLoadHandlingDevices(
        vehicle.getReference(),
        devices
    );
  }

  @Test
  void shouldForwardVehicleStateChangeToKernel() {
    vehicleModel.setState(Vehicle.State.EXECUTING);

    verify(vehicleService).updateVehicleState(
        vehicle.getReference(),
        Vehicle.State.EXECUTING
    );
  }

  @Test
  void shouldForwardEventToBus() {
    final String adapterName = "myAdapter";
    final String eventString = "myString";
    final List<VehicleCommAdapterEvent> eventsReceived = new ArrayList<>();

    eventBus.subscribe(event -> {
      if (event instanceof VehicleCommAdapterEvent) {
        eventsReceived.add((VehicleCommAdapterEvent) event);
      }
    });

    vehicleModel.publishEvent(new VehicleCommAdapterEvent(adapterName, eventString));

    assertEquals(1, eventsReceived.size());
    VehicleCommAdapterEvent event = eventsReceived.get(0);
    assertEquals(eventString, event.getAppendix());
  }

  // Test cases for implementation of interface VehicleController start here.
  @Test
  void shouldHaveIdempotentEnabledState() {
    stdVehicleController.initialize();
    assertTrue(stdVehicleController.isInitialized());
    stdVehicleController.initialize();
    assertTrue(stdVehicleController.isInitialized());
    stdVehicleController.terminate();
    assertFalse(stdVehicleController.isInitialized());
    stdVehicleController.terminate();
    assertFalse(stdVehicleController.isInitialized());
  }

  @Test
  void shouldSetClaimOnNewTransportOrder() {
    Location location = dataObjectFactory.createLocation();

    Point dstPoint = dataObjectFactory.createPoint();
    Path stepPath = dataObjectFactory.createPath(dstPoint.getReference());
    List<Route.Step> steps = List.of(
        new Route.Step(stepPath, null, dstPoint, Vehicle.Orientation.FORWARD, 0, 1)
    );

    DriveOrder driveOrder = new DriveOrder(
        "some-order",
        new DriveOrder.Destination(location.getReference())
    )
        .withRoute(new Route(steps));

    TransportOrder transportOrder
        = new TransportOrder("some-transport-order", List.of(driveOrder))
            .withCurrentDriveOrderIndex(0);

    stdVehicleController.setTransportOrder(transportOrder);

    verify(scheduler).claim(eq(stdVehicleController), Mockito.any());
  }
}
