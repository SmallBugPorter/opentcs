// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opentcs.commadapter.vehicle.vda5050.CommAdapterConfiguration;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.common.UnsupportedPropertiesFilter;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.IncomingMessage;
import org.opentcs.commadapter.vehicle.vda5050.common.mqtt.MqttClientManager;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.ordermapping.UnsupportedPropertiesExtractor;
import org.opentcs.data.model.Vehicle;

/** Tests the production adapter's state decoding and outgoing MQTT message, without a broker. */
class Version21WireTest {
  @Test
  void initialVehicleStateProducesVersion21DownloadActionOnCorrectTopic() {
    Vehicle vehicle = new Vehicle("agv-001")
        .withProperty("vda5050:version", "2.1")
        .withProperty("vda5050:interfaceName", "uagv")
        .withProperty("vda5050:manufacturer", "dx")
        .withProperty("vda5050:serialNumber", "agv-001")
        .withProperty("vda5050:mapId", "living_room")
        .withProperty(MapPreparation.MAP_VERSION, "1")
        .withProperty(MapPreparation.MAP_DOWNLOAD_LINK, "http://localhost/living_room-v1.xml")
        .withProperty(MapPreparation.AUTO_PREPARE, "true");
    CommAdapterComponentsFactory factory = mock(CommAdapterComponentsFactory.class);
    when(factory.createUnsupportedPropertiesFilter(any(), any()))
        .thenReturn(new UnsupportedPropertiesFilter(vehicle, ignored -> Map.of()));
    when(factory.createMovementCommandManager(vehicle))
        .thenReturn(mock(MovementCommandManager.class));
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    doAnswer(call -> {
      call.getArgument(0, Runnable.class).run();
      return null;
    }).when(executor).execute(any(Runnable.class));
    IncomingMessageFilter filter = mock(IncomingMessageFilter.class);
    when(filter.accept(any())).thenReturn(true);
    MqttClientManager mqtt = mock(MqttClientManager.class);
    JsonBinder binder = new JsonBinder();
    CommAdapterImpl adapter = new CommAdapterImpl(
        vehicle, MqttSetting.forVehicle(vehicle).orElseThrow(), new MessageValidator("2.1"),
        executor, factory, mqtt, filter, binder, mock(CommAdapterConfiguration.class),
        mock(UnsupportedPropertiesExtractor.class)
    );
    adapter.onIncomingMessage(new IncomingMessage("uagv/v2/dx/agv-001/state", """
        {"headerId":1,"timestamp":"2026-01-01T00:00:00Z","version":"2.1.0",
         "manufacturer":"dx","serialNumber":"agv-001","orderId":"","orderUpdateId":0,
         "lastNodeId":"","lastNodeSequenceId":0,"nodeStates":[],"edgeStates":[],
         "driving":false,"actionStates":[],"batteryState":{"batteryCharge":75.5,"charging":false},
         "operatingMode":"AUTOMATIC","errors":[],
         "safetyState":{"eStop":"NONE","fieldViolation":false},
         "maps":[],"velocity":{"vx":0,"vy":0,"omega":0}}
        """));
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(mqtt).publish(
        eq("uagv/v2/dx/agv-001/instantActions"), any(), payload.capture(), eq(false)
    );
    JsonNode message = binder.fromJson(payload.getValue(), JsonNode.class);
    assertThat(message.path("version").asText()).isEqualTo("2.1.0");
    assertThat(message.path("actions").get(0).path("actionType").asText()).isEqualTo("downloadMap");
    assertThat(adapter.getProcessModel().getPosition()).isNull();
  }
}
