// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Defines various error levels.
 */
public enum ErrorLevel {

  /**
   * AGV is ready to start. (E.g. maintenance cycle expiration warning.)
   */
  @JsonProperty(value = "WARNING")
  WARNING,
  /** Immediate attention required; the current order may continue (3.0). */
  URGENT,
  /** The current order cannot continue (3.0). */
  CRITICAL,
  /**
   * AGV is not in running condition, user intervention required. (E.g. laser scanner is
   * contaminated.)
   */
  @JsonProperty(value = "FATAL")
  FATAL;
}
