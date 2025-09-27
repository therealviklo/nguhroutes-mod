package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos

/**
 * Returns true if the distance between a and b is less than dist
 */
fun distLess(a: BlockPos, b: BlockPos, dist: Int): Boolean {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return dx * dx + dy * dy + dz * dz <= dist * dist
}

/**
 * Returns true if p is closer to a than b
 */
fun distCloser(p: BlockPos, a: BlockPos, b: BlockPos): Boolean {
    val dxa = p.x - a.x
    val dya = p.y - a.y
    val dza = p.z - a.z
    val dxb = p.x - b.x
    val dyb = p.y - b.y
    val dzb = p.z - b.z
    return dxa * dxa + dya * dya + dza * dza <= dxb * dxb + dyb * dyb + dzb * dzb
}