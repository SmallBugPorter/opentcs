// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.virtualvehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.opentcs.data.model.Path;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Pose;
import org.opentcs.data.model.Triple;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.util.MapValueExtractor;

class LoopbackRotationTest {

  @Test
  void rotationKeepsSourcePositionAndCommandUnacknowledgedThenStartsTravel()
      throws Exception {
    var fixture = fixture(0, true, 1);
    var tasks = fixture.tasks();
    var adapter = fixture.adapter();
    for (int i = 0; i < 5; i++) {
      tasks.remove().run();
    }
    assertEquals(22.5, adapter.getProcessModel().getPose().getOrientationAngle(), 1e-9);
    assertTrue(adapter.getProcessModel().isRotating());
    assertFalse(adapter.getProcessModel().getVelocityController().hasWayEntries());
    assertEquals("a", adapter.getProcessModel().getPosition());
    assertTrue(adapter.pending());
    adapter.onVehiclePaused(true);
    for (int i = 0; i < 20; i++) {
      tasks.remove().run();
    }
    assertEquals(22.5, adapter.getProcessModel().getPose().getOrientationAngle(), 1e-9);
    assertTrue(adapter.pending());
    adapter.onVehiclePaused(false);
    for (int i = 0; i < 10; i++) {
      tasks.remove().run();
    }
    assertFalse(adapter.getProcessModel().isRotating());
    assertEquals(90, adapter.getProcessModel().getPose().getOrientationAngle(), 1e-9);
    assertTrue(adapter.getProcessModel().getVelocityController().hasWayEntries());
    assertEquals("a", adapter.getProcessModel().getPosition());
    assertTrue(adapter.pending());
  }

  @Test
  void cancellationDoesNotExecuteMovementOrAdvanceHeading()
      throws Exception {
    var f = fixture(0, true, 1);
    for (int i = 0; i < 5; i++) {
      f.tasks().remove().run();
    }
    f.adapter().clearCommandQueue();
    f.tasks().remove().run();
    assertFalse(f.adapter().pending());
    assertFalse(f.adapter().getProcessModel().isRotating());
    assertFalse(f.adapter().getProcessModel().getVelocityController().hasWayEntries());
    assertEquals(22.5, f.adapter().getProcessModel().getPose().getOrientationAngle(), 1e-9);
  }

  @Test
  void straightUnknownHeadingAndOptOutDoNotRotate()
      throws Exception {
    for (var f : List.of(
        fixture(90, true, 1), fixture(Double.NaN, true, 1), fixture(0, false, 1)
    )) {
      assertFalse(f.adapter().getProcessModel().isRotating());
      assertTrue(f.adapter().getProcessModel().getVelocityController().hasWayEntries());
    }
  }

  @Test
  void rotationUsesSimulationTimeFactor()
      throws Exception {
    var f = fixture(0, true, 2);
    for (int i = 0; i < 7; i++) {
      f.tasks().remove().run();
    }
    assertTrue(f.adapter().getProcessModel().isRotating());
    f.tasks().remove().run();
    assertFalse(f.adapter().getProcessModel().isRotating());
    assertEquals(90, f.adapter().getProcessModel().getPose().getOrientationAngle(), 1e-9);
  }

  @Test
  void travelPublishesIntermediatePoseButAcknowledgesOnlyArrival()
      throws Exception {
    var f = fixture(90, true, 1);
    var model = f.adapter().getProcessModel();
    f.tasks().remove().run();
    assertTrue(model.getPose().getPosition().getY() > 0);
    assertTrue(model.getPose().getPosition().getY() < 1000);
    assertEquals("a", model.getPosition());
    assertTrue(f.adapter().pending());
    assertEquals(90, model.getPose().getOrientationAngle());
    var pausedPose = model.getPose();
    f.adapter().onVehiclePaused(true);
    var events = new java.util.concurrent.atomic.AtomicInteger();
    model.addPropertyChangeListener(event -> events.incrementAndGet());
    for (int i = 0; i < 10; i++) {
      f.tasks().remove().run();
    }
    assertEquals(pausedPose, model.getPose());
    assertEquals(0, events.get());
    f.adapter().onVehiclePaused(false);
    for (int i = 0; i < 100 && f.adapter().pending(); i++) {
      f.tasks().remove().run();
    }
    assertFalse(f.adapter().pending());
    assertEquals("b", model.getPosition());
    assertEquals(new Triple(0, 1000, 0), model.getPose().getPosition());
  }

