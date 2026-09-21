# VDA5050 protocol support in this checkout

This checkout supports VDA5050 1.1, 2.0, 2.1 and 3.0 for openTCS's existing
node/edge route execution. Versions 2.0, 2.1 and 3.0 share the route adapter;
`ProtocolVersion` and `WireMessageMapper` define the version boundary.

## Configuration

The kernel setting `commadapter.vehicle.vda5050.enabledVersions` is an allowlist:

```properties
commadapter.vehicle.vda5050.enabledVersions=1.1, 2.0, 2.1, 3.0
commadapter.vehicle.vda5050.mqtt.brokerHost=localhost
commadapter.vehicle.vda5050.mqtt.brokerPort=1883
```

Set these **vehicle properties** in the plant model, then attach and enable the
VDA5050 adapter in the Kernel Control Center:

```text
vda5050:version = 3.0
vda5050:interfaceName = vda5050
vda5050:manufacturer = MyManufacturer
vda5050:serialNumber = Robot001
vda5050:mapId = MyMap
```

Use `2.1` for a 2.1 vehicle. Configuration values are major.minor; outgoing message
headers use `2.1.0` or `3.0.0`. Incoming messages must match the configured
major.minor version (patch numbers may differ). Topic prefixes use the major
version, e.g. `vda5050/v3/MyManufacturer/Robot001` for 3.0 and
`vda5050/v2/MyManufacturer/Robot001` for 2.1. The interface name must match the
vehicle. An explicit `vda5050:topicPrefix` still takes precedence.

The legacy 2.0 integration guide's node IDs, map IDs, custom actions, typed action
parameters, optional-parameter filters and MQTT settings still apply. The
optional-parameter filter uses the legacy/internal field names before conversion
to the 3.0 wire format, e.g. `edges.maxSpeed`, not `edges.maximumSpeed`.
Restart the kernel and reattach the adapter after changing the protocol version.

## Implemented behavior

- Per-vehicle protocol selection and per-version kernel enable/disable settings.
- Version-specific schemas for connection, state, visualization, order and
  instantActions. Incoming wire JSON is validated before conversion; outgoing
  JSON is validated after conversion, even if incoming validation is disabled.
- 3.0 order field names, orientation-before-entry behavior and circular node
  tolerances expressed as ellipses with equal semi-axes.
- 3.0 position/localization, power supply and emergency-stop field names mapped
  to the existing process model.
- 3.0 `instantActionStates` and `zoneActionStates` joined with order action states
  for the existing action-ID-based tracker. Cancellation waits for a terminal
  acknowledgement before allowing a subsequent order.
- New connection states, operating modes, `RETRIABLE` action status, `SINGLE`
  order-action blocking type, `URGENT`/`CRITICAL` errors and order rejection types.
  `RETRIABLE` remains pending; `CRITICAL` maps to openTCS ERROR. STARTUP,
  INTERVENED and TEACH_IN are not reported as idle/available. INTERVENED permits
  queued orders and cancellation, but blocks other instant actions.
- 3.0 instant actions use `NONE`, as required by the protocol. The legacy
  `initPosition` action becomes `initializePosition`; its x/y/theta parameters
  are sent as numbers. Other user-defined action parameters are preserved.
- Static driving corridors for 2.1/3.0, using the following **path properties**:

```text
vda5050:corridor.leftWidth = 0.25
vda5050:corridor.rightWidth = 0.25
vda5050:corridor.corridorReferencePoint = KINEMATIC_CENTER
```

Widths are in metres and both must be specified, finite and non-negative.
The optional reference point is `KINEMATIC_CENTER` or `CONTOUR`. Left/right
are relative to the direction of traversal, including reverse traversal.
Corridor fields are omitted for 2.0 vehicles sharing the same model.

## Scope and remaining extensions

This is route-based protocol integration, not an implementation of every optional
feature of VDA5050 3.0. The adapter does not implement free-navigation scheduling,
zone-set storage, dynamic zone/edge release negotiation, planned/intermediate
path conflict resolution, automatic map distribution or factsheet capability
negotiation. It does not grant zone/edge release requests. Do not configure robot
routes that require these negotiations with this adapter.

Map download/enable/delete actions can be sent using the existing custom instant
message mechanism; their state details are not exposed through dedicated UI
panels. Factsheets are still ignored as in the existing 2.0 adapter. RETRIABLE
states are retained but retry policy is not automated. Ellipse tolerances use
circles derived from the existing openTCS radius; independent ellipse axes are
not configurable. No real-robot interoperability certification is implied.

## Schema provenance and validation

Schemas are vendored from the official release tags:

- https://github.com/VDA5050/VDA5050/tree/2.1.0/json_schemas
- https://github.com/VDA5050/VDA5050/tree/3.0.0/json_schemas

They are MIT-licensed; source license copies accompany them. The invalid trailing
comma in the 3.0 order schema was removed. The schemas use a subset compatible
with draft-07, so the loader changes only the dialect URI for the existing Everit
validator. Upstream schema updates must be checked for new 2020-12-only keywords
before replacing these files.

Run regression tests with:

```bash
bash ./gradlew :opentcs-commadapter-vda5050:test
```

`WireProtocolTest` exercises real serializers and schemas through the adapter's
MQTT callbacks with a mocked MQTT transport; it does not require a broker or a
physical vehicle. It covers order publication, state reception, version isolation,
new wire fields and cancellation sequencing. Real-robot commissioning remains
necessary for a particular vehicle's maps and action semantics.
