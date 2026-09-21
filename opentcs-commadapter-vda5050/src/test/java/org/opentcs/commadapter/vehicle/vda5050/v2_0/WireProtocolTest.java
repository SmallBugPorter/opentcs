// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterConfiguration;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterFactoryImpl;
import org.opentcs.commadapter.vehicle.vda5050.Vda5050CommAdapterFactory;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.common.UnsupportedPropertiesFilter;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.IncomingMessage;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.MqttClientManager;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.QualityOfService;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.Action;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.BlockingType;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.NodePosition;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.Connection;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.ConnectionState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Corridor;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Edge;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Node;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Order;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionStatus;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.OperatingMode;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.visualization.Visualization;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.ordermapping.UnsupportedPropertiesExtractor;
import org.opentcs.data.model.Vehicle;
import org.opentcs.drivers.vehicle.MovementCommand;

/** Tests the version boundary using real schemas, serialization and adapter MQTT callbacks. */
class WireProtocolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonBinder binder = new JsonBinder();

  @ParameterizedTest
  @EnumSource(ProtocolVersion.class)
  void sendsOrdersAndInstantActionsInSelectedWireFormat(ProtocolVersion version)
      throws Exception {
    Vehicle vehicle = vehicle(version);
    MqttClientManager mqtt = mock(MqttClientManager.class);
    CommAdapterImpl adapter = adapter(vehicle, mqtt);
    Node first = new Node("P1", 0L, true, List.of());
    first.setNodePosition(new NodePosition(1.0, 2.0, "map").setAllowedDeviationXY(0.2));
    Node last = new Node("P2", 2L, true, List.of());
    Edge edge = new Edge("E1", 1L, true, "P1", "P2", List.of());
    edge.setMaxSpeed(0.5);
    edge.setCorridor(new Corridor(0.25, 0.5, "KINEMATIC_CENTER"));
    edge.setRotationAllowed(false);
    Order order = new Order("order-1", 0L, List.of(first, last), List.of(edge));
    adapter.sendOrder(order);
    String prefix = "uagv/v" + version.major() + "/test/001";
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mqtt).publish(
        eq(prefix + "/order"), eq(QualityOfService.AT_MOST_ONCE),
        body.capture(), eq(false)
    );
    new MessageValidator(version).validate(body.getValue(), Order.class);
    JsonNode wire = mapper.readTree(body.getValue());
    assertThat(wire.path("version").asText()).isEqualTo(version.wireVersion());
    assertThat(wire.path("manufacturer").asText()).isEqualTo("test");
    assertThat(wire.at("/edges/0").has("corridor")).isEqualTo(version != ProtocolVersion.V2_0);
    if (version != ProtocolVersion.V2_0) {
      assertThat(wire.at("/edges/0/corridor/leftWidth").asDouble()).isEqualTo(0.25);
    }
    if (version == ProtocolVersion.V3_0) {
      assertThat(wire.at("/nodes/0/nodePosition/allowedDeviationXY/a").asDouble()).isEqualTo(0.2);
      assertThat(wire.at("/edges/0/maximumSpeed").asDouble()).isEqualTo(0.5);
      assertThat(wire.at("/edges/0/reachOrientationBeforeEntering").asBoolean()).isTrue();
      assertThat(wire.at("/edges/0").has("startNodeId")).isFalse();
    }
    else {
      assertThat(wire.at("/nodes/0/nodePosition/allowedDeviationXY").asDouble()).isEqualTo(0.2);
      assertThat(wire.at("/edges/0/maxSpeed").asDouble()).isEqualTo(0.5);
    }
    adapter.sendInstantAction(
        new InstantActions().setActions(
            List.of(
                new Action("initPosition", "init-1", BlockingType.HARD)
            )
        )
    );
    verify(mqtt).publish(
        eq(prefix + "/instantActions"), eq(QualityOfService.AT_MOST_ONCE),
        body.capture(), eq(false)
    );
    new MessageValidator(version).validate(body.getValue(), InstantActions.class);
    wire = mapper.readTree(body.getValue());
    assertThat(wire.at("/actions/0/actionType").asText())
        .isEqualTo(version == ProtocolVersion.V3_0 ? "initializePosition" : "initPosition");
    assertThat(wire.at("/actions/0/blockingType").asText())
        .isEqualTo(version == ProtocolVersion.V3_0 ? "NONE" : "HARD");
  }

  @ParameterizedTest
  @EnumSource(ProtocolVersion.class)
  void receivesConnectionAndStateViaAdapter(ProtocolVersion version)
      throws Exception {
    CommAdapterImpl adapter = adapter(vehicle(version), mock(MqttClientManager.class));
    String prefix = "uagv/v" + version.major() + "/test/001";
    ObjectNode state = stateJson(version);
    ObjectNode connection = mapper.createObjectNode();
    for (String key : List.of("headerId", "timestamp", "version", "manufacturer", "serialNumber")) {
      connection.set(key, state.get(key));
    }
    connection.put("connectionState", "ONLINE");
    adapter.onIncomingMessage(new IncomingMessage(prefix + "/connection", connection.toString()));
    adapter.onIncomingMessage(new IncomingMessage(prefix + "/state", state.toString()));
    assertThat(adapter.getProcessModel().isCommAdapterConnected()).isTrue();
    assertThat(adapter.getProcessModel().getEnergyLevel()).isEqualTo(75);
    assertThat(adapter.getProcessModel().getPosition()).isEqualTo("P1");
    assertThat(adapter.getProcessModel().getState()).isEqualTo(Vehicle.State.IDLE);
    state.put("version", "9.0.0");
    state.withObject("/" + (version == ProtocolVersion.V3_0 ? "powerSupply" : "batteryState"))
        .put(version == ProtocolVersion.V3_0 ? "stateOfCharge" : "batteryCharge", 20);
    adapter.onIncomingMessage(new IncomingMessage(prefix + "/state", state.toString()));
    assertThat(adapter.getProcessModel().getEnergyLevel()).isEqualTo(75);
  }

  @ParameterizedTest
  @EnumSource(ProtocolVersion.class)
  void disabledVersionsCannotLeakThroughSharedFactory(ProtocolVersion version) {
    CommAdapterConfiguration config = mock(CommAdapterConfiguration.class);
    when(config.enabledVersions()).thenReturn(List.of("1.1"));
    Vda5050CommAdapterFactory shared = mock(Vda5050CommAdapterFactory.class);
    CommAdapterFactoryImpl factory = new CommAdapterFactoryImpl(
        config, mock(Vda5050CommAdapterFactory.class), shared
    );
    assertThat(factory.providesAdapterFor(vehicle(version))).isFalse();
    assertThat(factory.getAdapterFor(vehicle(version))).isNull();
    verify(shared, never()).getAdapterFor(any());
    when(config.enabledVersions()).thenReturn(List.of(version.configurationValue()));
    factory = new CommAdapterFactoryImpl(config, mock(Vda5050CommAdapterFactory.class), shared);
    when(shared.providesAdapterFor(any())).thenReturn(true);
    assertThat(factory.providesAdapterFor(vehicle(version))).isTrue();
    factory.getAdapterFor(vehicle(version));
    verify(shared).getAdapterFor(vehicle(version));
    for (ProtocolVersion other : ProtocolVersion.values()) {
      if (other != version) {
        assertThat(factory.providesAdapterFor(vehicle(other))).isFalse();
      }
    }
  }

  @Test
  void cancelWaitsForTerminalInstantActionStateBeforeNextOrder()
      throws Exception {
    List<Order> sentOrders = new ArrayList<>();
    List<InstantActions> sentActions = new ArrayList<>();
    MessageResponseMatcher matcher = new MessageResponseMatcher(
        "test", sentOrders::add, sentActions::add, ignored -> {}, 0
    );
    ObjectNode state = stateJson(ProtocolVersion.V3_0);
    matcher.onStateMessage(readState(state));
    matcher.enqueueAction(
        new InstantActions().setActions(
            List.of(
                new Action("cancelOrder", "cancel-1", BlockingType.NONE)
            )
        )
    );
    matcher.enqueueCommand(
        new Order("next", 0L, List.of(), List.of()), mock(MovementCommand.class)
    );
    ObjectNode action = state.withArray("instantActionStates").addObject();
    action.put("actionId", "cancel-1").put("actionStatus", "RUNNING");
    matcher.onStateMessage(readState(state));
    assertThat(sentOrders).isEmpty();
    assertThat(sentActions).hasSize(1);
    action.put("actionStatus", "FINISHED");
    matcher.onStateMessage(readState(state));
    assertThat(sentOrders).hasSize(1);
  }

  @Test
  void validatesNewRequiredFieldsAndBlockingRules()
      throws Exception {
    MessageValidator validator = new MessageValidator(ProtocolVersion.V3_0);
    ObjectNode state = stateJson(ProtocolVersion.V3_0);
    state.remove("instantActionStates");
    assertThrows(
        IllegalArgumentException.class, () -> validator.validate(state.toString(), State.class)
    );
    ObjectNode wrongBattery = stateJson(ProtocolVersion.V3_0);
    wrongBattery.withObject("/powerSupply").put("stateOfCharge", 101);
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(wrongBattery.toString(), State.class)
    );
    ObjectNode instant = mapper.createObjectNode();
    for (String key : List.of("headerId", "timestamp", "version", "manufacturer", "serialNumber")) {
      instant.set(key, state.get(key));
    }
    instant.putArray("actions").addObject().put("actionType", "cancelOrder")
        .put("actionId", "cancel").put("blockingType", "HARD");
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(instant.toString(), InstantActions.class)
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {"STARTUP", "INTERVENED", "TEACH_IN"})
  void newOperatingModesDoNotBecomeIdle(String mode)
      throws Exception {
    ObjectNode state = stateJson(ProtocolVersion.V3_0);
    state.put("operatingMode", mode);
    State parsed = readState(state);
    assertThat(StateMappings.toVehicleState(parsed)).isEqualTo(Vehicle.State.UNAVAILABLE);
    assertThat(parsed.getOperatingMode()).isEqualTo(
        mode.equals("TEACH_IN") ? OperatingMode.TEACHIN : OperatingMode.valueOf(mode)
    );
  }

  @Test
  void retriableActionsRemainPendingAndCriticalErrorsStopExecution()
      throws Exception {
    ObjectNode state = stateJson(ProtocolVersion.V3_0);
    state.withArray("actionStates").addObject().put("actionId", "retry-1")
        .put("actionStatus", "RETRIABLE");
    State parsed = readState(state);
    assertThat(parsed.getActionStates().getFirst().getActionStatus()).isEqualTo(
        ActionStatus.RETRIABLE
    );
    assertThat(StateMappings.toVehicleState(parsed)).isEqualTo(Vehicle.State.EXECUTING);
    state.withArray("errors").addObject().put("errorType", "OUTSIDE_OF_CORRIDOR")
        .put("errorLevel", "CRITICAL");
    assertThat(StateMappings.toVehicleState(readState(state))).isEqualTo(Vehicle.State.ERROR);
  }

  @Test
  void acceptsV3VisualizationAndConnectionStates()
      throws Exception {
    ObjectNode visualization = stateJson(ProtocolVersion.V3_0);
    visualization.put("referenceStateHeaderId", 1);
    new MessageValidator(ProtocolVersion.V3_0).validate(
        visualization.toString(), Visualization.class
    );
    Visualization parsed = binder.fromJson(
        new WireMessageMapper(ProtocolVersion.V3_0).fromWire(visualization.toString()),
        Visualization.class
    );
    assertThat(parsed.getAgvPosition().getX()).isEqualTo(1.5);
    for (String connectionState : List.of("HIBERNATING", "CONNECTION_BROKEN")) {
      visualization.put("connectionState", connectionState);
      new MessageValidator(ProtocolVersion.V3_0).validate(
          visualization.toString(), Connection.class
      );
      Connection connection = binder.fromJson(
          new WireMessageMapper(ProtocolVersion.V3_0).fromWire(visualization.toString()),
          Connection.class
      );
      assertThat(connection.getConnectionState()).isEqualTo(
          connectionState.equals("HIBERNATING")
              ? ConnectionState.HIBERNATING : ConnectionState.CONNECTIONBROKEN
      );
    }
  }

  @Test
  void intervenedAllowsCancellationButDefersOtherInstantActions()
      throws Exception {
    List<InstantActions> sent = new ArrayList<>();
    MessageResponseMatcher matcher = new MessageResponseMatcher(
        "test", ignored -> {}, sent::add, ignored -> {}, 0
    );
    ObjectNode state = stateJson(ProtocolVersion.V3_0);
    state.put("operatingMode", "INTERVENED");
    matcher.onStateMessage(readState(state));
    matcher.enqueueAction(
        new InstantActions().setActions(
            List.of(
                new Action("cancelOrder", "cancel", BlockingType.NONE)
            )
        )
    );
    assertThat(sent).hasSize(1);
    matcher.clear();
    matcher.enqueueAction(
        new InstantActions().setActions(
            List.of(
                new Action("startPause", "pause", BlockingType.NONE)
            )
        )
    );
    assertThat(sent).hasSize(1);
    state.put("operatingMode", "AUTOMATIC");
    matcher.onStateMessage(readState(state));
    assertThat(sent).hasSize(2);
  }

  @Test
  void onlyWakeupIsPublishedWhileHibernating()
      throws Exception {
    MqttClientManager mqtt = mock(MqttClientManager.class);
    CommAdapterImpl adapter = adapter(vehicle(ProtocolVersion.V3_0), mqtt);
    ObjectNode connection = stateJson(ProtocolVersion.V3_0);
    connection.put("connectionState", "HIBERNATING");
    adapter.onIncomingMessage(
        new IncomingMessage("uagv/v3/test/001/connection", connection.toString())
    );
    assertThat(adapter.getProcessModel().isCommAdapterConnected()).isFalse();
    adapter.sendInstantAction(
        new InstantActions().setActions(
            List.of(
                new Action("startPause", "pause", BlockingType.NONE)
            )
        )
    );
    verify(mqtt, never()).publish(any(), any(), any(), eq(false));
    adapter.sendInstantAction(
        new InstantActions().setActions(
            List.of(
                new Action("stopHibernation", "wake", BlockingType.NONE)
            )
        )
    );
    verify(mqtt).publish(eq("uagv/v3/test/001/instantActions"), any(), any(), eq(false));
  }

  @Test
  void preservesCustomParametersAndConvertsLegacyInitializationCoordinates()
      throws Exception {
    JsonNode input = mapper.readTree("""
        {"actions": [
          {"actionType":"initPosition", "actionId":"init", "blockingType":"HARD",
           "actionParameters":[{"key":"x","value":"1.25"}]},
          {"actionType":"custom", "actionId":"custom", "blockingType":"NONE",
           "actionParameters":[{"key":"x","value":"unchanged"},
                               {"key":"actionDescription","value":{"maxSpeed":7}}]}
        ]}
        """);
    JsonNode wire = new WireMessageMapper(ProtocolVersion.V3_0).toWire(input);
    assertThat(wire.at("/actions/0/actionParameters/0/value").isNumber()).isTrue();
    assertThat(wire.at("/actions/0/actionParameters/0/value").asDouble()).isEqualTo(1.25);
    assertThat(wire.at("/actions/1/actionParameters")).isEqualTo(
        input.at("/actions/1/actionParameters")
    );
    assertThat(input.at("/actions/0/blockingType").asText()).isEqualTo("HARD");
  }

  @Test
  void acceptsV21MapsAndErrorHints()
      throws Exception {
    ObjectNode state = stateJson(ProtocolVersion.V2_1);
    state.putArray("maps").addObject().put("mapId", "map").put("mapVersion", "1")
        .put("mapStatus", "ENABLED");
    state.withArray("errors").addObject().put("errorType", "maintenance")
        .put("errorLevel", "WARNING").put("errorHint", "Check vehicle");
    new MessageValidator(ProtocolVersion.V2_1).validate(state.toString(), State.class);
    State parsed = binder.fromJson(
        new WireMessageMapper(ProtocolVersion.V2_1)
            .fromWire(state.toString()), State.class
    );
    assertThat(parsed.getErrors().getFirst().getErrorType()).isEqualTo("maintenance");
  }

  @ParameterizedTest
  @EnumSource(ProtocolVersion.class)
  void explicitTopicPrefixIsPreservedAndPatchVersionsAreAccepted(ProtocolVersion version) {
    Vehicle vehicle = vehicle(version).withProperty("vda5050:topicPrefix", "custom/robot");
    assertThat(MqttSetting.forVehicle(vehicle).orElseThrow().orderTopicName())
        .isEqualTo("custom/robot/order");
    String json = "{\"version\":\"" + version.configurationValue() + ".1\"}";
    assertThat(new WireMessageMapper(version).fromWire(json)).contains(
        version.configurationValue()
    );
    assertThat(
        new VehicleHasRequiredProperties().test(
            vehicle.withProperty("vda5050:version", "9.0")
        )
    ).isFalse();
  }

  private State readState(ObjectNode state) {
    new MessageValidator(ProtocolVersion.V3_0).validate(state.toString(), State.class);
    return binder.fromJson(
        new WireMessageMapper(ProtocolVersion.V3_0).fromWire(state.toString()), State.class
    );
  }

  private ObjectNode stateJson(ProtocolVersion version)
      throws IOException {
    String json;
    try (var stream = getClass().getResourceAsStream("state-3.0.json")) {
      json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (version != ProtocolVersion.V3_0) {
      json = new WireMessageMapper(ProtocolVersion.V3_0).fromWire(json);
    }
    ObjectNode state = (ObjectNode) mapper.readTree(json);
    state.put("version", version.wireVersion());
    if (version != ProtocolVersion.V3_0) {
      state.remove("instantActionStates");
    }
    return state;
  }

  private Vehicle vehicle(ProtocolVersion version) {
    return new Vehicle("test")
        .withProperty("vda5050:version", version.configurationValue())
        .withProperty("vda5050:interfaceName", "uagv")
        .withProperty("vda5050:manufacturer", "test")
        .withProperty("vda5050:serialNumber", "001");
  }

  private CommAdapterImpl adapter(Vehicle vehicle, MqttClientManager mqtt) {
    CommAdapterComponentsFactory components = mock(CommAdapterComponentsFactory.class);
    when(components.createUnsupportedPropertiesFilter(any(), any()))
        .thenReturn(new UnsupportedPropertiesFilter(vehicle, ignored -> Map.of()));
    when(components.createMovementCommandManager(any())).thenReturn(
        mock(MovementCommandManager.class)
    );
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(executor).execute(any(Runnable.class));
    ProtocolVersion version = ProtocolVersion.fromConfiguration(
        vehicle.getProperty("vda5050:version")
    )
        .orElseThrow();
    return new CommAdapterImpl(
        vehicle, MqttSetting.forVehicle(vehicle).orElseThrow(),
        new MessageValidator(version), executor, components, mqtt, new IncomingMessageFilter(),
        new JsonBinder(), mock(CommAdapterConfiguration.class), new UnsupportedPropertiesExtractor()
    );
  }
}
