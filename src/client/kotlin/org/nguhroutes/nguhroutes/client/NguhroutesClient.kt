package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.util.Clipboard
import net.minecraft.client.util.InputUtil
import net.minecraft.client.world.ClientWorld
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.get


class NguhroutesClient : ClientModInitializer, HudElement {
    // TODO: nicer way of doing this?
    val nrDataLoadError: AtomicReference<Pair<NRData?, String?>> = AtomicReference(Pair(null, null))
    val currRoutePair: AtomicReference<Pair<Route, Int>?> = AtomicReference(null)
    var tracker: Tracker? = null
    var waypointsEnabled = true

    init {
        loadJson(false)
    }

    private fun downloadJson(url: String): JsonElement {
        val url = URI(url).toURL()
        val data = url.openStream().readAllBytes().decodeToString()
        return Json.parseToJsonElement(data)
    }

    fun loadJson(noNether: Boolean, feedback: ClientPlayerEntity? = null) {
        val nullPair = Pair(null, null)
        nrDataLoadError.set(nullPair)
        currRoutePair.set(null)
        Thread {
            try {
                val networkJson = downloadJson("https://mc.nguh.org/wiki/Data:NguhRoutes/network.json?action=raw")
                val network = Network(networkJson.jsonObject)
                val preCalcRoutes = PreCalcRoutes(network, noNether)
                nrDataLoadError.compareAndSet(nullPair, Pair(NRData(network, preCalcRoutes), null))
                feedback?.sendMessage(Text.of("Finished loading NguhRoutes data!"), false)
            } catch (e: Exception) {
                nrDataLoadError.compareAndSet(nullPair, Pair(null, e.toString()))
                if (feedback != null)
                    sendError(Text.of("Error when loading NguhRoutes data: ${e.toString()}"))
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
        // Commands
        registerCommand(ClientCommandManager.literal("nguhroutes")
            .then(ClientCommandManager.literal("status")
                .executes { context ->
                    val container = FabricLoader.getInstance().getModContainer("nguhroutes").orElse(null)
                    val version = container?.metadata?.version?.friendlyString ?: "(unknown version)"
                    context.source.sendFeedback(Text.literal("NguhRoutes v$version"))
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
                    sendRouteMessage(Text.of("Cleared route"))
                    1
                })
            .then(ClientCommandManager.literal("reload")
                .executes { context ->
                    loadJson(false, context.source.player)
                    1
                }
                .then(ClientCommandManager.literal("nonether")
                    .executes { context ->
                        loadJson(true)
                        1
                    }))
            .then(ClientCommandManager.literal("route")
                .executes { context ->
                    val currRoutePair = currRoutePair.get()
                    if (currRoutePair == null) {
                        context.source.sendError(Text.literal("No active route"))
                    } else {
                        val nrData = getNRData(context) ?: return@executes 1
                        context.source.sendFeedback(Text.literal("Active route:"))
                        for (i in currRoutePair.first.stops.indices) {
                            val stop = currRoutePair.first.stops[i];
                            val name = nrData.network.stationNames[stop.code]?.getOrNull(0) ?: stop.code
                            var text = Text.literal(if (i == currRoutePair.second) "> " else "")
                                .append("$name (${stop.code}, ")
                            val square = getLineColourSquare(stop.lineCode, nrData)
                            if (square != null) {
                                text = text.append(square)
                            }
                            text = text.append("${stop.lineName})")
                            context.source.sendFeedback(text)
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
                    val jsonData = getNRData(context) ?: return@executes 1
                    val stations = mutableSetOf<String>()
                    for (line in jsonData.network.lines) {
                        for (stop in line.value.stops) {
                            if (stop != null) {
                                stations.add(stop.code)
                            }
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
                        val jsonData = getNRData(context) ?: return@executes 1
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
                    }))
            .then(ClientCommandManager.literal("measure")
                .then(ClientCommandManager.literal("start")
                    .executes { context ->
                        startMeasuring(context.source.player)
                        1
                    })
                .then(ClientCommandManager.literal("stop")
                    .executes { context ->
                        stopMeasuring(context.source.player, context)
                        1
                    })
                .then(ClientCommandManager.literal("copy")
                    .executes { context ->
                        copyMeasuring(false, context.source.player, context)
                        1
                    }
                    .then(ClientCommandManager.literal("both")
                        .executes { context ->
                            copyMeasuring(true, context.source.player, context)
                            1
                        }))
                .then(ClientCommandManager.literal("coords")
                    .executes { context ->
                        copyBlockCoords(context.source.player.blockPos)
                        context.source.sendFeedback(Text.of("Copied current coordinates to clipboard"))
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

        // Keybinds
        val bindingStartMeasuring: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.start_measuring",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        val bindingStopMeasuring: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.stop_measuring",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        val bindingCopyMeasuring: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.copy_measuring",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        val bindingCopyBothMeasuring: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.copy_both_measuring",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        val bindingCopyCoords: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.copy_coords",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        val bindingToggleWaypoints: KeyBinding = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.nguhroutes.toggle_waypoints",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.category.nguhroutes"
            )
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (bindingStartMeasuring.wasPressed()) {
                val player = client.player
                if (player != null) {
                    startMeasuring(player)
                }
            }
            while (bindingStopMeasuring.wasPressed()) {
                val player = client.player
                if (player != null) {
                    stopMeasuring(player, null)
                }
            }
            while (bindingCopyMeasuring.wasPressed()) {
                val player = client.player
                if (player != null) {
                    copyMeasuring(false, player, null)
                }
            }
            while (bindingCopyBothMeasuring.wasPressed()) {
                val player = client.player
                if (player != null) {
                    copyMeasuring(true, player, null)
                }
            }
            while (bindingCopyCoords.wasPressed()) {
                val player = client.player
                if (player != null) {
                    copyBlockCoords(player.blockPos)
                    player.sendMessage(Text.of("Copied current coordinates to clipboard"), false)
                }
            }
            while (bindingToggleWaypoints.wasPressed()) {
                waypointsEnabled = !waypointsEnabled
            }
        }

        HudElementRegistry.addFirst(Identifier.of("nguhroutes", "bottom"), this)
    }

    override fun render(context: DrawContext, tickCounter: RenderTickCounter) {
        if (!waypointsEnabled) return
        val nrData = getNRData(null) ?: return
        val currRoutePair = currRoutePair.get() ?: return
        val currRoute = currRoutePair.first
        val currStop = currRoutePair.second
        if (currStop >= currRoute.stops.size) return
        val colour = nrData.network.lines[currRoute.stops[currStop].lineCode]?.colour ?: Colour(0xFFFFFFFF)
        val clientWorld = MinecraftClient.getInstance().player?.clientWorld ?: return
        val fromCoordsDim = currRoute.stops[currStop].fromCoordsDim
        if (fromCoordsDim == null) {
            if (checkPlayerDim(currRoute.stops[currStop].dimension, clientWorld))
                renderWaypoint(context, currRoute.stops[currStop].coords.toCenterPos(), "Next", "(Approx.)", true, colour, 0xFFu)
        } else {
            val fromCoords = fromCoordsDim.first
            val fromDim = fromCoordsDim.second
            val text2 = if (currRoute.stops[currStop].reverseDirection) {
                "(Probably wrong side of the platform)"
            } else {
                null
            }
            if (checkPlayerDim(fromDim, clientWorld)) {
                val text = if (currRoute.stops[currStop].lineName == "Interdimensional transfer") "Next portal" else "Next platform"
                renderWaypoint(context, fromCoords.toCenterPos(), text, text2, true, colour, 0xFFu)
            }
            if (checkPlayerDim(currRoute.stops[currStop].dimension, clientWorld))
                renderWaypoint(context, currRoute.stops[currStop].coords.toCenterPos(), "Next stop", text2, false, colour, 0x7Fu)
        }
    }

    private fun renderWaypoint(context: DrawContext, pos: Vec3d, text: String, text2: String?, angled: Boolean, colour: Colour, opacity: UByte) {
        val player = MinecraftClient.getInstance().player ?: return
        val camera = MinecraftClient.getInstance().cameraEntity ?: return
        val matrices = context.matrices

        val colour = colour.withOpacity(opacity)

        val eye = camera.eyePos
        val x = pos.x.toFloat() - eye.x.toFloat()
        val y = pos.y.toFloat() - eye.y.toFloat()
        val z = -(pos.z.toFloat() - eye.z.toFloat())

        val tx = -camera.pitch * MathHelper.RADIANS_PER_DEGREE
        val ty = camera.yaw * MathHelper.RADIANS_PER_DEGREE
//        val tz = 0.0f

        val dz = MathHelper.cos(tx) * (MathHelper.cos(ty) * z + MathHelper.sin(ty) * (/* MathHelper.sin(tz) * y + */ /* MathHelper.cos(tz) * */ x)) - MathHelper.sin(tx) * (/* MathHelper.cos(tz) * */ y /* + MathHelper.sin(tz) * x */)
        if (dz < 0.0f) {
            val dx = MathHelper.cos(ty) * (/* MathHelper.sin(tz) * y + */ /* MathHelper.cos(tz) * */ x) - MathHelper.sin(ty) * z
            val dy = MathHelper.sin(tx) * (MathHelper.cos(ty) * z + MathHelper.sin(ty) * (/* MathHelper.sin(tz) * y + */ /* MathHelper.cos(tz) * */ x)) + MathHelper.cos(tx) * (/* MathHelper.cos(tz) * */ y /* + MathHelper.sin(tz) * x */)

            // Not quite sure what's going on with the fov but this makes it look correct enough
            val fov1 = MinecraftClient.getInstance().options.fov.value * 0.01f * player.getFovMultiplier(true, 1.0f)
            val fov2 = fov1 * (1.25f + MathHelper.square(fov1 - 0.3f))
            val scale = context.scaledWindowHeight * 0.5f / 0.7f / fov2

            val bx = 1.0f / dz * dx * scale + context.scaledWindowWidth * 0.5f
            val by = 1.0f / dz * dy * scale + context.scaledWindowHeight * 0.5f

            val colourARGB = colour.argb()

            matrices.pushMatrix()
            matrices.translate(bx, by)
            if (angled)
                matrices.rotate(MathHelper.HALF_PI * 0.5f)
            context.fill(-5, -5, 5, 5, colourARGB)
            matrices.popMatrix()

            val tr = MinecraftClient.getInstance().textRenderer

            var yBelow = by.toInt() + 10
            fun drawTextBelow(text: String) {
                context.drawCenteredTextWithShadow(tr, text, bx.toInt(), yBelow, (opacity.toInt() shl 24) or 0x00FFFFFF)
                yBelow += tr.fontHeight
            }

            drawTextBelow(text)
            val dist = pos.distanceTo(player.pos)
            if (text2 != null && dist < 20.0) {
                drawTextBelow(text2)
            }
            drawTextBelow(prettyDist(dist))
        }
    }

    private fun setRouteWithStart(context: CommandContext<FabricClientCommandSource>, start: String, dest: String) {
        val jsonData = getNRData(context) ?: return

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
        val jsonData = getNRData(context) ?: return
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
                    if (checkPlayerDim(getDim(route.key.first), context.source.player.clientWorld)) {
                        val firstStopCoords = route.value.conns.getOrNull(0)?.fromCoords

                        // If we can't determine the coords for the initial stop we can't use that route
                        if (firstStopCoords == null) {
                            continue
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

            if (checkPlayerDim(getDim(dest), context.source.player.clientWorld)) {
                // Check if just sprinting there is faster
                val coords = jsonData.network.findAverageStationCoords(dest)
                if (coords != null) {
                    val directTime = sprintTime(playerPos, coords)
                    if (directTime < fastestRouteTime) {
                        fastestRoute = PreCalcRoute(0.0, listOf())
                        fastestRouteStart = dest
                        fastestRouteTime = directTime
                    }
                }
            }

            var warpStart = false
            fun checkIfWarpIsFaster(code: String, warpCoords: BlockPos) {
                val route = jsonData.preCalcRoutes.routes[Pair(code, dest)]
                if (route != null) {
                    val firstStopCoords = route.conns.getOrNull(0)?.fromCoords ?: return
                    // Time is the time it takes for the route, plus the time it takes to walk to the actual station
                    // from the warp, plus 3 seconds as an estimate for typing in and performing the warp
                    val time = route.time + walkTime(warpCoords.toBottomCenterPos(), firstStopCoords) + 3.0
                    if (time < fastestRouteTime) {
                        fastestRoute = route
                        fastestRouteStart = code
                        fastestRouteTime = time
                        warpStart = true
                    }
                }
            }
            checkIfWarpIsFaster("MZS", BlockPos(0, 163, 0))
            checkIfWarpIsFaster("XG3", BlockPos(-7993, 63, -7994))

            if (fastestRoute == null) {
                context.source.sendError(Text.literal("Could not find route."))
                return@Thread
            }

            val routeObj = Route(fastestRouteStart!!, fastestRoute.conns, jsonData.network, warpStart)
            currRoutePair.set(Pair(routeObj, 0))

            sendNextStopMessage(routeObj.stops[0], context)
        }.start()
    }

    private fun tick(clientWorld: ClientWorld) {
        currRouteLogic(clientWorld)
        if (tracker != null && tracker!!.active) {
            trackerLogic()
        }
    }

    private fun currRouteLogic(clientWorld: ClientWorld) {
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
            checkPlayerDim(currRoute.stops[currStop].dimension, clientWorld) &&
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
                sendRouteMessage(Text.of("Route finished!"))
            }
        }
    }

    private fun trackerLogic() {
        val player = MinecraftClient.getInstance().player
        if (player != null) {
            tracker!!.updatePos(player.blockPos)
        }
    }

    private fun startMeasuring(player: ClientPlayerEntity) {
        tracker = Tracker(player.blockPos)
        player.sendMessage(Text.of("Measuring tracker started"), false)
    }

    private fun stopMeasuring(player: ClientPlayerEntity, context: CommandContext<FabricClientCommandSource>?) {
        val tracker = tracker
        if (tracker == null) {
            sendError(Text.of("Measuring tracker is not active"), context)
            return
        }
        tracker.stop(player.blockPos)
        player.sendMessage(Text.of("Measuring tracker stopped"), false)
        tracker.copyEndStop()
        player.sendMessage(Text.of("End stop JSON copied to clipboard"), false)
    }

    private fun copyMeasuring(both: Boolean, player: ClientPlayerEntity, context: CommandContext<FabricClientCommandSource>?) {
        val tracker = tracker
        if (tracker == null) {
            sendError(Text.of("Measuring tracker is not active"), context)
            return
        }
        if (both) {
            tracker.copyBothStops()
            player.sendMessage(Text.of("Both stops JSON copied to clipboard"), false)
        } else {
            tracker.copyEndStop()
            player.sendMessage(Text.of("End stop JSON copied to clipboard"), false)
        }
    }

    private fun copyBlockCoords(coords: BlockPos) {
        val clipboard = Clipboard()
        clipboard.setClipboard(0, "${coords.x}, ${coords.y}, ${coords.z}")
    }

    private fun stationList(context: CommandContext<FabricClientCommandSource>, ngationCode: String) {
        if (ngationCode.length != 2) {
            context.source.sendError(Text.literal("This command currently only works with 2-letter codes."))
            return
        }
        val jsonData = getNRData(context) ?: return

        context.source.sendFeedback(Text.literal("All stations in $ngationCode:"))
        val stations = mutableSetOf<String>()
        for (line in jsonData.network.lines) {
            for (stop in line.value.stops) {
                if (stop != null) {
                    val code = stop.code
                    if (code.regionMatches(getPrefix(code).length, ngationCode, 0, 2)) {
                        stations.add(code)
                    }
                }
            }
        }
        for (station in stations) {
            val name = jsonData.network.stationNames[station]?.getOrNull(0) ?: station
            context.source.sendFeedback(Text.literal("$name ($station)"))
        }
    }

    private fun sendNextStopMessage(stop: RouteStop, context: CommandContext<FabricClientCommandSource>? = null) {
        val nrData = getNRData(context) ?: return
        val name = nrData.network.stationNames[stop.code]?.getOrNull(0) ?: stop.code
        var text = Text.literal("Next: $name (${stop.code}, ")
        val square = getLineColourSquare(stop.lineCode, nrData)
        if (square != null) {
            text = text.append(square)
        }
        text = text.append("${stop.lineName})")
        sendRouteMessage(text)
    }

    private fun sendRouteMessage(msg: Text) {
        val player = MinecraftClient.getInstance().player ?: return
        player.sendMessage(msg, true)
        player.sendMessage(msg, false)
    }

    /**
     * Returns a coloured text square with the appropriate line colour. Returns null if it is unable to determine
     * the colour. If lineCode is null it immediately returns null.
     */
    private fun getLineColourSquare(lineCode: String?, nrData: NRData): MutableText? {
        if (lineCode == null) return null
        val lineColour = nrData.network.lines[lineCode]?.colour
        return if (lineColour != null) {
            Text.literal("■").withColor(lineColour.argb())
        } else {
            null
        }
    }

    /**
     * Gets the NRData and prints a message if it does not exist.
     */
    private fun getNRData(context: CommandContext<FabricClientCommandSource>? = null): NRData? {
        val nrDataLoadError = nrDataLoadError.get()
        val nrData = nrDataLoadError.first
        if (nrData == null) {
            if (nrDataLoadError.second == null) {
                val text = Text.of("Data has not loaded yet.")
                sendError(text, context)
            } else {
                sendError(Text.of("Error when loading:"), context)
                sendError(Text.of(nrDataLoadError.second), context)
            }
            return null
        }
        return nrData
    }

    private fun sendError(text: Text, context: CommandContext<FabricClientCommandSource>? = null) {
        if (context != null) {
            context.source.sendError(text)
        } else {
            val player = MinecraftClient.getInstance().player ?: return
            player.sendMessage(text.getWithStyle(Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.RED)))[0], false)
        }
    }

    private fun checkPlayerDim(dim: String, clientWorld: ClientWorld): Boolean {
        return clientWorld.registryKey.value == Identifier.of(dim)
    }
}
