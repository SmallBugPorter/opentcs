// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.beans.PropertyChangeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterConfiguration;
import org.opentcs.commadapter.vehicle.vda5050.common.DistanceInAdvanceController;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.common.UnsupportedPropertiesFilter;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.IncomingMessage;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.MqttClientManager;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.AgvPosition;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.Connection;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.ConnectionState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Order;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.BatteryState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.EStop;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.OperatingMode;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.SafetyState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.visualization.Visualization;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.ordermapping.UnsupportedPropertiesExtractor;
import org.opentcs.data.model.Pose;
import org.opentcs.data.model.Vehicle;
import org.opentcs.drivers.vehicle.VehicleProcessModel;

class PositionSynchronizationTest {
  private final JsonBinder binder = new JsonBinder();
  private final List<PropertyChangeEvent> events = new ArrayList<>();
  private CommAdapterImpl adapter;
  private IncomingMessageFilter filter;
  private MqttClientManager client;
  private String kernelPosition;

  @BeforeEach
  void setUp() {
    Vehicle vehicle = new Vehicle("agv-0008")
        .withProperty("vda5050:version", "2.1")
        .withProperty("vda5050:autoPrepareMap", "false");
    CommAdapterComponentsFactory factory = mock(CommAdapterComponentsFactory.class);
    when(factory.createDistanceInAdvanceController(anyLong()))
        .thenReturn(new DistanceInAdvanceController(Long.MAX_VALUE));
    when(factory.createMovementCommandManager(any()))
        .thenReturn(mock(MovementCommandManager.class));
    when(factory.createUnsupportedPropertiesFilter(any(), any()))
        .thenReturn(new UnsupportedPropertiesFilter(vehicle, ignored -> Map.of()));
    MqttSetting mqtt = mock(MqttSetting.class);
    when(mqtt.topicNamePrefix()).thenReturn("uagv/v2/dx/agv-0008");
    when(mqtt.stateTopicName()).thenReturn("state");
    when(mqtt.connectionTopicName()).thenReturn("connection");
    when(mqtt.visualizationTopicName()).thenReturn("visualization");
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    doAnswer(invocation -> {
      invocation.getArgument(0, Runnable.class).run();
      return null;
    }).when(executor).execute(any(Runnable.class));
    filter = mock(IncomingMessageFilter.class);
    when(filter.accept(any())).thenReturn(true);
    client = mock(MqttClientManager.class);
    adapter = new CommAdapterImpl(
        vehicle, mqtt, mock(MessageValidator.class), executor, factory, client, filter,
        new JsonBinder(), mock(CommAdapterConfiguration.class),
        mock(UnsupportedPropertiesExtractor.class)
    );
    adapter.getProcessModel().addPropertyChangeListener(event -> {
      events.add(event);
      // Model the kernel's separate position store. Coordinate resolution
      // changes this store, but does not change the adapter's cached node.
      if (event.getPropertyName().equals(VehicleProcessModel.Attribute.POSITION.name())) {
        kernelPosition = (String) event.getNewValue();
      }
      else if (event.getPropertyName().equals(
          VehicleProcessModel.Attribute.POSITION_RESOLUTION_REQUESTED.name()
      )) {
        Pose pose = (Pose) event.getNewValue();
        kernelPosition = pose.getPosition().getX() == 0 ? "Point-0001" : "Point-0113";
      }
    });
  }

