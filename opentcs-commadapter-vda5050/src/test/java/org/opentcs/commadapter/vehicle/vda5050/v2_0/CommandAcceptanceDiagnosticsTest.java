// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterConfiguration;
import org.opentcs.commadapter.vehicle.vda5050.common.DistanceInAdvanceController;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.common.UnsupportedPropertiesFilter;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.MqttClientManager;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.ordermapping.UnsupportedPropertiesExtractor;
import org.opentcs.data.model.Path;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.BasicVehicleCommAdapter;
import org.opentcs.drivers.vehicle.MovementCommand;

class CommandAcceptanceDiagnosticsTest {
  private final List<LogRecord> records = new ArrayList<>();
  private final Logger logger = Logger.getLogger(BasicVehicleCommAdapter.class.getName());
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  };
  private Level originalLevel;

  @BeforeEach
  void captureWarningsAtNormalLogLevel() {
    originalLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    logger.addHandler(handler);
  }

  @AfterEach
  void restoreLogger() {
    logger.removeHandler(handler);
    logger.setLevel(originalLevel);
  }

  @Test
  void rejectsUnpreparedMapWithVehicleAndOrderAtWarningLevel() {
    CommAdapterImpl adapter = adapter(true);
    assertThat(adapter.enqueueCommand(command(1000))).isFalse();
    assertThat(adapter.getUnsentCommands()).isEmpty();
    assertThat(records).singleElement().satisfies(record -> {
      assertThat(record.getLevel()).isEqualTo(Level.WARNING);
      assertThat(record.getMessage()).contains(
          "COMMAND_REJECTED", "vehicle=agv-test", "order=sort-test", "target=destination",
          "mapReady=false", "mapStatus=WAITING_FOR_STATE", "stateAgeMs=-1",
          "queueCapacityAvailable=true", "distanceAvailable=true"
      );
    });
  }

  @Test
  void rejectsFullQueueAndRetainsDecisionAfterQueueChanges() {
    CommAdapterImpl adapter = adapter(false);
    MovementCommand first = command(1);
    assertThat(adapter.enqueueCommand(first)).isTrue();
    assertThat(adapter.enqueueCommand(command(1))).isTrue();
    assertThat(adapter.enqueueCommand(command(1))).isTrue();
    assertThat(records).isEmpty();
    assertThat(adapter.enqueueCommand(command(1))).isFalse();
    assertThat(adapter.getUnsentCommands()).hasSize(3);
    String rejected = adapter.getCommandAcceptanceDiagnostics();
    assertThat(rejected).contains("queueCapacityAvailable=false", "unsent=3", "capacity=3");
    adapter.getUnsentCommands().remove(first);
    assertThat(adapter.getCommandAcceptanceDiagnostics()).isEqualTo(rejected);
    assertThat(adapter.canAcceptNextCommand()).isTrue();
    assertThat(adapter.getCommandAcceptanceDiagnostics()).contains("queueCapacityAvailable=true");
  }

  @Test
  void rejectsAtDistanceLimitIncludingSentCommands() {
    CommAdapterImpl adapter = adapter(false);
    MovementCommand first = command(5000);
    assertThat(adapter.enqueueCommand(first)).isTrue();
    adapter.getUnsentCommands().remove(first);
    adapter.getSentCommands().add(first);
    assertThat(adapter.enqueueCommand(command(1))).isFalse();
    assertThat(adapter.getCommandAcceptanceDiagnostics()).contains(
        "mapReady=true", "queueCapacityAvailable=true", "sent=1",
        "distanceAvailable=false", "queuedDistanceMm=5000", "maxDistanceInAdvanceMm=5000"
    );
    assertThat(adapter.getUnsentCommands()).isEmpty();
  }

  private CommAdapterImpl adapter(boolean prepareMap) {
    Vehicle vehicle = new Vehicle("agv-test")
        .withProperty("vda5050:version", "2.1")
        .withProperty("vda5050:autoPrepareMap", Boolean.toString(prepareMap))
        .withProperty("vda5050:mapId", "map")
        .withProperty("vda5050:mapVersion", "1")
        .withProperty("vda5050:mapDownloadLink", "http://localhost/map.xml");
    CommAdapterComponentsFactory factory = mock(CommAdapterComponentsFactory.class);
    when(factory.createDistanceInAdvanceController(anyLong()))
        .thenReturn(new DistanceInAdvanceController(5000));
    when(factory.createUnsupportedPropertiesFilter(any(), any()))
        .thenReturn(new UnsupportedPropertiesFilter(vehicle, ignored -> Map.of()));
    MqttSetting mqtt = mock(MqttSetting.class);
    when(mqtt.topicNamePrefix()).thenReturn("uagv/v2/test/agv-test");
    return new CommAdapterImpl(
        vehicle, mqtt, mock(MessageValidator.class),
        mock(ScheduledExecutorService.class), factory, mock(MqttClientManager.class),
        mock(IncomingMessageFilter.class), new JsonBinder(), mock(CommAdapterConfiguration.class),
        mock(UnsupportedPropertiesExtractor.class)
    );
  }

  private MovementCommand command(long length) {
    Point source = new Point("source");
    Point destination = new Point("destination");
    Path path = new Path("path", source.getReference(), destination.getReference()).withLength(
        length
    );
    return new MovementCommand(
        new TransportOrder("sort-test", List.of()),
        new DriveOrder("drive-test", new DriveOrder.Destination(destination.getReference())),
        new Route.Step(path, source, destination, Vehicle.Orientation.FORWARD, 0, 1),
        "NOP", null, true, null, destination, "NOP", Map.of()
    );
  }
}
