// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoEntry;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoReference;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;

/** Maps versioned battery display telemetry to protocol-independent vehicle properties. */
final class BatteryTelemetry {
  static final long MAX_AGE_MS = 5000;
  private final ScheduledExecutorService executor;
  private final BiConsumer<String, String> properties;
  private ScheduledFuture<?> expiry;
  private long generation;

  BatteryTelemetry(ScheduledExecutorService executor, BiConsumer<String, String> properties) {
    this.executor = executor;
    this.properties = properties;
  }

  void invalidate() {
    generation++;
    if (expiry != null) {
      expiry.cancel(false);
      expiry = null;
    }
    properties.accept("battery:schemaVersion", "1");
    properties.accept("battery:maxAgeMs", Long.toString(MAX_AGE_MS));
    properties.accept("battery:valid", "false");
  }

  void update(State state) {
    Map<String, String> values = values(state, Instant.now());
    // Mark invalid while replacing the fields; publish valid last.
    invalidate();
    if (values.isEmpty()) {
      return;
    }
    values.forEach(properties);
    properties.accept("battery:valid", "true");
    long currentGeneration = generation;
    expiry = executor.schedule(() -> {
      if (generation == currentGeneration) {
        invalidate();
      }
    }, MAX_AGE_MS, TimeUnit.MILLISECONDS);
  }

  /** Empty means unsupported, incomplete or invalid; last readings remain historical only. */
  static Map<String, String> values(State state, Instant receivedAt) {
    List<InfoEntry> entries = state.getInformation() == null ? List.of()
        : state.getInformation().stream()
            .filter(entry -> "batteryTelemetry".equals(entry.getInfoType())).toList();
    if (entries.size() != 1 || entries.getFirst().getInfoReferences() == null) {
      return Map.of();
    }
    Map<String, String> refs = new HashMap<>();
    for (InfoReference ref : entries.getFirst().getInfoReferences()) {
      if (refs.putIfAbsent(ref.getReferenceKey(), ref.getReferenceValue()) != null) {
        return Map.of();
      }
    }
    if (!"1".equals(refs.get("schemaVersion")) || !"true".equals(refs.get("valid"))) {
      return Map.of();
    }
    try {
      long sampleAge = Long.parseLong(refs.get("sampleAgeMs"));
      if (sampleAge < 0 || sampleAge > 1000) {
        return Map.of();
      }
      Map<String, String> result = new LinkedHashMap<>();
      for (String key : List.of("voltageV", "currentA", "temperatureC", "totalCapacityAh")) {
        String raw = refs.get(key);
        if (raw == null || raw.length() > 32) {
          return Map.of();
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.abs().compareTo(BigDecimal.valueOf(1000000)) > 0
            || value.scale() < -6 || value.scale() > 6
            || ((key.equals("voltageV") || key.equals("totalCapacityAh"))
                && value.signum() < 0)) {
          return Map.of();
        }
        result.put("battery:" + key, value.stripTrailingZeros().toPlainString());
      }
      long cycles = Long.parseLong(refs.get("cycleCount"));
      if (cycles < 0) {
        return Map.of();
      }
      result.put("battery:cycleCount", Long.toString(cycles));
      result.put("battery:charging", state.getBatteryState().isCharging().toString());
      // Kernel receipt time, independent of the vehicle's wall clock.
      result.put("battery:updatedAt", receivedAt.toString());
      return result;
    }
    catch (IllegalArgumentException | NullPointerException exc) {
      return Map.of();
    }
  }
}
