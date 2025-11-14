package org.nguhroutes.nguhroutes.client

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.roundToInt

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
fun moveTime(a: Vec3d, b: Vec3d, secondsPerBlock: Double): Double {
    return a.distanceTo(b) * secondsPerBlock
}

/**
 * Calculates the time it takes to sprint in a straight line from pos to coords
 */
fun sprintTime(a: Vec3d, b: Vec3d): Double {
    return moveTime(a, b, 1 / 5.612)
}

/**
 * Calculates the time it takes to walk in a straight line from pos to coords
 */
fun walkTime(a: Vec3d, b: Vec3d): Double {
    return moveTime(a, b, 1 / 4.317)
}

/**
 * Formats a distance in metres or kilometres.
 */
fun prettyDist(dist: Double): String {
    return if (dist < 1000) {
        "${dist.roundToInt()} m"
    } else {
        String.format("%.1f km", dist / 1000)
    }
}