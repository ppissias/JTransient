/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jtransient.gpu;

import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

/**
 * Package-private pairing of an {@link OpenClDevice} descriptor with its live JOCL platform and device handles.
 *
 * @param info JOCL-free device metadata
 * @param platform JOCL platform handle
 * @param device JOCL device handle
 */
record OpenClNativeDevice(OpenClDevice info, cl_platform_id platform, cl_device_id device) {
}
