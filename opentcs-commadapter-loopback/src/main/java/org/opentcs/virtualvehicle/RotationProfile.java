// SPDX-FileCopyrightText: The openTCS Authors
// SPDX-License-Identifier: MIT
package org.opentcs.virtualvehicle;

/** Rest-to-rest, shortest-angle triangular/trapezoidal rotation, in degrees and seconds. */
final class RotationProfile {

  private final double start;
  private final double delta;
  private final double acceleration;
  private final double deceleration;
  private final double peak;
  private final double accelerationTime;
  private final double cruiseTime;
  private final double duration;

  RotationProfile(
      double start, double target, double velocity, double acceleration,
      double deceleration
  ) {
    if (!Double.isFinite(start) || !Double.isFinite(target)
        || !positive(velocity) || !positive(acceleration) || !positive(deceleration)) {
      throw new IllegalArgumentException("Angles must be finite and angular limits positive");
    }
    this.start = normalize(start);
    this.delta = normalize(target - start);
    this.acceleration = acceleration;
    this.deceleration = deceleration;
    double distance = Math.abs(delta);
    peak = Math.min(velocity, Math.sqrt(2 * distance / (1 / acceleration + 1 / deceleration)));
    accelerationTime = peak / acceleration;
    double rampDistance = peak * peak / (2 * acceleration) + peak * peak / (2 * deceleration);
    cruiseTime = peak == 0 ? 0 : Math.max(0, (distance - rampDistance) / peak);
    duration = accelerationTime + cruiseTime + peak / deceleration;
  }

  static boolean positive(double value) {
    return Double.isFinite(value) && value > 0;
  }

  static double normalize(double angle) {
    return ((angle + 180) % 360 + 360) % 360 - 180;
  }

  static double heading(double dx, double dy, boolean backward) {
    return normalize(Math.toDegrees(Math.atan2(dy, dx)) + (backward ? 180 : 0));
  }

  double duration() {
    return duration;
  }

  double angleAt(double time) {
    double t = Math.max(0, Math.min(time, duration));
    double distance;
    if (t <= accelerationTime) {
      distance = acceleration * t * t / 2;
    }
    else if (t <= accelerationTime + cruiseTime) {
      distance = peak * peak / (2 * acceleration) + peak * (t - accelerationTime);
    }
    else {
      double remaining = duration - t;
      distance = Math.abs(delta) - deceleration * remaining * remaining / 2;
    }
    return normalize(start + Math.signum(delta) * distance);
  }

  double velocityAt(double time) {
    double t = Math.max(0, time);
    if (t >= duration) {
      return 0;
    }
    double velocity = t <= accelerationTime ? acceleration * t
        : t <= accelerationTime + cruiseTime ? peak : deceleration * (duration - t);
    return Math.signum(delta) * velocity;
  }
}
