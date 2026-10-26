/*
 * JTransient
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 */
package io.github.ppissias.jtransient.engine;

import java.util.List;

/** The first inspection pass: the kept reference and the transients of each kept frame. */
public record TransientInspection(TransientReference reference, List<FrameTransients> frames) {
}
