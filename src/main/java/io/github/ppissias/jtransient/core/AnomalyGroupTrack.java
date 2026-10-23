/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.core;

import io.github.ppissias.jtransient.model.Track;

/**
 * A group of rescued same-frame anomalies, offered to the streak consolidation and never exported. A merge or
 * copy of two such groups stays one; anything merged with a real track becomes an ordinary track.
 */
final class AnomalyGroupTrack extends Track {
}
