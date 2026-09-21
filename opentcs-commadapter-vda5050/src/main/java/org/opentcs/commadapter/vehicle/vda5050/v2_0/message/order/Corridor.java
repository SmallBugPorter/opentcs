// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;

/**
 * A static driving corridor supported from VDA5050 2.1 onwards.
 *
 * @param leftWidth Allowed deviation to the left, in metres.
 * @param rightWidth Allowed deviation to the right, in metres.
 * @param corridorReferencePoint KINEMATIC_CENTER or CONTOUR; null uses the protocol default.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Corridor(double leftWidth, double rightWidth, String corridorReferencePoint)
    implements
      Serializable {

  /**
   * Creates a static corridor.
   *
   * @param leftWidth Allowed deviation to the left, in metres.
   * @param rightWidth Allowed deviation to the right, in metres.
   * @param corridorReferencePoint Reference point, or null for the protocol default.
   */
  public Corridor {
    if (!Double.isFinite(leftWidth) || leftWidth < 0
        || !Double.isFinite(rightWidth) || rightWidth < 0) {
      throw new IllegalArgumentException("Corridor widths must be finite and non-negative");
    }
    if (corridorReferencePoint != null
        && !corridorReferencePoint.equals("KINEMATIC_CENTER")
        && !corridorReferencePoint.equals("CONTOUR")) {
      throw new IllegalArgumentException("Unsupported corridor reference point");
    }
  }
}
