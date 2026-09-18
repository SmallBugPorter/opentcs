// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import jakarta.annotation.Nonnull;
import java.io.InputStreamReader;
import java.util.Map;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonValidator;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.Connection;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Order;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.visualization.Visualization;

/**
 * A {@link JsonValidator} for VDA5050 2.0/2.1 messages.
 */
public class MessageValidator
    extends
      JsonValidator {

  /**
   * A validator that accepts all JSON inputs, i.e. never throws.
   */
  public static final MessageValidator ACCEPTING_ALL = new MessageValidator.AcceptingAll();

  /**
   * Creates a new instance.
   *
   * @throws IllegalArgumentException If there was a problem reading the VDA5050 schemas.
   */
  public MessageValidator()
      throws IllegalArgumentException {
    this("2.0");
  }

  public MessageValidator(String version) {
    super(
        Map.of(
            Connection.class,
            new InputStreamReader(
                Connection.class.getResourceAsStream(
                    schemaPath(Connection.JSON_SCHEMA_PATH, version)
                )
            ),
            InstantActions.class,
            new InputStreamReader(
                InstantActions.class.getResourceAsStream(
                    schemaPath(InstantActions.JSON_SCHEMA_PATH, version)
                )
            ),
            Order.class,
            new InputStreamReader(
                Order.class.getResourceAsStream(schemaPath(Order.JSON_SCHEMA_PATH, version))
            ),
            State.class,
            new InputStreamReader(
                State.class.getResourceAsStream(schemaPath(State.JSON_SCHEMA_PATH, version))
            ),
            Visualization.class,
            new InputStreamReader(
                Visualization.class.getResourceAsStream(
                    schemaPath(Visualization.JSON_SCHEMA_PATH, version)
                )
            ),
            com.fasterxml.jackson.databind.JsonNode.class,
            new InputStreamReader(
                MessageValidator.class.getResourceAsStream(
                    schemaPath(
                        "/org/opentcs/commadapter/vehicle/vda5050/"
                            + "v2_0/schemas/factsheet.schema.json",
                        version
                    )
                )
            )
        )
    );
  }

  private static String schemaPath(String path, String version) {
    if (!java.util.Set.of("2.0", "2.1").contains(version)) {
      throw new IllegalArgumentException("Unsupported VDA5050 version: " + version);
    }
    return "2.1".equals(version) ? path.replace("/v2_0/", "/v2_1/") : path;
  }

  /**
   * A validator that accepts all JSON inputs.
   */
  private static class AcceptingAll
      extends
        MessageValidator {
    /**
     * Creates a new instance.
     */
    AcceptingAll() {
    }

    @Override
    public void validate(
        @Nonnull
        String json,
        @Nonnull
        Class<?> clazz
    ) {
      // Accepts all JSON inputs, i.e. never throws.
    }
  }
}