  @Test
  void travelInterpolatesAllCoordinatesUsingPathDistanceAndTimeFactor()
      throws Exception {
    var normal = fixture(0, false, 1, new Triple(-2000, 3000, 4000));
    var fast = fixture(0, false, 2, new Triple(-2000, 3000, 4000));
    normal.tasks().remove().run();
    fast.tasks().remove().run();
    var model = normal.adapter().getProcessModel();
    long distance = model.getVelocityController().getCurrentPosition();
    assertTrue(distance > 0);
    assertEquals(
        new Triple(-2 * distance, 3 * distance, 4 * distance),
        model.getPose().getPosition()
    );
    assertTrue(
        fast.adapter().getProcessModel().getPose().getPosition().getY()
            > model.getPose().getPosition().getY()
    );
    for (int i = 0; i < 100 && normal.adapter().pending(); i++) {
      normal.tasks().remove().run();
    }
    assertFalse(normal.adapter().pending());
    assertEquals(new Triple(-2000, 3000, 4000), model.getPose().getPosition());
  }

  private Fixture fixture(double heading, boolean enabled, double factor)
      throws Exception {
    return fixture(heading, enabled, factor, new Triple(0, 1000, 0));
  }

  private Fixture fixture(double heading, boolean enabled, double factor, Triple destination)
      throws Exception {
    var tasks = new ArrayDeque<Runnable>();
    var executor = mock(ScheduledExecutorService.class);
    doAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; })
        .when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    var config = mock(VirtualVehicleConfiguration.class);
    when(config.commandQueueCapacity()).thenReturn(2);
    when(config.rechargeOperation()).thenReturn("CHARGE");
    when(config.simulationTimeFactor()).thenReturn(factor);
    var vehicle = new Vehicle("test").withProperty(
        "loopback:rotationEnabled", Boolean.toString(enabled)
    );
    var adapter = new TestAdapter(config, vehicle, executor);
    var a = new Point("a").withPose(new Pose(new Triple(0, 0, 0), 0));
    var b = new Point("b").withPose(new Pose(destination, 0));
    var path = new Path("ab", a.getReference(), b.getReference()).withLength(1000);
    var step = new Route.Step(path, a, b, Vehicle.Orientation.FORWARD, 0, 1);
    var drive = new DriveOrder("drive", new DriveOrder.Destination(b.getReference()));
    var order = new TransportOrder("order", List.of(drive));
    var cmd = new MovementCommand(
        order, drive, step, "MOVE", null, true, null, b, "MOVE", Map.of()
    );
    adapter.getProcessModel().setPosition("a");
    adapter.getProcessModel().setPose(new Pose(new Triple(0, 0, 0), heading));
    adapter.start(cmd);
    return new Fixture(adapter, tasks);
  }

  private record Fixture(TestAdapter adapter, ArrayDeque<Runnable> tasks) {}

  private static class TestAdapter
      extends
        LoopbackCommunicationAdapter {

    TestAdapter(
        VirtualVehicleConfiguration config, Vehicle vehicle, ScheduledExecutorService executor
    ) {
      super(config, mock(MapValueExtractor.class), vehicle, executor);
    }

    @Override
    public boolean isInitialized() {
      return true;
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    void start(MovementCommand command)
        throws Exception {
      getSentCommands().add(command);
      var method = LoopbackCommunicationAdapter.class.getDeclaredMethod(
          "startVehicleSimulation", MovementCommand.class
      );
      method.setAccessible(true);
      method.invoke(this, command);
    }

    boolean pending() {
      return !getSentCommands().isEmpty();
    }
  }
}
