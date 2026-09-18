// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.Action;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.ActionParameter;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.common.BlockingType;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.ActionStatus;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.MapState;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.data.model.Vehicle;

/**
 * Optional map download/enable workflow. Motion is admitted only after inventory and pose agree.
 * An action failure/timeout remains latched until the operator requests {@code prepareMap} again.
 */
public class MapPreparation {
  /**
   * The requested map version.
   */
  public static final String MAP_VERSION = "vda5050:mapVersion";
  /**
   * HTTP URL accessible from the vehicle.
   */
  public static final String MAP_DOWNLOAD_LINK = "vda5050:mapDownloadLink";
  /**
   * Optional SHA-256 hash of the downloaded file.
   */
  public static final String MAP_HASH = "vda5050:mapHash";
  /**
   * Whether to automatically download and enable the requested map.
   */
  public static final String AUTO_PREPARE = "vda5050:autoPrepareMap";
  /**
   * The vehicle property exposing readiness and failures.
   */
  public static final String PREPARATION_STATUS = "vda5050:mapPreparation";
  /**
   * The adapter message allowing an operator to retry after a failure.
   */
  public static final String PREPARE_MESSAGE = "prepareMap";

  private final boolean required;
  private final boolean automatic;
  private final String mapId;
  private final String mapVersion;
  private final String downloadLink;
  private final String mapHash;
  private final Consumer<InstantActions> sender;
  private Action pending;
  private long pendingSince;
  private boolean failed;
  private boolean ready;
  private String status = "WAITING_FOR_STATE";

  public MapPreparation(Vehicle vehicle, Consumer<InstantActions> sender) {
    this.required = "2.1".equals(
        Objects.toString(vehicle.getProperty(ObjectProperties.PROPKEY_VEHICLE_VERSION), "").strip()
    );
    this.automatic = Boolean.parseBoolean(vehicle.getProperty(AUTO_PREPARE));
    this.mapId = vehicle.getProperty(ObjectProperties.PROPKEY_VEHICLE_MAP_ID);
    this.mapVersion = vehicle.getProperty(MAP_VERSION);
    this.downloadLink = vehicle.getProperty(MAP_DOWNLOAD_LINK);
    this.mapHash = vehicle.getProperty(MAP_HASH);
    this.sender = sender;
    if (required && automatic && (blank(mapId) || blank(mapVersion) || blank(downloadLink))) {
      throw new IllegalArgumentException(
          "autoPrepareMap requires mapId, mapVersion and mapDownloadLink"
      );
    }
  }

  public boolean isReady() {
    return !required || ready;
  }

  public String getStatus() {
    return required ? status : "NOT_REQUIRED";
  }

  public void disconnected() {
    ready = false;
    status = "WAITING_FOR_CONNECTION";
  }

  public void retry() {
    pending = null;
    failed = false;
    ready = false;
    status = "WAITING_FOR_STATE";
  }

  public void onState(State state) {
    if (!required) {
      return;
    }
    ready = false;
    if (failed) {
      return;
    }
    List<MapState> maps = state.getMaps() == null ? List.of() : state.getMaps();
    MapState installed = maps.stream()
        .filter(map -> Objects.equals(map.mapId(), mapId))
        .filter(map -> blank(mapVersion) || Objects.equals(map.mapVersion(), mapVersion))
        .findFirst().orElse(null);
    if (pending != null) {
      ActionState result = state.getActionStates().stream()
          .filter(action -> action.getActionId().equals(pending.getActionId()))
          .findFirst().orElse(null);
      if (result != null && result.getActionStatus() == ActionStatus.FAILED) {
        fail(pending.getActionType() + " failed: " + result.getResultDescription());
        return;
      }
      boolean inventoryConfirmed = installed != null
          && (pending.getActionType().equals("downloadMap")
              || installed.mapStatus() == MapState.MapStatus.ENABLED);
      if (result == null || result.getActionStatus() != ActionStatus.FINISHED
          || !inventoryConfirmed) {
        if (System.nanoTime() - pendingSince > Duration.ofSeconds(60).toNanos()) {
          fail(pending.getActionType() + " timed out; check vehicle actionStates and maps");
        }
        return;
      }
      pending = null;
    }
    if (installed != null && installed.mapStatus() == MapState.MapStatus.ENABLED) {
      ready = state.getAgvPosition() != null
          && Boolean.TRUE.equals(state.getAgvPosition().isPositionInitialized())
          && Objects.equals(state.getAgvPosition().getMapId(), mapId)
          && !blank(state.getLastNodeId());
      status = ready ? "READY" : "WAITING_FOR_LOCALIZATION";
      return;
    }
    status = "WAITING_FOR_MAP";
    if (!automatic) {
      return;
    }
    if (Boolean.TRUE.equals(state.isDriving()) || !state.getNodeStates().isEmpty()
        || !state.getEdgeStates().isEmpty() || state.getVelocity() == null
        || !stopped(state.getVelocity().getVx(), 0.001)
        || !stopped(state.getVelocity().getVy(), 0.001)
        || !stopped(state.getVelocity().getOmega(), 0.015)) {
      status = "WAITING_FOR_IDLE";
      return;
    }
    List<ActionParameter> parameters = new ArrayList<>(
        List.of(
            new ActionParameter("mapId", mapId), new ActionParameter("mapVersion", mapVersion)
        )
    );
    String actionType = installed == null ? "downloadMap" : "enableMap";
    if (installed == null) {
      parameters.add(new ActionParameter("mapDownloadLink", downloadLink));
      if (!blank(mapHash)) {
        parameters.add(new ActionParameter("mapHash", mapHash));
      }
    }
    pending = new Action(actionType, UUID.randomUUID().toString(), BlockingType.NONE)
        .setActionParameters(parameters);
    pendingSince = System.nanoTime();
    status = actionType.equals("downloadMap") ? "DOWNLOADING" : "ENABLING";
    sender.accept(new InstantActions().setActions(List.of(pending)));
  }

  private void fail(String reason) {
    failed = true;
    status = "FAILED: " + reason;
  }

  private static boolean stopped(Double value, double tolerance) {
    return value != null && Math.abs(value) <= tolerance;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
