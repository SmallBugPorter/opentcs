// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.BatteryState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.EStop;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoEntry;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoLevel;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoReference;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.OperatingMode;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.SafetyState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;

class BatteryTelemetryTest {
  private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");

  static State state() {
    return new State(
        1L, NOW, "2.1.0", "dx", "agv-0008", "", 0L, "", 0L,
        List.of(), List.of(), false, List.of(), new BatteryState(82.0, true),
        OperatingMode.AUTOMATIC, List.of(), new SafetyState(EStop.NONE, false)
    ).setInformation(List.of(entry(references())));
  }

  private static Map<String, String> references() {
    return new LinkedHashMap<>(
        Map.of(
            "schemaVersion", "1", "valid", "true", "sampleAgeMs", "40",
            "voltageV", "51.2", "currentA", "-12.3", "temperatureC", "-5.0",
            "cycleCount", "40000", "totalCapacityAh", "5000.0"
        )
    );
  }

  private static InfoEntry entry(Map<String, String> refs) {
    return new InfoEntry("batteryTelemetry", InfoLevel.INFO).setInfoReferences(
        refs.entrySet().stream().map(e -> new InfoReference(e.getKey(), e.getValue())).toList()
    );
  }

  @Test
  void mapsSignedValuesUnitsAndKernelReceiptTime() {
    assertThat(BatteryTelemetry.values(state(), NOW)).containsExactlyInAnyOrderEntriesOf(
        Map.of(
            "battery:voltageV", "51.2", "battery:currentA", "-12.3",
            "battery:temperatureC", "-5", "battery:cycleCount", "40000",
            "battery:totalCapacityAh", "5000", "battery:charging", "true",
            "battery:updatedAt", "2026-11-19T08:00:00Z"
        )
    );
  }

  @ParameterizedTest
  @CsvSource(
    {"schemaVersion,2", "valid,false", "sampleAgeMs,1001", "sampleAgeMs,-1",
        "voltageV,-1", "voltageV,NaN", "currentA,Infinity", "temperatureC,invalid",
        "cycleCount,-1", "cycleCount,1.5", "totalCapacityAh,-1", "voltageV,1e100000000"}
  )
  void rejectsInvalidValuesWithoutAffectingOtherVehicleState(String key, String value) {
    Map<String, String> refs = references();
    refs.put(key, value);
    assertThat(BatteryTelemetry.values(state().setInformation(List.of(entry(refs))), NOW))
        .isEmpty();
  }

  @Test
  void rejectsMissingDuplicateAndUnsupportedTelemetry() {
    assertThat(BatteryTelemetry.values(state().setInformation(null), NOW)).isEmpty();
    assertThat(BatteryTelemetry.values(state().setInformation(List.of()), NOW)).isEmpty();
    for (String key : references().keySet()) {
      Map<String, String> refs = references();
      refs.remove(key);
      assertThat(BatteryTelemetry.values(state().setInformation(List.of(entry(refs))), NOW))
          .isEmpty();
    }
    InfoEntry entry = entry(references());
    assertThat(BatteryTelemetry.values(state().setInformation(List.of(entry, entry)), NOW))
        .isEmpty();
    List<InfoReference> refs = new ArrayList<>(entry.getInfoReferences());
    refs.add(new InfoReference("voltageV", "24"));
    entry.setInfoReferences(refs);
    assertThat(BatteryTelemetry.values(state().setInformation(List.of(entry)), NOW)).isEmpty();
  }

  @Test
  void invalidatesOnTimeoutAndDoesNotLetOldTimersInvalidateNewSamples() {
    ScheduledExecutorService executor = mock();
    List<Runnable> timers = new ArrayList<>();
    doAnswer(invocation -> {
      timers.add(invocation.getArgument(0, Runnable.class));
      return null;
    }).when(executor).schedule(any(Runnable.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    Map<String, String> props = new HashMap<>();
    BatteryTelemetry telemetry = new BatteryTelemetry(executor, props::put);
    telemetry.update(state());
    telemetry.update(state());
    timers.getFirst().run();
    assertThat(props).containsEntry("battery:valid", "true");
    timers.getLast().run();
    assertThat(props).containsEntry("battery:valid", "false")
        .containsEntry("battery:currentA", "-12.3");
    telemetry.update(state());
    telemetry.update(state().setInformation(List.of()));
    assertThat(props).containsEntry("battery:valid", "false");
    telemetry.update(state());
    telemetry.invalidate();
    assertThat(props).containsEntry("battery:valid", "false");
  }
}
