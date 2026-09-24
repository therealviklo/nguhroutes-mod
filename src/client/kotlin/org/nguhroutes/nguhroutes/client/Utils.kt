package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.net.URI
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
fun moveTime(a: Vec3, b: Vec3, secondsPerBlock: Double): Double {
    return a.distanceTo(b) * secondsPerBlock
}

/**
 * Calculates the time it takes to sprint in a straight line from pos to coords
 */
fun sprintTime(a: Vec3, b: Vec3): Double {
    return moveTime(a, b, 1 / 5.612)
}

/**
 * Calculates the time it takes to walk in a straight line from pos to coords
 */
fun walkTime(a: Vec3, b: Vec3): Double {
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

/**
 * Download JSON from a URL
 */
fun downloadJson(url: String): JsonElement {
    val url = URI(url).toURL()
    val data = url.openStream().readAllBytes().decodeToString()
    return Json.parseToJsonElement(data)
}

val dimNames = mapOf("overworld" to "Overworld", "the_nether" to "Nether")

/**
 * Returns the name of the dimension with a certain identifier.
 */
fun dimName(identifier: String): String {
    return dimNames[identifier] ?: identifier
}

/**
 * Checks if two y-coords are on the same side of the Nether ceiling.
 */
fun sameSideOfCeil(y1: Double, y2: Double): Boolean {
    return !((y1 < 128.0) xor (y2 < 128.0))
}

/**
 * Sends a message to chat from the main thread
 */
fun sendFeedback(context: CommandContext<FabricClientCommandSource>, msg: Component) {
    Minecraft.getInstance().execute {
        context.source.sendFeedback(msg)
    }
}

/**
 * Sends a chat message to the player from the main thread
 */
fun sendPlayerSystemMessage(player: LocalPlayer, msg: Component) {
    Minecraft.getInstance().execute {
        player.sendSystemMessage(msg)
    }
}

/**
 * Sends an error message to chat from the main thread
 */
fun sendError( context: CommandContext<FabricClientCommandSource>, msg: Component) {
    Minecraft.getInstance().execute {
        context.source.sendError(msg)
    }
}