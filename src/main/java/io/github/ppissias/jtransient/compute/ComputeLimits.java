/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.compute;

/**
 * Effective motion and time bounds applied to a faint-mover search plan.
 *
 * @param maxMotions maximum coarse trial motions admitted by the plan
 * @param timeLimitSeconds search time limit in seconds
 */
public record ComputeLimits(int maxMotions, double timeLimitSeconds) {
}
