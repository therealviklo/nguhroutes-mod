package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.getOrNull


class NguhroutesClient : ClientModInitializer {
    // TODO: nicer way of doing this?
    val nrDataLoadError: AtomicReference<Pair<NRData?, String?>> = AtomicReference(Pair(null, null))
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
        nrDataLoadError.set(nullPair)
        currRoutePair.set(null)
        Thread {
            try {
                val networkJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/network.json")
                val network = Network(networkJson.jsonObject)
                val preCalcRoutes = PreCalcRoutes(network)
                nrDataLoadError.compareAndSet(nullPair, Pair(NRData(network, preCalcRoutes), null))
            } catch (e: Exception) {
                nrDataLoadError.compareAndSet(nullPair, Pair(null, e.toString()))
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
                    val jsonDataLoadError = this@NguhroutesClient.nrDataLoadError.get()
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
                    })
                .then(ClientCommandManager.argument("start", StringArgumentType.string())
                    .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                        .executes { context ->
                            val start = StringArgumentType.getString(context, "start").uppercase()
                            val dest = StringArgumentType.getString(context, "dest").uppercase()
                            setRouteWithStart(context, start, dest)
                            1
                        })))
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
                        val jsonData = getJsonData(context) ?: return@executes 1
                        context.source.sendFeedback(Text.literal("Active route:"))
                        for (i in currRoutePair.first.stops.indices) {
                            val stop = currRoutePair.first.stops[i];
                            val name = jsonData.network.stationNames[stop.code]?.getOrNull(0) ?: stop.code
                            val text = (if (i == currRoutePair.second) "> " else "") + "$name (${stop.code}, ${stop.lineName})";
                            context.source.sendFeedback(Text.literal(text))
                        }
                    }
                    1
                })
            .then(ClientCommandManager.literal("stationlist")
                .then(ClientCommandManager.argument("ngationcode", StringArgumentType.string())
                    .executes { context ->
                        val ngationcode = StringArgumentType.getString(context, "ngationcode").uppercase()
                        stationList(context, ngationcode)
                        1
                    }))
            .then(ClientCommandManager.literal("random")
                .executes { context ->
                    val jsonData = getJsonData(context) ?: return@executes 1
                    val stations = mutableSetOf<String>()
                    for (line in jsonData.network.lines) {
                        for (stop in line.value.stops) {
                            stations.add(stop.code)
                        }
                    }
                    val selectedStation = stations.random()
                    setRoute(context, selectedStation)
                    1
                })
            .then(ClientCommandManager.literal("search")
                .then(ClientCommandManager.argument("regex", StringArgumentType.greedyString())
                    .executes { context ->
                        val query = StringArgumentType.getString(context, "regex")
                        val jsonData = getJsonData(context) ?: return@executes 1
                        Thread {
                            val finds = mutableListOf<Text>()
                            for (station in jsonData.network.stationNames) {
                                val re = Regex(query, RegexOption.IGNORE_CASE)
                                var longestNameFind: Text? = null
                                var longestNameLength = 0
                                for (name in station.value) {
                                    if (longestNameFind != null && longestNameLength >= name.length)
                                        continue
                                    val match = re.find(name)
                                    if (match != null) {
                                        val text = Text.literal(name.take(match.range.first))
                                        Text.of(name.substring(match.range))
                                            .getWithStyle(Style.EMPTY.withUnderline(true)).map { i -> text.append(i) }
                                        text.append(Text.of(name.substring((match.range.last + 1)) + " (${station.key})"))
                                        longestNameFind = text
                                        longestNameLength = name.length
                                    }
                                }
                                if (longestNameFind != null)
                                    finds.add(longestNameFind)
                            }
                            if (finds.isEmpty()) {
                                context.source.sendFeedback(Text.of("No search results."))
                            } else {
                                context.source.sendFeedback(Text.of("Search Results:"))
                                for (result in finds) {
                                    context.source.sendFeedback(result)
                                }
                            }
                        }.start()
                        1
                    })),
            listOf("nr"))
        registerCommand(ClientCommandManager.literal("nrs")
            .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                .executes { context ->
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    setRoute(context, dest)
                    1
                })
            .then(ClientCommandManager.argument("start", StringArgumentType.string())
                .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                    .executes { context ->
                        val start = StringArgumentType.getString(context, "start").uppercase()
                        val dest = StringArgumentType.getString(context, "dest").uppercase()
                        setRouteWithStart(context, start, dest)
                        1
                    })))
        ClientTickEvents.END_WORLD_TICK.register { clientWorld -> tick(clientWorld) }
    }

    private fun setRouteWithStart(context: CommandContext<FabricClientCommandSource>, start: String, dest: String) {
        val jsonData = getJsonData(context) ?: return

        val route = jsonData.preCalcRoutes.routes[Pair(start, dest)]
        if (route == null) {
            context.source.sendError(Text.literal("Could not find route."))
            return
        }
        val routeObj = Route(start, route.conns, jsonData.network)
        currRoutePair.set(Pair(routeObj, 0))

        sendNextStopMessage(routeObj.stops[0], context)
    }

    private fun setRoute(context: CommandContext<FabricClientCommandSource>, dest: String) {
        val jsonData = getJsonData(context) ?: return
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
            for (route in jsonData.preCalcRoutes.routes) {
                if (route.value.conns.isEmpty())
                    continue
                if (route.key.second == dest) {
                    stationHasBeenSeen = true
                    if (context.source!!.player.clientWorld.registryKey.value == Identifier.of(getDim(route.key.first))) {
                        val firstStopCoords = if (route.value.conns[0].line == "Interdimensional transfer") {
                            jsonData.network.findAverageStationCoords(route.key.first)
                        } else {
                            jsonData.network.getStop(route.key.first, route.value.conns[0].line).coords
                        }
                        // Add the time it takes to sprint to the stop
                        val time = route.value.time + sprintTime(playerPos, firstStopCoords)
                        if (time < fastestRouteTime) {
                            fastestRoute = route.value
                            fastestRouteStart = route.key.first
                            fastestRouteTime = time
                        }
                    }
                }
            }

            if (!stationHasBeenSeen) {
                if (jsonData.network.stationNames.containsKey(dest)) {
                    // This is the case for where a station is in the "stations" property in network.jsonc,
                    // but is not actually anywhere in the network.
                    context.source.sendError(Text.literal("Could not find route to station \"${dest}\"."))
                } else {
                    context.source.sendError(Text.literal("Could not find station \"${dest}\"."))
                }
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

            sendNextStopMessage(routeObj.stops[0], context)
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

    private fun stationList(context: CommandContext<FabricClientCommandSource>, ngationCode: String) {
        if (ngationCode.length != 2) {
            context.source.sendError(Text.literal("This command currently only works with 2-letter codes."))
            return
        }
        val jsonData = getJsonData(context) ?: return

        context.source.sendFeedback(Text.literal("All stations in $ngationCode:"))
        val stations = mutableSetOf<String>()
        for (line in jsonData.network.lines) {
            for (stop in line.value.stops) {
                val code = stop.code
                if (code.regionMatches(getPrefix(code).length, ngationCode, 0, 2)) {
                    stations.add(code)
                }
            }
        }
        for (station in stations) {
            val name = jsonData.network.stationNames[station]?.getOrNull(0) ?: station
            context.source.sendFeedback(Text.literal("$name ($station)"))
        }
    }

    private fun sendNextStopMessage(stop: RouteStop, context: CommandContext<FabricClientCommandSource>? = null) {
        val jsonData = getJsonData(context) ?: return
        val name = jsonData.network.stationNames[stop.code]?.getOrNull(0) ?: stop.code
        sendRouteMessage("Next: ${name} (${stop.code}, ${stop.lineName})")
    }

    private fun sendRouteMessage(msg: String) {
        val player = MinecraftClient.getInstance().player ?: return
        val text = Text.of(msg)
        player.sendMessage(text, true)
        player.sendMessage(text, false)
    }

    /**
     * Gets the JsonData and prints a message if it does not exist.
     */
    private fun getJsonData(context: CommandContext<FabricClientCommandSource>? = null): NRData? {
        val jsonData = nrDataLoadError.get().first
        if (jsonData == null) {
            val text = Text.of("Data has not loaded yet.")
            if (context != null) {
                context.source.sendError(text)
            } else {
                val player = MinecraftClient.getInstance().player ?: return null
                player.sendMessage(text, false)
            }
            return null
        }
        return jsonData
    }
}
