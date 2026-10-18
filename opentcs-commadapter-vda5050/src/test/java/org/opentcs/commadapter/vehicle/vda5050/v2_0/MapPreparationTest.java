// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonBinder;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.Velocity;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionStatus;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.MapState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.OperatingMode;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.data.model.Vehicle;

class MapPreparationTest {
  private final AtomicLong clock = new AtomicLong();
  private final List<InstantActions> sent = new ArrayList<>();
  private final Vehicle vehicle = new Vehicle("test")
      .withProperty("vda5050:autoPrepareMap", "true")
      .withProperty("vda5050:mapId", "map")
      .withProperty("vda5050:mapVersion", "1")
      .withProperty("vda5050:mapDownloadLink", "http://localhost/map.xml");
  private MapPreparation preparation;
  private State state;

  @BeforeEach
  void setUp()
      throws Exception {
    preparation = create(vehicle);
    try (var stream = getClass().getResourceAsStream("state-3.0.json")) {
      state = new JsonBinder().fromJson(
          new WireMessageMapper(ProtocolVersion.V3_0)
              .fromWire(new String(stream.readAllBytes(), StandardCharsets.UTF_8)), State.class
      );
    }
    state.setMaps(List.of());
    state.setVelocity(new Velocity().setVx(0.0).setOmega(0.0));
    state.getAgvPosition().setPositionInitialized(false);
  }

  @Test
  void downloadsThenEnablesAndWaitsForLocalization() {
    stoppedStates();
    assertThat(type()).isEqualTo("downloadMap");
    assertThat(sent.getFirst().getActions().getFirst().getActionParameters())
        .anySatisfy(p -> {
          assertThat(p.getKey()).isEqualTo("mapVersion");
          assertThat(p.getValue()).isEqualTo("1");
        });
    acknowledge(ActionStatus.RUNNING);
    tick(200);
    assertThat(sent).hasSize(1);
    assertThat(preparation.isReady()).isFalse();
    acknowledge(ActionStatus.FINISHED);
    tick(200); // Completion alone is insufficient without inventory confirmation.
    assertThat(sent).hasSize(1);
    state.setMaps(List.of(new MapState("map", "1", "DISABLED")));
    tick(200);
    assertThat(sent).hasSize(2);
    assertThat(type()).isEqualTo("enableMap");
    acknowledge(ActionStatus.FINISHED);
    state.setMaps(List.of(new MapState("map", "1", "ENABLED")));
    tick(200);
    assertThat(preparation.status()).isEqualTo("WAITING_FOR_LOCALIZATION");
    state.getAgvPosition().setPositionInitialized(true);
    tick(200);
    assertThat(preparation.isReady()).isTrue();
    assertThat(sent).hasSize(2);
  }

  @Test
  void cachedVersionOnlyNeedsEnableAndResetRechecksInventory() {
    state.setMaps(List.of(new MapState("map", "1", "DISABLED")));
    stoppedStates();
    assertThat(type()).isEqualTo("enableMap");
    String firstId = sent.getFirst().getActions().getFirst().getActionId();
    preparation.reset();
    state.setActionStates(List.of());
    stoppedStates();
    assertThat(type()).isEqualTo("enableMap");
    assertThat(sent.getLast().getActions().getFirst().getActionId()).isNotEqualTo(firstId);
  }

  @Test
  void enabledVersionSkipsActionsButRequiresMatchingLocalizedPosition() {
    state.setMaps(List.of(new MapState("map", "1", "ENABLED")));
    stoppedStates();
    assertThat(sent).isEmpty();
    assertThat(preparation.isReady()).isFalse();
    state.getAgvPosition().setPositionInitialized(true).setMapId("other");
    tick(200);
    assertThat(preparation.isReady()).isFalse();
    state.getAgvPosition().setMapId("map");
    tick(200);
    assertThat(preparation.isReady()).isTrue();
    clock.addAndGet(2000);
    MapPreparation.Readiness fresh = preparation.readiness();
    assertThat(fresh.ready()).isTrue();
    assertThat(fresh.stateAgeMillis()).isEqualTo(2000);
    clock.incrementAndGet();
    MapPreparation.Readiness stale = preparation.readiness();
    assertThat(stale.status()).isEqualTo("READY");
    assertThat(stale.stateAgeMillis()).isEqualTo(2001);
    assertThat(stale.ready()).isFalse();
    // The saved snapshot must keep the actual decision even after another state arrives.
    tick(1);
    assertThat(preparation.isReady()).isTrue();
    assertThat(stale.ready()).isFalse();
    clock.addAndGet(2001);
    assertThat(preparation.isReady()).isFalse();
    preparation.reset();
    assertThat(preparation.isReady()).isFalse();
    assertThat(preparation.readiness().stateAgeMillis()).isEqualTo(-1);
  }

