// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import java.util.Arrays;
import java.util.Optional;

/**
 * Wire protocol versions served by the shared route-based adapter.
 */
public enum ProtocolVersion {
  /** VDA5050 2.0. */
  V2_0("2.0", 2, "v2_0"),
  /** VDA5050 2.1. */
  V2_1("2.1", 2, "v2_1"),
  /** VDA5050 3.0. */
  V3_0("3.0", 3, "v3_0");

  private final String configurationValue;
  private final int major;
  private final String resourceDirectory;

  ProtocolVersion(String configurationValue, int major, String resourceDirectory) {
    this.configurationValue = configurationValue;
    this.major = major;
    this.resourceDirectory = resourceDirectory;
  }

  /** @return The value used in vehicle and kernel configuration. */
  public String configurationValue() {
    return configurationValue;
  }

  /** @return The full version written to message headers. */
  public String wireVersion() {
    return configurationValue + ".0";
  }

  /** @return The major version used in MQTT topic names. */
  public int major() {
    return major;
  }

  /** @return The classpath directory containing the schemas. */
  public String schemaDirectory() {
    return "/org/opentcs/commadapter/vehicle/vda5050/" + resourceDirectory + "/schemas/";
  }

  /**
   * Resolves a configured version, without silently falling back for unknown versions.
   *
   * @param value The configured major.minor version.
   * @return The matching version, if supported.
   */
  public static Optional<ProtocolVersion> fromConfiguration(String value) {
    return Arrays.stream(values())
        .filter(version -> version.configurationValue.equals(value == null ? null : value.strip()))
        .findFirst();
  }
}
