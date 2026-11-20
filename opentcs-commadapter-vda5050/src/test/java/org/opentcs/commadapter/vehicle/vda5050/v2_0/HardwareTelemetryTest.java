// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoEntry;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoLevel;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoReference;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;

class HardwareTelemetryTest {
  static State state() {
    return state("motor", refs("motor"));
  }

  static Map<String, String> refs(String group) {
    Map<String, String> refs = new LinkedHashMap<>();
    refs.put("schemaVersion", "1");
    refs.put("valid", "true");
    refs.put("sampleAgeMs", "20");
    if (group.equals("motor")) {
      for (String side : List.of("left", "right")) {
        refs.put(side + ".valid", "true");
        refs.put(side + ".hallCount", "-2147483648");
        refs.put(side + ".speedCountsPerSecond", "-123");
        refs.put(side + ".rxTimestampMs", "4294967295");
      }
    }
    else if (group.equals("magnetic")) {
      for (String side : List.of("front", "rear")) {
        for (String key : List.of("io", "collision", "direction", "count")) {
          refs.put(side + "." + key, "63");
        }
        refs.put(side + ".x0Mm", "-3276.8");
        refs.put(side + ".x1Mm", "12.3");
      }
    }
    else {
      refs.put("bms.serialNumber", "S123456789");
      refs.put("io.softwareVersion", "1.2.3");
    }
    return refs;
  }

  static State state(String group, Map<String, String> refs) {
    return BatteryTelemetryTest.state().setInformation(
        List.of(
            new InfoEntry(group + "Telemetry", InfoLevel.INFO).setInfoReferences(
                refs.entrySet().stream().map(e -> new InfoReference(e.getKey(), e.getValue()))
                    .toList()
            )
        )
    );
  }

  @Test
  void preservesSignedCountsTimestampRangeAndRawMagneticOffsets() {
    assertThat(HardwareTelemetry.values("motor", state()))
        .containsEntry("motor:left.hallCount", "-2147483648")
        .containsEntry("motor:left.rxTimestampMs", "4294967295")
        .containsEntry("motor:left.speedCountsPerSecond", "-123");
    assertThat(HardwareTelemetry.values("magnetic", state("magnetic", refs("magnetic"))))
        .containsEntry("magnetic:front.x0Mm", "-3276.8");
  }

  @Test
  void acceptsComputedCountRatesBeyondTheRemovedInt16WireField() {
    Map<String, String> refs = refs("motor");
    refs.put("left.speedCountsPerSecond", "-78917");
    assertThat(HardwareTelemetry.values("motor", state("motor", refs)))
        .containsEntry("motor:left.speedCountsPerSecond", "-78917");
    refs.put("left.speedCountsPerSecond", "2147483647001");
    assertThat(HardwareTelemetry.values("motor", state("motor", refs))).isNull();
  }

  @Test
  void rejectsMissingMalformedAndDuplicateFieldsWithoutRejectingOtherGroups() {
    for (String group : List.of("motor", "magnetic", "device")) {
      assertThat(HardwareTelemetry.values(group, BatteryTelemetryTest.state())).isNull();
      for (Map.Entry<String, String> change : Map.of(
          "schemaVersion", "2", "valid", "false", "sampleAgeMs", "1001"
      ).entrySet()) {
        Map<String, String> refs = refs(group);
        refs.put(change.getKey(), change.getValue());
        assertThat(HardwareTelemetry.values(group, state(group, refs))).isNull();
      }
      State duplicate = state(group, refs(group));
      duplicate.setInformation(
          List.of(duplicate.getInformation().getFirst(), duplicate.getInformation().getFirst())
      );
      assertThat(HardwareTelemetry.values(group, duplicate)).isNull();
    }
    for (String invalid : List.of("NaN", "65536", "-1", "1.2", "1e10000000")) {
      Map<String, String> refs = refs("magnetic");
      refs.put("front.io", invalid);
      assertThat(HardwareTelemetry.values("magnetic", state("magnetic", refs))).isNull();
    }
    Map<String, String> motor = refs("motor");
    motor.remove("left.valid");
    assertThat(HardwareTelemetry.values("motor", state("motor", motor))).isNull();
    Map<String, String> versions = refs("device");
    versions.put("bms.serialNumber", "x".repeat(33));
    assertThat(HardwareTelemetry.values("device", state("device", versions))).isNull();
  }

  @Test
  void clearsMissingVersionsAndInvalidatesOnExpiryAndDisconnect() {
    ScheduledExecutorService executor = mock();
    List<Runnable> timers = new ArrayList<>();
    doAnswer(invocation -> {
      timers.add(invocation.getArgument(0, Runnable.class));
      return null;
    }).when(executor).schedule(any(Runnable.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    Map<String, String> properties = new HashMap<>();
    HardwareTelemetry telemetry = new HardwareTelemetry(executor, properties::put);
    telemetry.update(state("device", refs("device")));
    assertThat(properties).containsEntry("device:bms.serialNumber", "S123456789")
        .containsEntry("device:valid", "true").containsEntry("motor:valid", "false");
    Map<String, String> empty = refs("device");
    empty.remove("bms.serialNumber");
    telemetry.update(state("device", empty));
    assertThat(properties).containsEntry("device:bms.serialNumber", null);
    timers.getFirst().run();
    assertThat(properties).containsEntry("device:valid", "true");
    timers.getLast().run();
    assertThat(properties).containsEntry("device:valid", "false");
    telemetry.update(state());
    telemetry.invalidate();
    assertThat(properties).containsEntry("motor:valid", "false");
  }

  @Test
  void speedLimitReportsRequestedAndAppliedSeparatelyAndExpires() {
    Map<String, String> refs = new HashMap<>(Map.of(
        "schemaVersion", "1", "valid", "true", "sampleAgeMs", "0",
        "maximumMps", "1.0", "appliedMps", "0.8", "requestedMps", "0.4",
        "requestId", "speed-limit-test", "status", "pending", "error", ""
    ));
    Map<String, String> properties = new HashMap<>();
    ScheduledExecutorService executor = mock();
    List<Runnable> timers = new ArrayList<>();
    doAnswer(invocation -> {
      timers.add(invocation.getArgument(0, Runnable.class));
      return null;
    }).when(executor).schedule(any(Runnable.class), eq(5000L), eq(TimeUnit.MILLISECONDS));
    HardwareTelemetry telemetry = new HardwareTelemetry(executor, properties::put);
    telemetry.update(state("speedLimit", refs));
    assertThat(properties).containsEntry("speedLimit:valid", "true")
        .containsEntry("speedLimit:appliedMps", "0.8")
        .containsEntry("speedLimit:requestedMps", "0.4")
        .containsEntry("speedLimit:status", "pending");
    timers.getLast().run();
    assertThat(properties).containsEntry("speedLimit:valid", "false");
    for (String invalid : List.of("1.1", "NaN", "-0.1", "0", "Infinity")) {
      refs.put("requestedMps", invalid);
      assertThat(HardwareTelemetry.values("speedLimit", state("speedLimit", refs))).isNull();
    }
    refs.put("requestedMps", "0.4");
    refs.put("status", "applied");
    assertThat(HardwareTelemetry.values("speedLimit", state("speedLimit", refs))).isNull();
    refs.put("status", "unknown");
    assertThat(HardwareTelemetry.values("speedLimit", state("speedLimit", refs))).isNull();
  }
}
