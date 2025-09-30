package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
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
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.net.URI
import java.util.concurrent.atomic.AtomicReference


class NguhroutesClient : ClientModInitializer {
    // TODO: nicer way of doing this?
    val jsonDataLoadError: AtomicReference<Pair<JsonData?, String?>> = AtomicReference(Pair(null, null))
    val currRoutePair: AtomicReference<Pair<Route, Int>?> = AtomicReference(null)

    init {
        loadJson(false)
    }

    private fun downloadJson(url: String): JsonElement {
        val url = URI(url).toURL()
        val data = url.openStream().readAllBytes().decodeToString()
        return Json.parseToJsonElement(data)
    }

    fun loadJson(noNether: Boolean) {
        val nullPair = Pair(null, null)
        jsonDataLoadError.set(nullPair)
        currRoutePair.set(null)
        Thread {
            try {
                val routesJson = if (noNether) {
                    downloadJson("https://nguhroutes.viklo.workers.dev/json/routes_no_nether.json")
                } else {
                    downloadJson("https://nguhroutes.viklo.workers.dev/json/routes.json")
                }
                val networkJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/network.json")
                jsonDataLoadError.compareAndSet(nullPair, Pair(JsonData(routesJson, networkJson), null))
            } catch (e: Exception) {
                jsonDataLoadError.compareAndSet(nullPair, Pair(null, e.toString()))
            }
        }.start()
    }

    private fun registerCommand(command: LiteralArgumentBuilder<FabricClientCommandSource?>, redirects: List<String> = listOf()) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource?>, _/*registryAccess*/: CommandRegistryAccess? ->
            val command = dispatcher.register(command)
            for (redirect in redirects) {
                dispatcher.register(
                    // This only works for commands with arguments but currently you always call with arguments
                    ClientCommandManager.literal(redirect)
                        .redirect(command)
                )
            }
        })
    }

    override fun onInitializeClient() {
        registerCommand(ClientCommandManager.literal("nguhroutes")
            .then(ClientCommandManager.literal("status")
                .executes { context ->
                    val container = FabricLoader.getInstance().getModContainer("nguhroutes").orElse(null)
                    val version = container?.metadata?.version?.friendlyString ?: "(unknown version)"
                    context.source.sendFeedback(Text.literal("Nguhroutes v$version"))
                    val jsonDataLoadError = this@NguhroutesClient.jsonDataLoadError.get()
                    val jsonData = jsonDataLoadError.first
                    val loadError = jsonDataLoadError.second
                    if (jsonData!= null) {
                        context.source.sendFeedback(Text.literal("JSON data has been loaded"))
                    } else if (loadError != null) {
                        context.source.sendFeedback(Text.literal("An error occurred when loading JSON data"))
                    } else {
                        context.source.sendFeedback(Text.literal("JSON data is still loading"))
                    }
                    if (loadError != null) {
                        context.source.sendFeedback(Text.literal("Error:"))
                        context.source.sendError(Text.literal(loadError))
                    }
                    1
                })
            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                    .executes { context ->
                        val dest = StringArgumentType.getString(context, "dest").uppercase()
                        setRoute(context, dest)
                        1
                    }))
            .then(ClientCommandManager.literal("restart")
                .executes { context ->
                    val currRoutePair = currRoutePair.get()
                    if (currRoutePair == null) {
                        context.source.sendError(Text.literal("No active route"))
                    } else {
                        setRoute(context, currRoutePair.first.stops.last().code)
                    }
                    1
                })
            .then(ClientCommandManager.literal("stop")
                .executes { context ->
                    currRoutePair.set(null)
                    sendRouteMessage("Cleared route")
                    1
                })
            .then(ClientCommandManager.literal("reload")
                .then(ClientCommandManager.argument("noNether", BoolArgumentType.bool())
                    .executes { context ->
                        val noNether = BoolArgumentType.getBool(context, "noNether")
                        loadJson(noNether)
                        1
                    }))
            .then(ClientCommandManager.literal("route")
                .executes { context ->
                    val currRoutePair = currRoutePair.get()
                    if (currRoutePair == null) {
                        context.source.sendError(Text.literal("No active route"))
                    } else {
                        for (i in currRoutePair.first.stops.indices) {
                            val stop = currRoutePair.first.stops[i];
                            val text = (if (i == currRoutePair.second) "> " else "") + "${stop.code} (${stop.lineName})";
                            context.source.sendFeedback(Text.literal(text))
                        }
                    }
                    1
                }),
            listOf("nr"))
        registerCommand(ClientCommandManager.literal("nrs")
            .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                .executes { context ->
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    setRoute(context, dest)
                    1
                }))
        ClientTickEvents.END_WORLD_TICK.register { clientWorld -> tick(clientWorld) }
    }

    private fun setRoute(context: CommandContext<FabricClientCommandSource>, dest: String) {
        val jsonData = jsonDataLoadError.get().first
        if (jsonData == null) {
            context.source.sendError(Text.literal("Data has not loaded yet."))
            return
        }
        Thread {
            val playerPos = context.source?.player?.pos
            if (playerPos == null) {
                context.source.sendError(Text.literal("Could not get player position."))
                return@Thread
            }

            // Find which route is fastest
            var fastestRoute: PreCalcRoute? = null
            var fastestRouteStart: String? = null
            var fastestRouteTime: Double = Double.POSITIVE_INFINITY
            var stationHasBeenSeen = false
            for (route in jsonData.routes.routes) {
                if (route.value.conns.isEmpty())
                    continue
                val routeCodes = route.key.split('`')
                if (routeCodes.getOrNull(1) == dest) {
                    stationHasBeenSeen = true
                    if (context.source!!.player.clientWorld.registryKey.value == Identifier.of(getDim(routeCodes[0]))) {
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
            }

            if (!stationHasBeenSeen) {
                context.source.sendError(Text.literal("Could not find station \"${dest}\"."))
                return@Thread
            }

            // Check if just sprinting there is faster
            val directTime = sprintTime(playerPos, jsonData.network.findAverageStationCoords(dest))
            if (directTime < fastestRouteTime) {
                fastestRoute = PreCalcRoute(0.0, listOf())
                fastestRouteStart = dest
                fastestRouteTime = directTime
            }

            if (fastestRoute == null) {
                context.source.sendError(Text.literal("Could not find route."))
                return@Thread
            }

            val routeObj = Route(fastestRouteStart!!, fastestRoute.conns, jsonData.network)
            currRoutePair.set(Pair(routeObj, 0))

            sendNextStopMessage(routeObj.stops[0])
        }.start()
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
        sendRouteMessage("Next: ${stop.code} (${stop.lineName})")
    }

    private fun sendRouteMessage(msg: String) {
        val player = MinecraftClient.getInstance().player ?: return
        val text = Text.of(msg)
        player.sendMessage(text, true)
        player.sendMessage(text, false)
    }
}
