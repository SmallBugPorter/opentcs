// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state;

import java.io.Serializable;

/**
 * A map version in the vehicle's inventory.
 *
 * @param mapId Map identifier.
 * @param mapVersion Map version.
 * @param mapStatus ENABLED or DISABLED.
 */
public record MapState(String mapId, String mapVersion, String mapStatus)
    implements
      Serializable {
}
