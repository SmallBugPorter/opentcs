// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.commadapter.vehicle.vda5050;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentcs.data.model.Vehicle;

class CommAdapterFactoryImplTest {
  @Test
  void version21MustBeExplicitlyEnabled() {
    CommAdapterConfiguration config = mock(CommAdapterConfiguration.class);
    Vda5050CommAdapterFactory shared = mock(Vda5050CommAdapterFactory.class);
    Vehicle vehicle = new Vehicle("agv-001").withProperty("vda5050:version", "2.1");
    when(shared.providesAdapterFor(vehicle)).thenReturn(true);
    when(config.enabledVersions()).thenReturn(List.of("2.0"));
    assertThat(new CommAdapterFactoryImpl(config, shared, shared).providesAdapterFor(vehicle))
        .isFalse();
    when(config.enabledVersions()).thenReturn(List.of("2.1"));
    assertThat(new CommAdapterFactoryImpl(config, shared, shared).providesAdapterFor(vehicle))
        .isTrue();
    assertThat(
        new CommAdapterFactoryImpl(config, shared, shared)
            .providesAdapterFor(new Vehicle("unconfigured"))
    ).isFalse();
  }
}
