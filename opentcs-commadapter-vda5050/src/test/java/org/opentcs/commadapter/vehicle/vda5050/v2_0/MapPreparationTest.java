// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.AgvPosition;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionStatus;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.MapState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.data.model.Vehicle;

class MapPreparationTest {
  private final JsonBinder binder = new JsonBinder();
  private final List<InstantActions> sent = new ArrayList<>();
  private final Vehicle vehicle = new Vehicle("agv-001")
      .withProperty("vda5050:version", "2.1")
      .withProperty("vda5050:mapId", "living_room")
      .withProperty(MapPreparation.MAP_VERSION, "1")
      .withProperty(MapPreparation.MAP_DOWNLOAD_LINK, "http://localhost/living_room-v1.xml")
      .withProperty(MapPreparation.AUTO_PREPARE, "true");

  @Test
  void downloadsEnablesAndWaitsForLocalization() {
    MapPreparation preparation = new MapPreparation(vehicle, sent::add);
    State state = state();
    preparation.onState(state);
    assertThat(preparation.isReady()).isFalse();
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().getActions().getFirst().getActionType()).isEqualTo("downloadMap");
    preparation.onState(state);
    assertThat(sent).hasSize(1);
    state.setActionStates(List.of(result(0, ActionStatus.FINISHED)));
    preparation.onState(state);
    assertThat(sent).hasSize(1); // FINISHED alone does not confirm the map inventory.
    state.setMaps(List.of(map(MapState.MapStatus.DISABLED)));
    preparation.onState(state);
    assertThat(sent).hasSize(2);
    assertThat(sent.getLast().getActions().getFirst().getActionType()).isEqualTo("enableMap");
    state.setMaps(List.of(map(MapState.MapStatus.ENABLED)));
    state.setActionStates(List.of(result(1, ActionStatus.FINISHED)));
    preparation.onState(state);
    assertThat(preparation.getStatus()).isEqualTo("WAITING_FOR_LOCALIZATION");
    state.setAgvPosition(new AgvPosition(5.0, 10.0, 0.0, "living_room", true));
    state.setLastNodeId("Point-0001");
    preparation.onState(state);
    assertThat(preparation.isReady()).isTrue();
    preparation.disconnected();
    assertThat(preparation.isReady()).isFalse();
  }

  @Test
  void failureRequiresExplicitRetry() {
    MapPreparation preparation = new MapPreparation(vehicle, sent::add);
    State state = state();
    preparation.onState(state);
    state.setActionStates(List.of(result(0, ActionStatus.FAILED)));
    preparation.onState(state);
    preparation.onState(state);
    assertThat(sent).hasSize(1);
    assertThat(preparation.getStatus()).startsWith("FAILED");
    preparation.retry();
    preparation.onState(state);
    assertThat(sent).hasSize(2);
    assertThat(sent.getFirst().getActions().getFirst().getActionId())
        .isNotEqualTo(sent.getLast().getActions().getFirst().getActionId());
  }

  @Test
  void installedMapSkipsDownloadButWrongPoseNeverBecomesReady() {
    MapPreparation preparation = new MapPreparation(vehicle, sent::add);
    State state = state().setMaps(List.of(map(MapState.MapStatus.ENABLED)))
        .setAgvPosition(new AgvPosition(0.0, 0.0, 0.0, "other_map", true))
        .setLastNodeId("Point-0001");
    preparation.onState(state);
    assertThat(sent).isEmpty();
    assertThat(preparation.isReady()).isFalse();
    state.setMaps(List.of(new MapState("living_room", "old", MapState.MapStatus.ENABLED, null)));
    preparation.onState(state);
    assertThat(sent).hasSize(1);
    assertThat(preparation.isReady()).isFalse();
  }

  @Test
  void waitsForStoppedFeedbackAndLeavesVersion20Unchanged() {
    MapPreparation preparation = new MapPreparation(vehicle, sent::add);
    State state = state();
    state.getVelocity().setVx(0.2);
    preparation.onState(state);
    assertThat(preparation.getStatus()).isEqualTo("WAITING_FOR_IDLE");
    assertThat(sent).isEmpty();
    MapPreparation old = new MapPreparation(
        vehicle.withProperty("vda5050:version", "2.0"), sent::add
    );
    old.onState(state);
    assertThat(old.isReady()).isTrue();
    assertThat(sent).isEmpty();
  }

  @Test
  void validatesVersion21StateAndRetainsMapInventory() {
    MessageValidator validator = new MessageValidator("2.1");
    State state = state().setMaps(List.of(map(MapState.MapStatus.ENABLED)));
    String json = binder.toJson(state);
    assertDoesNotThrow(() -> validator.validate(json, State.class));
    assertThat(binder.fromJson(json, State.class).getMaps()).containsExactly(
        map(MapState.MapStatus.ENABLED)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(json.replace("ENABLED", "UNKNOWN"), State.class)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(json.replace("2.1.0", "2.0.0"), State.class)
    );
  }

  private ActionState result(int index, ActionStatus status) {
    return new ActionState(sent.get(index).getActions().getFirst().getActionId(), status);
  }

  private MapState map(MapState.MapStatus status) {
    return new MapState("living_room", "1", status, null);
  }

  private State state() {
    return binder.fromJson("""
        {"headerId":1,"timestamp":"2026-01-01T00:00:00Z","version":"2.1.0",
         "manufacturer":"dx","serialNumber":"agv-001","orderId":"","orderUpdateId":0,
         "lastNodeId":"","lastNodeSequenceId":0,"nodeStates":[],"edgeStates":[],
         "driving":false,"actionStates":[],"batteryState":{"batteryCharge":75.5,"charging":false},
         "operatingMode":"AUTOMATIC","errors":[],
         "safetyState":{"eStop":"NONE","fieldViolation":false},
         "maps":[],"velocity":{"vx":0,"vy":0,"omega":0}}
        """, State.class);
  }
}
