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
import java.util.function.Predicate;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoEntry;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.InfoReference;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;

/** Read-only hardware telemetry exported as protocol-independent vehicle properties. */
final class HardwareTelemetry {
  private final List<Channel> channels;

  HardwareTelemetry(ScheduledExecutorService executor, BiConsumer<String, String> properties) {
    channels = List.of(
        new Channel("motor", executor, properties),
        new Channel("magnetic", executor, properties), new Channel("device", executor, properties)
    );
  }

  void invalidate() {
    channels.forEach(Channel::invalidate);
  }

  void update(State state) {
    channels.forEach(channel -> channel.update(state));
  }

  static Map<String, Predicate<String>> fields(String group) {
    Map<String, Predicate<String>> result = new LinkedHashMap<>();
    if (group.equals("motor")) {
      for (String side : List.of("left", "right")) {
        result.put(side + ".valid", value -> value.equals("true") || value.equals("false"));
        result.put(side + ".hallCount", integer(Integer.MIN_VALUE, Integer.MAX_VALUE));
        // Rounded controller-derived count delta / elapsed ms; no int16 wire speed.
        result.put(side + ".speedCountsPerSecond", integer(-2147483648000L, 2147483647000L));
        result.put(side + ".rxTimestampMs", integer(0, 0xffffffffL));
      }
    }
    else if (group.equals("magnetic")) {
      for (String side : List.of("front", "rear")) {
        for (String key : List.of("io", "collision", "direction", "count")) {
          result.put(side + "." + key, integer(0, 65535));
        }
        for (String key : List.of("x0Mm", "x1Mm")) {
          result.put(side + "." + key, value -> {
            BigDecimal number = new BigDecimal(value);
            return number.compareTo(new BigDecimal("-3276.8")) >= 0
                && number.compareTo(new BigDecimal("3276.7")) <= 0 && number.scale() <= 1;
          });
        }
      }
    }
    else if (group.equals("device")) {
      for (String device : List.of("bms", "magFront", "magRear", "motorLeft", "motorRight", "io")) {
        result.put(device + ".softwareVersion", text(device.equals("bms") ? 16 : 8));
        if (!device.equals("io")) {
          result.put(device + ".hardwareVersion", text(device.equals("bms") ? 16 : 8));
        }
      }
      result.put("bms.serialNumber", text(32));
    }
    return result;
  }

  private static Predicate<String> integer(long min, long max) {
    return value -> value.matches("-?[0-9]+") && Long.parseLong(value) >= min
        && Long.parseLong(value) <= max;
  }

  private static Predicate<String> text(int length) {
    return value -> !value.isEmpty() && value.length() <= length && value.matches("[ -~]+");
  }

  /** Null means invalid. Missing device fields explicitly clear previous identity values. */
  static Map<String, String> values(String group, State state) {
    List<InfoEntry> entries = state.getInformation() == null ? List.of()
        : state.getInformation().stream().filter(e -> (group + "Telemetry").equals(e.getInfoType()))
            .toList();
    if (entries.size() != 1 || entries.getFirst().getInfoReferences() == null) {
      return null;
    }
    Map<String, String> refs = new HashMap<>();
    for (InfoReference ref : entries.getFirst().getInfoReferences()) {
      if (refs.putIfAbsent(ref.getReferenceKey(), ref.getReferenceValue()) != null) {
        return null;
      }
    }
    if (!"1".equals(refs.get("schemaVersion")) || !"true".equals(refs.get("valid"))) {
      return null;
    }
    try {
      long age = Long.parseLong(refs.get("sampleAgeMs"));
      if (age < 0 || age > 1000) {
        return null;
      }
      Map<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<String, Predicate<String>> field : fields(group).entrySet()) {
        String value = refs.get(field.getKey());
        if (value == null && group.equals("device")) {
          result.put(group + ":" + field.getKey(), null);
        }
        else if (value == null || value.length() > 32 || !field.getValue().test(value)) {
          return null;
        }
        else {
          result.put(group + ":" + field.getKey(), value);
        }
      }
      return result;
    }
    catch (IllegalArgumentException | NullPointerException exc) {
      return null;
    }
  }

  private static final class Channel {
    private final String group;
    private final ScheduledExecutorService executor;
    private final BiConsumer<String, String> properties;
    private ScheduledFuture<?> expiry;
    private long generation;

    private Channel(
        String group, ScheduledExecutorService executor, BiConsumer<String, String> properties
    ) {
      this.group = group;
      this.executor = executor;
      this.properties = properties;
    }

    private void invalidate() {
      generation++;
      if (expiry != null) {
        expiry.cancel(false);
        expiry = null;
      }
      properties.accept(group + ":schemaVersion", "1");
      properties.accept(group + ":maxAgeMs", "5000");
      properties.accept(group + ":valid", "false");
    }

    private void update(State state) {
      Map<String, String> values = HardwareTelemetry.values(group, state);
      invalidate();
      if (values == null) {
        return;
      }
      values.forEach(properties);
      properties.accept(group + ":updatedAt", Instant.now().toString());
      properties.accept(group + ":valid", "true");
      long current = generation;
      expiry = executor.schedule(() -> {
        if (current == generation) {
          invalidate();
        }
      }, 5000, TimeUnit.MILLISECONDS);
    }
  }
}
