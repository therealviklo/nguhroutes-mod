package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.net.URI
import java.util.concurrent.atomic.AtomicReference


class NguhroutesClient : ClientModInitializer {
    val jsonData: AtomicReference<JsonData?> = AtomicReference(null)
    val error: AtomicReference<String?> = AtomicReference(null)
    val currRoutePair: AtomicReference<Pair<Route, Int>?> = AtomicReference(null)

    init {
        loadJson()
    }

    private fun downloadJson(url: String): JsonElement {
        val url = URI(url).toURL()
        val data = url.openStream().readAllBytes().decodeToString()
        return Json.parseToJsonElement(data)
    }

    fun loadJson() {
        Thread {
            try {
                val routesJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/routes.json")
                val networkJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/network.json")
                jsonData.set(JsonData(routesJson, networkJson))
            } catch (e: Exception) {
                error.set(e.toString())
            }
        }.start()
    }

    private fun registerCommand(command: LiteralArgumentBuilder<FabricClientCommandSource?>) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource?>?, _/*registryAccess*/: CommandRegistryAccess? ->
            dispatcher!!.register(command)
        })
    }

    override fun onInitializeClient() {
        registerCommand(ClientCommandManager.literal("nr-error")
            .executes { context: CommandContext<FabricClientCommandSource?>? ->
                val error = error.get()
                if (error != null) {
                    context!!.getSource()!!.sendFeedback(Text.literal(error))
                } else {
                    context!!.getSource()!!.sendFeedback(Text.literal("No errors."))
                }
                1
            })
        registerCommand(ClientCommandManager.literal("nr")
            .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                .executes { context: CommandContext<FabricClientCommandSource?>? ->
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    val jsonData = jsonData.get()
                    if (jsonData == null) {
                        context!!.getSource()!!.sendFeedback(Text.literal("Data has not loaded yet."))
                        return@executes 1
                    }
//                    Thread {
                        val playerPos = context?.source?.player?.pos
                        if (playerPos == null) {
                            context!!.getSource()!!.sendFeedback(Text.literal("Could not get player position."))
//                            return@Thread
                            return@executes 1
                        }

                        var fastestRoute: PreCalcRoute? = null
                        var fastestRouteStart: String? = null
                        var fastestRouteTime: Double = Double.POSITIVE_INFINITY
                        for (route in jsonData.routes.routes) {
                            if (route.value.conns.isEmpty())
                                continue
                            val routeCodes = route.key.split('`')
                            if (routeCodes.getOrNull(1) == dest &&
                                context.source!!.player.clientWorld.registryKey.value == Identifier.of(getDim(routeCodes[0]))) {
                                val firstStopCoords = if (route.value.conns[0].line == "Interdimensional transfer") {
                                    jsonData.network.findAverageStationCoords(routeCodes[0])
                                } else {
                                    jsonData.network.getStop(routeCodes[0], route.value.conns[0].line).coords
                                }
                                // Add the time it takes to sprint to the stop
                                val time = route.value.time + sprintTime(playerPos, firstStopCoords)
                                if (time < fastestRouteTime) {
                                    fastestRoute = route.value
                                    fastestRouteStart = routeCodes[0]
                                    fastestRouteTime = time
                                }
                            }
                        }
                        // Check if just sprinting there is faster
                        val directTime = sprintTime(playerPos, jsonData.network.findAverageStationCoords(dest))
                        if (directTime < fastestRouteTime) {
                            fastestRoute = PreCalcRoute(0.0, listOf())
                            fastestRouteStart = dest
                            fastestRouteTime = directTime
                        }
                        if (fastestRoute == null) {
                            context.getSource()!!.sendFeedback(Text.literal("Could not find route."))
//                            return@Thread
                            return@executes 1
                        }
                        val routeObj = Route(fastestRouteStart!!, fastestRoute.conns, jsonData.network)
                        currRoutePair.set(Pair(routeObj, 0))
                        for (stop in routeObj.stops) {
                            context!!.getSource()!!.sendFeedback(Text.literal("${stop.code} (${stop.line}) (${stop.coords})"))
                        }
                        sendNextStopMessage(routeObj.stops[0])
//                    }.start()
                    1
                }))
        ClientTickEvents.END_WORLD_TICK.register { clientWorld -> tick(clientWorld) }
    }

    private fun tick(clientWorld: ClientWorld) {
        val currRoutePair = currRoutePair.get() ?: return
        val currRoute = currRoutePair.first
        val currStop = currRoutePair.second
        if (currStop >= currRoute.stops.size) {
            this.currRoutePair.compareAndSet(currRoutePair, null)
            return
        }
        val player = MinecraftClient.getInstance().player ?: return
        val playerCoords = player.blockPos
        if (distLess(playerCoords, currRoute.stops[currStop].coords, 50) &&
            clientWorld.registryKey.value == Identifier.of(currRoute.stops[currStop].dimension) &&
            (currStop == 0 || currRoute.stops[currStop].dimension != currRoute.stops[currStop - 1].dimension || distCloser(
                playerCoords,
                currRoute.stops[currStop].coords,
                currRoute.stops[currStop - 1].coords))) {
            if (!this.currRoutePair.compareAndSet( currRoutePair, Pair(currRoute, currStop + 1)))
                return
            if (currStop + 1 < currRoute.stops.size) {
                val nextStop = currRoute.stops[currStop + 1]
                sendNextStopMessage(nextStop)
            } else {
                sendRouteMessage("Route finished!")
            }
        }
    }

    private fun sendNextStopMessage(stop: RouteStop) {
        sendRouteMessage("Next: ${stop.code} (${stop.line})")
    }

    private fun sendRouteMessage(msg: String) {
        val player = MinecraftClient.getInstance().player ?: return
        val text = Text.of(msg)
        player.sendMessage(text, true)
        player.sendMessage(text, false)
    }
}
