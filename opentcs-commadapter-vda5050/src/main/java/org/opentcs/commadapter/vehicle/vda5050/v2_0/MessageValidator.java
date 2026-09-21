// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import jakarta.annotation.Nonnull;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.opentcs.commadapter.vehicle.vda5050.common.JsonValidator;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.connection.Connection;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.instantactions.InstantActions;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.order.Order;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.state.State;
import org.opentcs.commadapter.vehicle.vda5050.v2_0.message.visualization.Visualization;

/**
 * A {@link JsonValidator} for the selected VDA5050 wire protocol.
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
    this(ProtocolVersion.V2_0);
  }

  /**
   * Creates a validator for a supported wire protocol.
   *
   * @param version The wire protocol version.
   */
  public MessageValidator(ProtocolVersion version) {
    super(
        Map.of(
            Connection.class, schemaReader(version, "connection"),
            InstantActions.class, schemaReader(version, "instantActions"),
            Order.class, schemaReader(version, "order"),
            State.class, schemaReader(version, "state"),
            Visualization.class, schemaReader(version, "visualization")
        )
    );
  }

  private static Reader schemaReader(ProtocolVersion version, String topic) {
    Reader reader = new InputStreamReader(
        Objects.requireNonNull(
            MessageValidator.class.getResourceAsStream(
                version.schemaDirectory() + topic + ".schema.json"
            )
        ), StandardCharsets.UTF_8
    );
    if (version == ProtocolVersion.V2_0) {
      return reader;
    }
    // The vendored schemas only use keywords with the same semantics in draft-07.
    // Everit does not recognize the upstream 2020-12 dialect URI.
    try (reader) {
      JSONObject schema = new JSONObject(new JSONTokener(reader));
      schema.put("$schema", "http://json-schema.org/draft-07/schema#");
      return new StringReader(schema.toString());
    }
    catch (java.io.IOException exc) {
      throw new IllegalArgumentException("Cannot read schema for " + topic, exc);
    }
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
