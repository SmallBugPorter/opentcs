// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;

/**
 * A map version installed on a VDA5050 2.1 vehicle.
 *
 * @param mapId The map identifier.
 * @param mapVersion The installed version.
 * @param mapStatus Whether the map version is enabled.
 * @param mapDescription Optional human-readable description.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapState(String mapId, String mapVersion, MapStatus mapStatus, String mapDescription)
    implements
      Serializable {

  public MapState {
    requireNonNull(mapId, "mapId");
    requireNonNull(mapVersion, "mapVersion");
    requireNonNull(mapStatus, "mapStatus");
  }

  public enum MapStatus {
    /** Map version is active. */
    ENABLED,
    /** Map version is downloaded but inactive. */
    DISABLED
  }
}
