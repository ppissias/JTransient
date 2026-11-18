// OpenCL C 1.2 only (no OpenCL 2.x features).
// Byte-identical to FaintMoverSearch's coarse search loop:
//   * per (pixel, motion), coarse planes are summed in order g = 0..G-1 starting from 0.0f
//   * score is one float32 multiply acc * scale; scale is computed in Java
//   * tie-breaking matches ascending original Java motion index via origMotion[]
#pragma OPENCL FP_CONTRACT OFF

#ifndef WGX
#define WGX 32
#endif
#ifndef WGY
#define WGY 8
#endif
#ifndef MB
#define MB 16
#endif
#ifndef LMAX
#define LMAX 4096
#endif
#ifndef SHIFT_T
#define SHIFT_T short
#endif

#define PLANE(g) ((g) < g1 ? b0 + (size_t) (g) * planeSize \
               : (g) < g2 ? b1 + (size_t) ((g) - g1) * planeSize \
               : (g) < g3 ? b2 + (size_t) ((g) - g2) * planeSize \
               :            b3 + (size_t) ((g) - g3) * planeSize)
#define BANK_ARGS __global const float* restrict b0, __global const float* restrict b1, \
                  __global const float* restrict b2, __global const float* restrict b3, \
                  const int g1, const int g2, const int g3

#ifdef BUILD_SIMPLE

// Reference/fallback kernel: one work-item per coarse pixel over permuted motions [m0, m1).
// Built in its own program without reqd_work_group_size so devices with smaller limits can still run.
__kernel void coarse_simple(BANK_ARGS, const int planeSize, const int stride, const int margin,
                            const int cw, const int ch, const int G, const int totalMotions,
                            __global const SHIFT_T* restrict shiftX,
                            __global const SHIFT_T* restrict shiftY,
                            __global const int* restrict origMotion,
                            const int m0, const int m1, const float scale,
                            __global float* best, __global int* bestMotion) {
    const int x = get_global_id(0), y = get_global_id(1);
    if (x >= cw || y >= ch) return;
    const int i = y * cw + x;
    float b = best[i];
    int bm = bestMotion[i];
    const int base = (y + margin) * stride + margin + x;
    for (int m = m0; m < m1; m++) {
        float acc = 0.0f;
        for (int g = 0; g < G; g++) {
            const size_t idx = (size_t) g * totalMotions + m;
            acc += PLANE(g)[base + (int) shiftY[idx] * stride + (int) shiftX[idx]];
        }
        const float v = acc * scale;
        const int om = origMotion[m];
        if (v > b || (v == b && v > -INFINITY && om < bm)) {
            b = v;
            bm = om;
        }
    }
    best[i] = b;
    bestMotion[i] = bm;
}

// Merges Z-slice partial maxima into the running best, keeping the lowest original motion index on ties.
__kernel void reduce_blocks(__global const float* restrict partBest,
                            __global const int* restrict partMotion,
                            const int slices, const int pixels,
                            __global float* best, __global int* bestMotion) {
    const int i = get_global_id(0);
    if (i >= pixels) return;
    float b = best[i];
    int bm = bestMotion[i];
    for (int k = 0; k < slices; k++) {
        const float v = partBest[(size_t) k * pixels + i];
        const int km = partMotion[(size_t) k * pixels + i];
        if (v > b || (v == b && v > -INFINITY && km >= 0 && km < bm)) {
            b = v;
            bm = km;
        }
    }
    best[i] = b;
    bestMotion[i] = bm;
}

#endif

#ifdef BUILD_BLOCKED

// Production kernel: WGX x WGY pixel work-group looping over motion blocks assigned to slice z.
// Shifts are transposed [g * totalMotions + m] and motions are ordered into compact 2D tiles.
__kernel __attribute__((reqd_work_group_size(WGX, WGY, 1)))
void coarse_blocked(BANK_ARGS, const int planeSize, const int stride, const int margin,
                    const int cw, const int ch, const int G, const int totalMotions,
                    __global const SHIFT_T* restrict shiftX,
                    __global const SHIFT_T* restrict shiftY,
                    __global const int* restrict origMotion,
                    __global const int* restrict box,
                    const int blk0, const int blkEnd, const int gridZ, const float scale,
                    __global float* partBest, __global int* partMotion) {
    __local float win[LMAX];
    __local int sx_l[MB];
    __local int sy_l[MB];
    const int lx = get_local_id(0), ly = get_local_id(1);
    const int x0 = get_group_id(0) * WGX, y0 = get_group_id(1) * WGY;
    const int x = x0 + lx, y = y0 + ly;
    const int z = get_global_id(2);
    const int lid = ly * WGX + lx;
    const bool inside = (x < cw && y < ch);

    float bestVal = -INFINITY;
    int bestIdx = -1;

    for (int blk = blk0 + z; blk < blkEnd; blk += gridZ) {
        const int mFirst = blk * MB;
        const int nm = min(MB, totalMotions - mFirst);
        float acc[MB];
        for (int j = 0; j < MB; j++) acc[j] = 0.0f;

        for (int g = 0; g < G; g++) {
            __global const float* plane = PLANE(g);
            __global const int* bx = box + ((size_t) blk * G + g) * 4;
            const int minSx = bx[0], minSy = bx[1];
            const int ww = WGX + bx[2], wh = WGY + bx[3];
            if (ww * wh <= LMAX) {
                const int ox = margin + x0 + minSx, oy = margin + y0 + minSy;
                barrier(CLK_LOCAL_MEM_FENCE);
                if (lid < nm) {
                    const size_t idx = (size_t) g * totalMotions + mFirst + lid;
                    sx_l[lid] = (int) shiftX[idx] - minSx;
                    sy_l[lid] = (int) shiftY[idx] - minSy;
                }
                for (int wy = ly; wy < wh; wy += WGY) {
                    const int py = oy + wy;
                    for (int wx = lx; wx < ww; wx += WGX) {
                        const int p = py * stride + ox + wx;
                        win[wy * ww + wx] = (p >= 0 && p < planeSize) ? plane[p] : 0.0f;
                    }
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                for (int j = 0; j < nm; j++) {
                    acc[j] += win[(ly + sy_l[j]) * ww + lx + sx_l[j]];
                }
            } else {
                const size_t gBase = (size_t) g * totalMotions + mFirst;
                const int pBase = (y + margin) * stride + margin + x;
                for (int j = 0; j < nm; j++) {
                    const int sx = (int) shiftX[gBase + j];
                    const int sy = (int) shiftY[gBase + j];
                    const int p = pBase + sy * stride + sx;
                    acc[j] += inside ? plane[p] : 0.0f;
                }
            }
        }

        if (inside) {
            for (int j = 0; j < nm; j++) {
                const float v = acc[j] * scale;
                const int om = origMotion[mFirst + j];
                if (v > bestVal || (v == bestVal && v > -INFINITY && (bestIdx < 0 || om < bestIdx))) {
                    bestVal = v;
                    bestIdx = om;
                }
            }
        }
    }

    if (!inside) return;
    const size_t o = (size_t) z * cw * ch + (size_t) y * cw + x;
    partBest[o] = bestVal;
    partMotion[o] = bestIdx;
}

#endif
