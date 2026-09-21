// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050.v2_0;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Converts version-specific wire fields to/from the shared route-based message model.
 * Validation is performed on the wire representation, before incoming conversion and after
 * outgoing conversion. User-defined action parameter names and values are left untouched.
 */
public class WireMessageMapper {
  private final ProtocolVersion version;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Creates a mapper.
   *
   * @param version The vehicle's configured protocol.
   */
  public WireMessageMapper(ProtocolVersion version) {
    this.version = requireNonNull(version, "version");
  }

  /**
   * Converts an incoming message, rejecting messages for another major/minor version.
   *
   * @param json The wire JSON.
   * @return JSON consumable by the shared message model.
   */
  public String fromWire(String json) {
    try {
      JsonNode parsed = mapper.readTree(json);
      if (!(parsed instanceof ObjectNode message)) {
        throw new IllegalArgumentException("Expected a JSON object");
      }
      String receivedVersion = message.path("version").asText();
      if (!receivedVersion.matches(
          java.util.regex.Pattern.quote(version.configurationValue())
              + "\\.[0-9]+"
      )) {
        throw new IllegalArgumentException("Unexpected VDA5050 version: " + receivedVersion);
      }
      if (version == ProtocolVersion.V3_0) {
        readV3(message);
      }
      return message.toString();
    }
    catch (JsonProcessingException exc) {
      throw new IllegalArgumentException("Cannot parse VDA5050 message", exc);
    }
  }

  /**
   * Converts a serialized order or instant-actions message to the selected wire format.
   *
   * @param input The serialized shared message model.
   * @return The wire representation.
   */
  public JsonNode toWire(JsonNode input) {
    if (version == ProtocolVersion.V2_0) {
      JsonNode message = input.deepCopy();
      for (JsonNode edge : message.path("edges")) {
        ((ObjectNode) edge).remove("corridor");
      }
      return message;
    }
    if (version == ProtocolVersion.V2_1) {
      return input;
    }
    ObjectNode message = (ObjectNode) input.deepCopy();
    message.remove("zoneSetId");
    for (JsonNode node : message.path("nodes")) {
      rename(node, "nodeDescription", "nodeDescriptor");
      JsonNode position = node.path("nodePosition");
      if (position instanceof ObjectNode object) {
        object.remove("mapDescription");
        JsonNode deviation = object.get("allowedDeviationXY");
        if (deviation != null && deviation.isNumber()) {
          ObjectNode ellipse = mapper.createObjectNode();
          ellipse.set("a", deviation);
          ellipse.set("b", deviation);
          ellipse.put("theta", 0.0);
          object.set("allowedDeviationXY", ellipse);
        }
      }
      writeActions(node.path("actions"), false);
    }
    for (JsonNode edge : message.path("edges")) {
      rename(edge, "edgeDescription", "edgeDescriptor");
      rename(edge, "maxSpeed", "maximumSpeed");
      rename(edge, "maxHeight", "maximumMobileRobotHeight");
      rename(edge, "minHeight", "minimumLoadHandlingDeviceHeight");
      ObjectNode object = (ObjectNode) edge;
      object.remove(List.of("startNodeId", "endNodeId"));
      JsonNode rotation = object.remove("rotationAllowed");
      if (rotation != null && !rotation.isNull()) {
        object.put("reachOrientationBeforeEntering", !rotation.asBoolean());
      }
      writeActions(edge.path("actions"), false);
    }
    writeActions(message.path("actions"), true);
    return message;
  }

  private void readV3(ObjectNode message) {
    rename(message, "mobileRobotPosition", "agvPosition");
    rename(message.path("agvPosition"), "localized", "positionInitialized");
    rename(message, "powerSupply", "batteryState");
    rename(message.path("batteryState"), "stateOfCharge", "batteryCharge");
    rename(message.path("safetyState"), "activeEmergencyStop", "eStop");
    if ("TEACH_IN".equals(message.path("operatingMode").asText())) {
      message.put("operatingMode", "TEACHIN");
    }
    if ("CONNECTION_BROKEN".equals(message.path("connectionState").asText())) {
      message.put("connectionState", "CONNECTIONBROKEN");
    }
    // The shared matcher tracks instant and order actions by their unique action IDs.
    if (message.get("actionStates") instanceof ArrayNode actions) {
      for (String name : List.of("instantActionStates", "zoneActionStates")) {
        for (JsonNode action : message.path(name)) {
          actions.add(action);
        }
      }
      for (JsonNode action : actions) {
        rename(action, "actionDescriptor", "actionDescription");
        rename(action, "actionResult", "resultDescription");
      }
    }
    for (JsonNode node : message.path("nodeStates")) {
      rename(node, "nodeDescriptor", "nodeDescription");
    }
    for (JsonNode edge : message.path("edgeStates")) {
      rename(edge, "edgeDescriptor", "edgeDescription");
    }
    for (JsonNode info : message.path("information")) {
      rename(info, "infoDescriptor", "infoDescription");
    }
  }

  private void writeActions(JsonNode actions, boolean instant) {
    for (JsonNode action : actions) {
      rename(action, "actionDescription", "actionDescriptor");
      ObjectNode object = (ObjectNode) action;
      if (instant) {
        object.put("blockingType", "NONE");
      }
      if ("initPosition".equals(action.path("actionType").asText())) {
        object.put("actionType", "initializePosition");
        for (JsonNode parameter : action.path("actionParameters")) {
          if (List.of("x", "y", "theta").contains(parameter.path("key").asText())
              && parameter.path("value").isTextual()) {
            double value = Double.parseDouble(parameter.path("value").asText());
            if (!Double.isFinite(value)) {
              throw new IllegalArgumentException("Position parameters must be finite");
            }
            ((ObjectNode) parameter).put("value", value);
          }
        }
      }
    }
  }

  private static void rename(JsonNode node, String from, String to) {
    if (node instanceof ObjectNode object) {
      JsonNode value = object.remove(from);
      if (value != null) {
        object.set(to, value);
      }
    }
  }
}
