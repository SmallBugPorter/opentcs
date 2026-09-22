// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.Action;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.ActionParameter;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.BlockingType;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.Velocity;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionStatus;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.OperatingMode;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.data.model.Vehicle;

/**
 * Prepares a configured map using vehicle acknowledgements, before allowing route dispatch.
 * Called on the kernel executor. Downloads remain entirely on the vehicle.
 */
class MapPreparation {
  private final boolean enabled;
  private final String mapId;
  private final String mapVersion;
  private final String downloadLink;
  private final String hash;
  private final String configurationError;
  private final Consumer<InstantActions> sender;
  private final LongSupplier clock;
  private Action pending;
  private long started;
  private long lastSent;
  private long stoppedSince = -1;
  private volatile long lastState = -1;
  private boolean failed;
  private volatile String status;

  MapPreparation(
      Vehicle vehicle, ProtocolVersion version,
      Consumer<InstantActions> sender, LongSupplier clock
  ) {
    enabled = Boolean.parseBoolean(vehicle.getProperty("vda5050:autoPrepareMap"));
    mapId = vehicle.getProperty("vda5050:mapId");
    mapVersion = vehicle.getProperty("vda5050:mapVersion");
    downloadLink = vehicle.getProperty("vda5050:mapDownloadLink");
    hash = vehicle.getProperty("vda5050:mapHash");
    this.sender = sender;
    this.clock = clock;
    configurationError = enabled ? validateConfiguration(version) : null;
    reset();
  }

  private String validateConfiguration(ProtocolVersion version) {
    if (version == ProtocolVersion.V2_0) {
      return "automatic map preparation requires VDA5050 2.1 or 3.0";
    }
    if (mapId == null || mapId.isBlank() || mapVersion == null || mapVersion.isBlank()) {
      return "mapId and mapVersion are required";
    }
    try {
      URI uri = URI.create(downloadLink == null ? "" : downloadLink);
      if (!List.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme())
          || uri.getHost() == null || uri.getUserInfo() != null) {
        return "mapDownloadLink must be an HTTP(S) URL without credentials";
      }
    }
    catch (IllegalArgumentException exc) {
      return "invalid mapDownloadLink";
    }
    if (hash != null && !hash.isBlank() && !hash.matches("[0-9a-fA-F]{64}")) {
      return "mapHash must be a SHA-256 hexadecimal digest";
    }
    return null;
  }

  void reset() {
    pending = null;
    stoppedSince = -1;
    lastState = -1;
    failed = configurationError != null;
    status = !enabled ? "DISABLED"
        : failed ? "FAILED: " + configurationError : "WAITING_FOR_STATE";
  }

  boolean isEnabled() {
    return enabled;
  }

  boolean isReady() {
    return !enabled || ("READY".equals(status) && clock.getAsLong() - lastState <= 2000);
  }

  String status() {
    return status;
  }

  void onState(State state) {
    if (!enabled || failed) {
      return;
    }
    long now = clock.getAsLong();
    if (lastState < 0 || now - lastState > 2000) {
      stoppedSince = -1;
    }
    lastState = now;
    boolean idle = Boolean.FALSE.equals(state.isDriving())
        && state.getNodeStates().isEmpty() && state.getEdgeStates().isEmpty()
        && state.getActionStates().stream().allMatch(
            a -> terminal(a.getActionStatus())
                || (pending != null && pending.getActionId().equals(a.getActionId()))
        )
        && stopped(state.getVelocity());
    if (!idle) {
      stoppedSince = -1;
    }
    else if (stoppedSince < 0) {
      stoppedSince = now;
    }
    boolean operational = state.getOperatingMode() == OperatingMode.AUTOMATIC
        || state.getOperatingMode() == OperatingMode.SEMIAUTOMATIC;
    if (pending != null) {
      var acknowledgement = state.getActionStates().stream()
          .filter(a -> pending.getActionId().equals(a.getActionId())).findFirst();
      if (acknowledgement.isPresent()
          && acknowledgement.get().getActionStatus() == ActionStatus.FAILED) {
        fail(pending.getActionType() + " failed (actionId=" + pending.getActionId() + ")");
        return;
      }
      boolean finished = acknowledgement.isPresent()
          && acknowledgement.get().getActionStatus() == ActionStatus.FINISHED;
      boolean installed = state.getMaps() != null && state.getMaps().stream()
          .anyMatch(
              m -> mapId.equals(m.mapId()) && mapVersion.equals(m.mapVersion())
                  && ("downloadMap".equals(pending.getActionType())
                      || "ENABLED".equals(m.mapStatus()))
          );
      if (finished && installed) {
        pending = null;
      }
      else {
        if (now - started >= 60000) {
          fail("map action timed out; reconnect or re-enable adapter to retry");
        }
        else if (acknowledgement.isEmpty() && operational && idle
            && now - lastSent >= 1000) {
              // Retry only unacknowledged delivery, with the same id for vehicle deduplication.
              send(now);
            }
        return;
      }
    }
    if (state.getMaps() == null) {
      status = "WAITING_FOR_MAP_INVENTORY";
      return;
    }
    var selected = state.getMaps().stream()
        .filter(m -> mapId.equals(m.mapId()) && mapVersion.equals(m.mapVersion())).findFirst();
    if (selected.isPresent() && "ENABLED".equals(selected.get().mapStatus())) {
      status = state.getAgvPosition() != null
          && mapId.equals(state.getAgvPosition().getMapId())
          && Boolean.TRUE.equals(state.getAgvPosition().isPositionInitialized())
              ? "READY" : "WAITING_FOR_LOCALIZATION";
      return;
    }
    if (!operational || !idle || now - stoppedSince < 500) {
      status = "WAITING_FOR_IDLE";
      return;
    }
    String type = selected.isPresent() ? "enableMap" : "downloadMap";
    List<ActionParameter> parameters = new ArrayList<>();
    parameters.add(new ActionParameter("mapId", mapId));
    parameters.add(new ActionParameter("mapVersion", mapVersion));
    if ("downloadMap".equals(type)) {
      parameters.add(new ActionParameter("mapDownloadLink", downloadLink));
      if (hash != null && !hash.isBlank()) {
        parameters.add(new ActionParameter("mapHash", hash));
      }
    }
    pending = new Action(type, "auto-map-" + UUID.randomUUID(), BlockingType.HARD)
        .setActionParameters(parameters);
    started = now;
    status = "downloadMap".equals(type) ? "DOWNLOADING" : "ENABLING";
    send(now);
  }

  private void send(long now) {
    lastSent = now;
    sender.accept(new InstantActions().setActions(List.of(pending)));
  }

  private void fail(String reason) {
    failed = true;
    status = "FAILED: " + reason;
  }

  private static boolean terminal(ActionStatus status) {
    return status == ActionStatus.FINISHED || status == ActionStatus.FAILED;
  }

  private static boolean stopped(Velocity velocity) {
    return velocity != null && nearZero(velocity.getVx()) && nearZero(velocity.getOmega())
        && (velocity.getVy() == null || nearZero(velocity.getVy()));
  }

  private static boolean nearZero(Double value) {
    return value != null && Double.isFinite(value) && Math.abs(value) <= 0.01;
  }
}
