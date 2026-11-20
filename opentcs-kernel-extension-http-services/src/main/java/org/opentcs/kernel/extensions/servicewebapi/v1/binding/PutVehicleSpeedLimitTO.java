// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.kernel.extensions.servicewebapi.v1.binding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** A requested operating speed limit in metres per second, confirmed asynchronously by the vehicle. */
public class PutVehicleSpeedLimitTO {
  private final double maxSpeedMps;
  private final String requestId;

  @JsonCreator
  public PutVehicleSpeedLimitTO(
      @JsonProperty(value = "maxSpeedMps", required = true) Double maxSpeedMps,
      @JsonProperty("requestId") String requestId
  ) {
    if (maxSpeedMps == null || !Double.isFinite(maxSpeedMps) || maxSpeedMps <= 0) {
      throw new IllegalArgumentException("maxSpeedMps must be a finite positive number");
    }
    if (requestId != null && !requestId.matches("[A-Za-z0-9_-]{1,96}")) {
      throw new IllegalArgumentException("Invalid speed limit requestId");
    }
    this.maxSpeedMps = maxSpeedMps;
    this.requestId = requestId == null ? UUID.randomUUID().toString() : requestId;
  }

  public double getMaxSpeedMps() {
    return maxSpeedMps;
  }

  public String getRequestId() {
    return requestId;
  }
}