  @Test
  void neverUsesUninitializedStartupCoordinatesAsARealPoint() {
    receive(state("", 0, false));
    assertThat(kernelPosition).isNull();
    assertThat(positionEvents()).isEmpty();
    assertThat(resolutionEvents()).isEmpty();
    assertThat(adapter.getProcessModel().getState()).isEqualTo(Vehicle.State.UNAVAILABLE);
    assertThat(adapter.canAcceptNextCommand()).isFalse();
    assertThat(adapter.getCommandAcceptanceDiagnostics()).contains("localizationReady=false");
    receive(state("Point-0113", 4, true));
    assertThat(kernelPosition).isEqualTo("Point-0113");
    assertThat(adapter.getProcessModel().getState()).isEqualTo(Vehicle.State.IDLE);
    assertThat(adapter.canAcceptNextCommand()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Point-0001"})
  void retainsKnownPositionAndResourcesDuringLocalizationLoss(String reportedNode) {
    receive(state("Point-0113", 4, true));
    Pose knownPose = adapter.getProcessModel().getPose();
    events.clear();
    receive(state(reportedNode, 0, false));
    assertThat(kernelPosition).isEqualTo("Point-0113");
    assertThat(adapter.getProcessModel().getPosition()).isEqualTo("Point-0113");
    assertThat(adapter.getProcessModel().getPose()).isEqualTo(knownPose);
    assertThat(positionEvents()).isEmpty();
    assertThat(resolutionEvents()).isEmpty();

    receive(state("Point-0113", 4, true));
    assertThat(positionEvents()).singleElement().satisfies(
        event -> assertThat(event.getNewValue()).isEqualTo("Point-0113")
    );
  }

  @Test
  void authoritativeNodeOverridesCoordinateResolutionEvenWhenCachedNodeIsUnchanged() {
    receive(state("Point-0113", 4, true));
    receive(state("", 0, true));
    assertThat(kernelPosition).isEqualTo("Point-0001");
    assertThat(adapter.getProcessModel().getPosition()).isEqualTo("Point-0113");
    events.clear();

    receive(state("Point-0113", 4, true));
    assertThat(kernelPosition).isEqualTo("Point-0113");
    assertThat(positionEvents()).singleElement().satisfies(
        event -> assertThat(event.getNewValue()).isEqualTo("Point-0113")
    );
    assertThat(resolutionEvents()).isEmpty();
  }

  @Test
  void reconnectResynchronizesSameNodeWithoutClearingPosition() {
    receive(state("Point-0113", 4, true));
    events.clear();
    adapter.onIncomingMessage(
        new IncomingMessage(
            "connection", binder.toJson(
                new Connection(
                    1L, Instant.now(), "2.1.0", "dx", "agv-0008", ConnectionState.ONLINE
                )
            )
        )
    );
    assertThat(positionEvents()).isEmpty();
    receive(state("Point-0113", 4, true));
    receive(state("Point-0113", 4, true));
    assertThat(positionEvents()).singleElement().satisfies(
        event -> assertThat(event.getNewValue()).isEqualTo("Point-0113")
    );
  }

  @Test
  void repeatedNormalStatesDoNotRepeatedlyAllocateTheSamePoint() {
    receive(state("Point-0113", 4, true));
    events.clear();
    receive(state("Point-0113", 4, true));
    receive(state("Point-0113", 4, true));
    assertThat(positionEvents()).isEmpty();
    assertThat(resolutionEvents()).isEmpty();
  }

  @Test
  void continuesSupportingLogicalNodesWithoutOptionalCoordinates() {
    State state = state("Point-0113", 4, true).setAgvPosition(null);
    receive(state);
    assertThat(kernelPosition).isEqualTo("Point-0113");
    assertThat(adapter.canAcceptNextCommand()).isTrue();
  }

  @Test
  void rejectsOutdatedPositionMessagesBeforeChangingThePosition() {
    receive(state("Point-0113", 4, true));
    events.clear();
    when(filter.accept(any())).thenReturn(false);
    receive(state("Point-0001", 0, false));
    assertThat(kernelPosition).isEqualTo("Point-0113");
    assertThat(positionEvents()).isEmpty();
    assertThat(resolutionEvents()).isEmpty();
    assertThat(adapter.canAcceptNextCommand()).isTrue();
  }

  @Test
  void visualizationCannotReplacePoseWithUninitializedCoordinates() {
    receive(state("Point-0113", 4, true));
    Pose knownPose = adapter.getProcessModel().getPose();
    adapter.onIncomingMessage(
        new IncomingMessage(
            "visualization", binder.toJson(
                new Visualization(
                    1L, Instant.now(), "2.1.0", "dx", "agv-0008"
                ).setAgvPosition(new AgvPosition(0.0, 0.0, 0.0, "sorting_2", false))
            )
        )
    );
    assertThat(adapter.getProcessModel().getPose()).isEqualTo(knownPose);
    assertThat(kernelPosition).isEqualTo("Point-0113");
  }

  @Test
  void holdsOrderTransmissionWhileLocalizationIsUninitialized() {
    receive(state("", 0, false));
    adapter.sendOrder(new Order("order", 0L, List.of(), List.of()));
    verifyNoInteractions(client);
  }

  private State state(String node, double x, boolean initialized) {
    return new State(
        1L, Instant.now(), "2.1.0", "dx", "agv-0008",
        "", 0L, node, 0L, List.of(), List.of(), false, List.of(),
        new BatteryState(44.0, false), OperatingMode.AUTOMATIC, List.of(),
        new SafetyState(EStop.NONE, false)
    ).setAgvPosition(new AgvPosition(x, 0.0, 0.0, "sorting_2", initialized));
  }

  private void receive(State state) {
    adapter.onIncomingMessage(new IncomingMessage("state", binder.toJson(state)));
  }

  private List<PropertyChangeEvent> positionEvents() {
    return events.stream()
        .filter(
            event -> event.getPropertyName().equals(VehicleProcessModel.Attribute.POSITION.name())
        )
        .toList();
  }

  private List<PropertyChangeEvent> resolutionEvents() {
    return events.stream()
        .filter(
            event -> event.getPropertyName().equals(
                VehicleProcessModel.Attribute.POSITION_RESOLUTION_REQUESTED.name()
            )
        )
        .toList();
  }
}