  @Test
  void wrongVersionIsDownloadedAndMissingInventoryIsNotTreatedAsEmpty() {
    state.setMaps(null);
    stoppedStates();
    assertThat(sent).isEmpty();
    state.setMaps(List.of(new MapState("map", "old", "ENABLED")));
    tick(200);
    assertThat(type()).isEqualTo("downloadMap");
  }

  @Test
  void requiresStationaryFeedbackAndOperationalMode() {
    state.setVelocity(null);
    stoppedStates();
    assertThat(sent).isEmpty();
    state.setVelocity(new Velocity().setVx(0.1).setOmega(0.0));
    stoppedStates();
    assertThat(sent).isEmpty();
    state.getVelocity().setVx(0.0);
    state.setOperatingMode(OperatingMode.INTERVENED);
    stoppedStates();
    assertThat(sent).isEmpty();
    state.setOperatingMode(OperatingMode.AUTOMATIC);
    tick(200);
    assertThat(type()).isEqualTo("downloadMap");
  }

  @Test
  void runningForeignActionPreventsMapChangesAndGapsRestartStoppedWindow() {
    state.setActionStates(List.of(new ActionState("foreign", ActionStatus.RUNNING)));
    stoppedStates();
    assertThat(sent).isEmpty();
    state.setActionStates(List.of());
    tick(200);
    tick(3000);
    assertThat(sent).isEmpty();
    tick(500);
    assertThat(sent).hasSize(1);
  }

  @Test
  void failureDoesNotEnableOrRetryUntilReset() {
    stoppedStates();
    acknowledge(ActionStatus.FAILED);
    tick(200);
    assertThat(preparation.status()).startsWith("FAILED:");
    tick(60000);
    assertThat(sent).hasSize(1);
    assertThat(preparation.isReady()).isFalse();
    preparation.reset();
    stoppedStates();
    assertThat(sent).hasSize(2);
  }

  @Test
  void retriesLostDeliveryWithSameIdButDoesNotResendRunningAction() {
    stoppedStates();
    tick(1000);
    assertThat(sent).hasSize(2);
    assertThat(sent.getFirst().getActions().getFirst().getActionId())
        .isEqualTo(sent.getLast().getActions().getFirst().getActionId());
    acknowledge(ActionStatus.RUNNING);
    tick(1000);
    assertThat(sent).hasSize(2);
    tick(60000);
    assertThat(preparation.status()).contains("timed out");
    assertThat(preparation.isReady()).isFalse();
  }

  @Test
  void optInAndConfigurationValidation() {
    assertThat(create(vehicle.withProperty("vda5050:autoPrepareMap", "false")).isReady())
        .isTrue();
    assertThat(create(vehicle.withProperty("vda5050:mapVersion", "")).status())
        .startsWith("FAILED:");
    assertThat(create(vehicle.withProperty("vda5050:mapDownloadLink", "file:///tmp/map")).status())
        .startsWith("FAILED:");
    assertThat(new MapPreparation(vehicle, ProtocolVersion.V2_0, sent::add, clock::get).status())
        .startsWith("FAILED:");
  }

  private MapPreparation create(Vehicle config) {
    return new MapPreparation(config, ProtocolVersion.V2_1, sent::add, clock::get);
  }

  private void stoppedStates() {
    tick(0);
    tick(500);
  }

  private void tick(long elapsed) {
    clock.addAndGet(elapsed);
    preparation.onState(state);
  }

  private String type() {
    return sent.getLast().getActions().getFirst().getActionType();
  }

  private void acknowledge(ActionStatus status) {
    state.setActionStates(
        List.of(
            new ActionState(
                sent.getLast().getActions().getFirst().getActionId(), status
            )
        )
    );
  }
}
