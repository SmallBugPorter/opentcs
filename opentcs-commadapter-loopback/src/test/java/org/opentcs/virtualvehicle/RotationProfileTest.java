// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.virtualvehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RotationProfileTest {

  @Test
  void straightHasNoDelayAndRightAngleHasTrapezoidalProfile() {
    assertEquals(0, new RotationProfile(40, 400, 90, 180, 180).duration());
    var p = new RotationProfile(0, 90, 90, 180, 180);
    assertEquals(1.5, p.duration(), 1e-9);
    assertEquals(22.5, p.angleAt(.5), 1e-9);
    assertEquals(90, p.velocityAt(.5), 1e-9);
    assertEquals(67.5, p.angleAt(1), 1e-9);
    assertEquals(90, p.angleAt(5), 1e-9);
    assertEquals(0, p.velocityAt(1.5), 1e-9);
  }

  @Test
  void smallAngleUsesTriangularProfileAndShortestWrap() {
    var p = new RotationProfile(350, 10, 90, 180, 180);
    assertEquals(2 * Math.sqrt(20.0 / 180), p.duration(), 1e-9);
    assertEquals(0, p.angleAt(p.duration() / 2), 1e-9);
    assertEquals(10, p.angleAt(p.duration()), 1e-9);
    assertTrue(p.velocityAt(p.duration() / 2) < 90);
    var reverse = new RotationProfile(10, 350, 90, 180, 180);
    assertTrue(reverse.velocityAt(.1) < 0);
    assertEquals(-10, reverse.angleAt(reverse.duration()), 1e-9);
  }

  @Test
  void asymmetricAccelerationAndDecelerationRespectLimits() {
    var p = new RotationProfile(0, 180, 90, 90, 180);
    assertEquals(2.75, p.duration(), 1e-9);
    assertEquals(-180, p.angleAt(p.duration()), 1e-9);
    assertEquals(0, p.velocityAt(p.duration()), 1e-9);
    for (double t = 0; t < p.duration(); t += .01) {
      assertTrue(Math.abs(p.velocityAt(t)) <= 90 + 1e-9);
    }
  }

  @Test
  void reverseTravelKeepsVehicleNoseOppositeTravelDirection() {
    assertEquals(90, RotationProfile.heading(0, 1, false));
    assertEquals(-90, RotationProfile.heading(0, 1, true));
    assertEquals(0, RotationProfile.heading(-1, 0, true));
  }

  @Test
  void invalidLimitsAreRejected() {
    for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class, () -> new RotationProfile(0, 90, value, 180, 180)
      );
      assertThrows(
          IllegalArgumentException.class, () -> new RotationProfile(0, 90, 90, value, 180)
      );
      assertThrows(
          IllegalArgumentException.class, () -> new RotationProfile(0, 90, 90, 180, value)
      );
    }
  }
}
