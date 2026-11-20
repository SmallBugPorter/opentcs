// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.kernel.extensions.servicewebapi.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.opentcs.components.kernel.services.InternalVehicleService;
import org.opentcs.data.model.Vehicle;
import org.opentcs.drivers.vehicle.VehicleCommAdapterMessage;
import org.opentcs.kernel.extensions.servicewebapi.common.KernelExecutorWrapper;
import org.opentcs.kernel.extensions.servicewebapi.v1.binding.PutVehicleSpeedLimitTO;

class SpeedLimitHandlerTest {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final InternalVehicleService service = mock();
  private final VehicleHandler handler = new VehicleHandler(service, mock(),
      new KernelExecutorWrapper(executor), mock());
  private Vehicle vehicle;

  @BeforeEach
  void setup() {
    vehicle = new Vehicle("agv-test").withState(Vehicle.State.ERROR)
        .withProperty("speedLimit:schemaVersion", "1")
        .withProperty("speedLimit:valid", "true")
        .withProperty("speedLimit:updatedAt", Instant.now().toString())
        .withProperty("speedLimit:maximumMps", "1.0")
        .withProperty("speedLimit:appliedMps", "0.8")
        .withProperty("speedLimit:requestedMps", "0.8")
        .withProperty("speedLimit:requestId", "")
        .withProperty("speedLimit:status", "applied");
    when(service.fetch(Vehicle.class, "agv-test")).thenAnswer(invocation -> Optional.of(vehicle));
  }

  @AfterEach
  void close() {
    executor.shutdownNow();
  }

  @Test
  void forwardsInMetresPerSecondWithoutClaimingAppliedOrChangingVehicleCap() {
    var result = handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(.4, "test"));
    assertThat(result).containsEntry("accepted", true).containsEntry("status", "requested")
        .containsEntry("requestId", "speed-limit-test");
    var captor = ArgumentCaptor.forClass(VehicleCommAdapterMessage.class);
    verify(service).sendCommAdapterMessage(eq(vehicle.getReference()), captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo("vda5050:sendInstantAction");
    assertThat(captor.getValue().getParameters())
        .containsEntry("actionType", "setSpeedLimit").containsEntry("blockingType", "NONE")
        .containsEntry("parameter.maxSpeedMps", "0.4");
    assertThat(handler.getVehicleSpeedLimit("agv-test")).containsEntry("appliedMps", "0.8")
        .containsEntry("maximumMps", "1.0");
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -1, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
  void invalidOrAboveCapCannotReachVehicle(double value) {
    assertThatThrownBy(() -> handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(value, "test")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(service, never()).sendCommAdapterMessage(any(), any());
  }

  @Test
  void staleOrMissingCapabilitiesAreRejected() {
    vehicle = vehicle.withProperty("speedLimit:updatedAt", Instant.now().minusSeconds(6).toString());
    assertThat(handler.getVehicleSpeedLimit("agv-test")).containsEntry("valid", "false");
    assertThatThrownBy(() -> handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(.4, "test")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(service, never()).sendCommAdapterMessage(any(), any());
  }

  @Test
  void pendingRequestAllowsOnlySameIdAndValue() {
    vehicle = vehicle.withProperty("speedLimit:status", "pending")
        .withProperty("speedLimit:requestId", "speed-limit-first").withProperty("speedLimit:requestedMps", "0.4");
    assertThatThrownBy(() -> handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(.5, "second")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(.5, "first")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(handler.putVehicleSpeedLimit("agv-test", new PutVehicleSpeedLimitTO(.4, "first")))
        .containsEntry("accepted", true);
  }
}
