package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.sqrt

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

/**
 * Calculates the time it takes to move in a straight line from pos to coords, given a speed expressed in seconds per block
 */
fun moveTime(pos: Vec3d, coords: BlockPos, secondsPerBlock: Double): Double {
    return sqrt(coords.getSquaredDistance(pos)) * secondsPerBlock
}

/**
 * Calculates the time it takes to sprint in a straight line from pos to coords
 */
fun sprintTime(pos: Vec3d, coords: BlockPos): Double {
    return moveTime(pos, coords, 1 / 5.612)
}

/**
 * Calculates the time it takes to walk in a straight line from pos to coords
 */
fun walkTime(pos: Vec3d, coords: BlockPos): Double {
    return moveTime(pos, coords, 1 / 4.317)
}