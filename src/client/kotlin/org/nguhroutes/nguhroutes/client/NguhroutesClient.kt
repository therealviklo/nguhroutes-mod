package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.json.jsonObject
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.KeyMapping
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.components.toasts.SystemToast
import com.mojang.blaze3d.platform.ClipboardManager
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.CommandBuildContext
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.lwjgl.sdl.SDLScancode
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val warpTypingCost = 3.0

class NguhroutesClient : ClientModInitializer, HudElement {
    // TODO: nicer way of doing this?
    val nrDataLoadError: AtomicReference<Pair<NRData?, String?>> = AtomicReference(Pair(null, null))
    val currRoutePair: AtomicReference<Pair<Route, Int>?> = AtomicReference(null)
    var tracker: Tracker? = null
    var waypointsEnabled = true

    private val loadGeneration = AtomicLong(0)
    private val loadLock = Any()

    init {
        // config is not available here so this is run later a second time if nonether_by_default is enabled.
        loadJson(false)
    }

    fun loadJson(noNether: Boolean, feedback: LocalPlayer? = null) {
        currRoutePair.set(null)

        val myGen = synchronized(loadLock) {
            nrDataLoadError.set(Pair(null, null))
            loadGeneration.incrementAndGet()
        }

        Thread {
            try {
                val nrdpr = if (config.debug) {
                    NRDataPerformanceReport()
                } else {
                    null
                }

                nrdpr?.totalTime?.start()

                nrdpr?.downloadTime?.start()
                val networkJson = downloadJson("https://mc.nguh.org/wiki/Data:NguhRoutes/network.json?action=raw")
                nrdpr?.downloadTime?.stop()

                nrdpr?.networkTime?.start()
                val network = Network(networkJson.jsonObject)
                nrdpr?.networkTime?.stop()

                nrdpr?.preCalcRoutesTime?.start()
                val preCalcRoutes = PreCalcRoutes(network, noNether, nrdpr)
                nrdpr?.preCalcRoutesTime?.stop()

                nrdpr?.totalTime?.stop()

                val won = synchronized(loadLock) {
                    if (loadGeneration.get() != myGen) false
                    else {
                        nrDataLoadError.set(Pair(NRData(network, preCalcRoutes), null))
                        true
                    }
                }

                if (won && feedback != null) {
                    Minecraft.getInstance().execute {
                        feedback.sendSystemMessage(Component.nullToEmpty("Finished loading NguhRoutes data!"))
                    }
                    nrdpr?.sendReportMessage(feedback)
                }
            } catch (e: Exception) {
                val won = synchronized(loadLock) {
                    if (loadGeneration.get() != myGen) false
                    else {
                        nrDataLoadError.set(Pair(null, e.toString()))
                        true
                    }
                }

                if (won && feedback != null)
                    sendError(Component.nullToEmpty("Error when loading NguhRoutes data: ${e.toString()}"))
            }
        }.start()
    }

    private fun registerCommand(command: LiteralArgumentBuilder<FabricClientCommandSource?>, redirects: List<String> = listOf()) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource>, _/*registryAccess*/: CommandBuildContext ->
            val command = dispatcher.register(command)
            for (redirect in redirects) {
                dispatcher.register(
                    // This only works for commands with arguments but currently you always call with arguments
                    ClientCommands.literal(redirect)
                        .redirect(command)
                )
            }
        })
    }

    override fun onInitializeClient() {
        // Load config from file
        config = loadConfig()
        // config is not available when loadJson is first run so this needs to be run a second time here
        if (config.nonether_by_default) {
            loadJson(true)
        }

        // Commands
        registerCommand(ClientCommands.literal("nguhroutes")
            .then(ClientCommands.literal("status")
                .executes { context ->
                    val container = FabricLoader.getInstance().getModContainer("nguhroutes").orElse(null)
                    val version = container?.metadata?.version?.friendlyString ?: "(unknown version)"
                    sendFeedback(context, 
                        Component.literal("NguhRoutes")
                        .setStyle(Style.EMPTY
                            .withItalic(true)
                            .withBold(true)
                            .withUnderlined(true))
                        .append(Component.literal(" v$version")
                            .setStyle(Style.EMPTY
                                .withItalic(false)
                                .withBold(true)
                                .withUnderlined(true))))
                    sendFeedback(context, Component.nullToEmpty("Supported format version: $supportedNetworkFormatVersion"))
                    val nrDataLoadError = this@NguhroutesClient.nrDataLoadError.get()
                    val nrData = nrDataLoadError.first
                    val loadError = nrDataLoadError.second
                    if (nrData!= null) {
                        sendFeedback(context, Component.literal("JSON data has been loaded"))
                    } else if (loadError != null) {
                        sendFeedback(context, Component.literal("An error occurred when loading JSON data"))
                    } else {
                        sendFeedback(context, Component.literal("JSON data is still loading"))
                    }
                    if (loadError != null) {
                        sendFeedback(context, Component.literal("Error:"))
                        sendError(context, Component.literal(loadError))
                    }
                    1
                })
            .then(ClientCommands.literal("start")
                .then(ClientCommands.argument("dest", StringArgumentType.string())
                    .executes { context ->
                        val dest = StringArgumentType.getString(context, "dest").uppercase()
                        setRouteFromCurrentPos(context, dest)
                        1
                    })
                .then(ClientCommands.argument("start", StringArgumentType.string())
                    .then(ClientCommands.argument("dest", StringArgumentType.string())
                        .executes { context ->
                            val start = StringArgumentType.getString(context, "start").uppercase()
                            val dest = StringArgumentType.getString(context, "dest").uppercase()
                            setRouteWithStart(context, start, dest)
                            1
                        }))
                .then(ClientCommands.argument("x", CoordinateArgumentType.coordinate())
                    .then(ClientCommands.argument("y", CoordinateArgumentType.coordinate())
                        .then(ClientCommands.argument("z", CoordinateArgumentType.coordinate())
                            .executes { context ->
                                val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x)
                                val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y)
                                val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z)
                                setRouteToCoord(context, Vec3(x, y, z), "overworld")
                                1
                            }
                            .then(ClientCommands.literal("nether")
                                .executes { context ->
                                    val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x)
                                    val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y)
                                    val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z)
                                    setRouteToCoord(context, Vec3(x, y, z), "the_nether")
                                    1
                                })))))
            .then(ClientCommands.literal("restart")
                .executes { context ->
                    val currRoutePair = currRoutePair.get()
                    if (currRoutePair == null) {
                        sendError(context, Component.literal("No active route"))
                    } else {
                        val last = currRoutePair.first.stops.last()
                        if (last.code == null) {
                            setRouteToCoord(context, Vec3.atBottomCenterOf(last.coords), last.dimension)
                        } else {
                            setRouteFromCurrentPos(context, last.code)
                        }
                    }
                    1
                })
            .then(ClientCommands.literal("stop")
                .executes {
                    currRoutePair.set(null)
                    sendRouteMessage(Component.nullToEmpty("Cleared route"))
                    1
                })
            .then(ClientCommands.literal("reload")
                .executes { context ->
                    sendFeedback(context, Component.nullToEmpty("Reloading NguhRoutes data..."))
                    loadJson(config.nonether_by_default, context.source.player)
                    1
                }
                .then(ClientCommands.literal("nonether")
                    .executes { context ->
                        sendFeedback(context, Component.nullToEmpty("Reloading NguhRoutes data..."))
                        loadJson(true, context.source.player)
                        1
                    })
                .then(ClientCommands.literal("nether")
                    .executes { context ->
                        sendFeedback(context, Component.nullToEmpty("Reloading NguhRoutes data..."))
                        loadJson(false, context.source.player)
                        1
                    }))
            .then(ClientCommands.literal("route")
                .executes { context ->
                    printRoute(context)
                    1
                })
            .then(ClientCommands.literal("stationlist")
                .then(ClientCommands.argument("ngationcode", StringArgumentType.string())
                    .executes { context ->
                        val ngationcode = StringArgumentType.getString(context, "ngationcode").uppercase()
                        stationList(context, ngationcode)
                        1
                    }))
            .then(ClientCommands.literal("random")
                .executes { context ->
                    val nrData = getNRData(context) ?: return@executes 1
                    val stations = mutableSetOf<String>()
                    for (line in nrData.network.lines) {
                        for (stop in line.value.stops) {
                            if (stop != null) {
                                stations.add(stop.code)
                            }
                        }
                    }
                    val selectedStation = stations.random()
                    setRouteFromCurrentPos(context, selectedStation)
                    1
                })
            .then(ClientCommands.literal("search")
                .then(ClientCommands.argument("regex", StringArgumentType.greedyString())
                    .executes { context ->
                        val query = StringArgumentType.getString(context, "regex")
                        val nrData = getNRData(context) ?: return@executes 1
                        Thread {
                            val finds = mutableListOf<Component>()
                            for (station in nrData.network.stationNames) {
                                val re = Regex(query, RegexOption.IGNORE_CASE)
                                var longestNameFind: Component? = null
                                var longestNameLength = 0
                                for (name in station.value) {
                                    if (longestNameFind != null && longestNameLength >= name.length)
                                        continue
                                    val match = re.find(name)
                                    if (match != null) {
                                        val text = Component.literal(name.take(match.range.first))
                                        Component.nullToEmpty(name.substring(match.range))
                                            .toFlatList(Style.EMPTY.withUnderlined(true)).map { i -> text.append(i) }
                                        text.append(Component.nullToEmpty(name.substring((match.range.last + 1)) + " (${station.key})"))
                                        longestNameFind = text
                                        longestNameLength = name.length
                                    }
                                }
                                if (longestNameFind != null)
                                    finds.add(longestNameFind)
                            }
                            if (finds.isEmpty()) {
                                sendFeedback(context, Component.nullToEmpty("No search results."))
                            } else {
                                sendFeedback(context, Component.literal("Search Results:")
                                    .setStyle(Style.EMPTY
                                        .withBold(true)
                                        .withUnderlined(true)))
                                for (result in finds) {
                                    sendFeedback(context, result)
                                }
                            }
                        }.start()
                        1
                    }))
            .then(ClientCommands.literal("measure")
                .then(ClientCommands.literal("start")
                    .executes { context ->
                        startMeasuring(context.source.player)
                        1
                    })
                .then(ClientCommands.literal("stop")
                    .executes { context ->
                        stopMeasuring(context.source.player, context)
                        1
                    })
                .then(ClientCommands.literal("copy")
                    .executes { context ->
                        copyMeasuring(false, context.source.player, context)
                        1
                    }
                    .then(ClientCommands.literal("both")
                        .executes { context ->
                            copyMeasuring(true, context.source.player, context)
                            1
                        }))
                .then(ClientCommands.literal("coords")
                    .executes { context ->
                        copyBlockCoords(context.source.player.blockPosition())
                        sendFeedback(context, Component.nullToEmpty("Copied current coordinates to clipboard"))
                        1
                    }))
            .then(config.configCommand()),
            listOf("nr"))
        registerCommand(ClientCommands.literal("nrs")
            .then(ClientCommands.argument("dest", StringArgumentType.string())
                .executes { context ->
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    setRouteFromCurrentPos(context, dest)
                    1
                })
            .then(ClientCommands.argument("start", StringArgumentType.string())
                .then(ClientCommands.argument("dest", StringArgumentType.string())
                    .executes { context ->
                        val start = StringArgumentType.getString(context, "start").uppercase()
                        val dest = StringArgumentType.getString(context, "dest").uppercase()
                        setRouteWithStart(context, start, dest)
                        1
                    }))
            .then(ClientCommands.argument("x", CoordinateArgumentType.coordinate())
                .then(ClientCommands.argument("y", CoordinateArgumentType.coordinate())
                    .then(ClientCommands.argument("z", CoordinateArgumentType.coordinate())
                        .executes { context ->
                            val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x)
                            val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y)
                            val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z)
                            setRouteToCoord(context, Vec3(x, y, z), "overworld")
                            1
                        }
                        .then(ClientCommands.literal("nether")
                            .executes { context ->
                                val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x)
                                val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y)
                                val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z)
                                setRouteToCoord(context, Vec3(x, y, z), "the_nether")
                                1
                            })))))
        ClientTickEvents.END_LEVEL_TICK.register { clientWorld -> tick(clientWorld) }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (config.update_notifications) {
                sendUpdateNotificationIfNeeded()
            }
        }

        // Keybinds
        val keyCategory = KeyMapping.Category(Identifier.fromNamespaceAndPath("nguhroutes", "keys"))
        val bindingStartMeasuring: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.start_measuring",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingStopMeasuring: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.stop_measuring",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingCopyMeasuring: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.copy_measuring",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingCopyBothMeasuring: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.copy_both_measuring",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingCopyCoords: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.copy_coords",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingToggleWaypoints: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.toggle_waypoints",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        val bindingConfigScreen: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.nguhroutes.open_config",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                keyCategory
            )
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (bindingStartMeasuring.consumeClick()) {
                val player = client.player
                if (player != null) {
                    startMeasuring(player)
                }
            }
            while (bindingStopMeasuring.consumeClick()) {
                val player = client.player
                if (player != null) {
                    stopMeasuring(player, null)
                }
            }
            while (bindingCopyMeasuring.consumeClick()) {
                val player = client.player
                if (player != null) {
                    copyMeasuring(false, player, null)
                }
            }
            while (bindingCopyBothMeasuring.consumeClick()) {
                val player = client.player
                if (player != null) {
                    copyMeasuring(true, player, null)
                }
            }
            while (bindingCopyCoords.consumeClick()) {
                val player = client.player
                if (player != null) {
                    copyBlockCoords(player.blockPosition())
                    sendPlayerSystemMessage(player, Component.nullToEmpty("Copied current coordinates to clipboard"))
                }
            }
            while (bindingToggleWaypoints.consumeClick()) {
                waypointsEnabled = !waypointsEnabled
            }
            while (bindingConfigScreen.consumeClick()) {
                val currentScreen = Minecraft.getInstance().gui.screen()
                if (currentScreen == null) {
                    println("NguhRoutes Warning: no current screen to return to")
                } else {
                    Minecraft.getInstance().gui.setScreen(ConfigScreen(config, currentScreen))
                }
            }
        }

        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("nguhroutes", "bottom"), this)
    }

    override fun extractRenderState(
        context: GuiGraphicsExtractor,
        deltaTracker: DeltaTracker
    ) {
        if (!waypointsEnabled) return
        val nrData = getNRData(null, false) ?: return
        val currRoutePair = currRoutePair.get() ?: return
        val currRoute = currRoutePair.first
        val currStop = currRoutePair.second
        if (currStop >= currRoute.stops.size) return
        val colour = nrData.network.lines[currRoute.stops[currStop].lineCode]?.colour ?: Colour(0xFFFFFFFF)
        val clientWorld = Minecraft.getInstance().player?.level() ?: return
        val fromCoordsDim = currRoute.stops[currStop].fromCoordsDim
        if (fromCoordsDim == null) {
            if (checkPlayerDim(currRoute.stops[currStop].dimension, clientWorld))
                renderWaypoint(context, Vec3.atCenterOf(currRoute.stops[currStop].coords), "Next", "(Approx.)", true, colour, 0xFFu)
        } else {
            val fromCoords = fromCoordsDim.first
            val fromDim = fromCoordsDim.second
            val text2 = if (currRoute.stops[currStop].reverseDirection) {
                "(Marker probably at wrong direction)"
            } else {
                null
            }
            if (checkPlayerDim(fromDim, clientWorld)) {
                val text = if (currRoute.stops[currStop].lineName == "Interdimensional transfer") "Next portal" else "Next platform"
                renderWaypoint(context, Vec3.atCenterOf(fromCoords), text, text2, true, colour, 0xFFu)
            }
            if (checkPlayerDim(currRoute.stops[currStop].dimension, clientWorld))
                renderWaypoint(context, Vec3.atCenterOf(currRoute.stops[currStop].coords), "Next stop", text2, false, colour, 0x7Fu)
        }
    }

    private fun renderWaypoint(context: GuiGraphicsExtractor, pos: Vec3, text: String, text2: String?, angled: Boolean, colour: Colour, opacity: UByte) {
        val player = Minecraft.getInstance().player ?: return
        val camera = Minecraft.getInstance().cameraEntity ?: return
        val matrices = context.pose()

        val colour = colour.withOpacity(opacity)

        val eye = camera.eyePosition
        val x = pos.x.toFloat() - eye.x.toFloat()
        val y = pos.y.toFloat() - eye.y.toFloat()
        val z = -(pos.z.toFloat() - eye.z.toFloat())

        val tx = -camera.xRot * Mth.DEG_TO_RAD
        val ty = camera.yRot * Mth.DEG_TO_RAD
//        val tz = 0.0f

        val dz = Mth.cos(tx.toDouble()) * (Mth.cos(ty.toDouble()) * z + Mth.sin(ty.toDouble()) * (/* Mth.sin(tz.toDouble()) * y + */ /* Mth.cos(tz.toDouble()) * */ x)) - Mth.sin(tx.toDouble()) * (/* Mth.cos(tz.toDouble()) * */ y /* + Mth.sin(tz.toDouble()) * x */)
        if (dz < 0.0f) {
            val dx = Mth.cos(ty.toDouble()) * (/* Mth.sin(tz.toDouble()) * y + */ /* Mth.cos(tz.toDouble()) * */ x) - Mth.sin(ty.toDouble()) * z
            val dy = Mth.sin(tx.toDouble()) * (Mth.cos(ty.toDouble()) * z + Mth.sin(ty.toDouble()) * (/* Mth.sin(tz.toDouble()) * y + */ /* Mth.cos(tz.toDouble()) * */ x)) + Mth.cos(tx.toDouble()) * (/* Mth.cos(tz.toDouble()) * */ y /* + Mth.sin(tz.toDouble()) * x */)

            // Not quite sure what's going on with the fov but this makes it look correct enough
            val fov1 = Minecraft.getInstance().options.fov().get() * 0.01f * player.getFieldOfViewModifier(true, 1.0f)
            val fov2 = fov1 * (1.25f + Mth.square(fov1 - 0.3f))
            val scale = context.guiHeight() * 0.5f / 0.7f / fov2

            val bx = 1.0f / dz * dx * scale + context.guiWidth() * 0.5f
            val by = 1.0f / dz * dy * scale + context.guiHeight() * 0.5f

            val colourARGB = colour.argb()

            matrices.pushMatrix()
            matrices.translate(bx, by)
            if (angled)
                matrices.rotate(Mth.HALF_PI * 0.5f)
            context.fill(-5, -5, 5, 5, colourARGB)
            matrices.popMatrix()

            val tr = Minecraft.getInstance().font

            var yBelow = by.toInt() + 10
//            @Suppress("AssignedValueIsNeverRead")
            fun drawTextBelow(text: String) {
                context.centeredText(tr, text, bx.toInt(), yBelow, (opacity.toInt() shl 24) or 0x00FFFFFF)
                yBelow += tr.lineHeight
            }

            drawTextBelow(text)
            val dist = pos.distanceTo(player.position())
            if (text2 != null && dist < 20.0) {
                drawTextBelow(text2)
            }
            drawTextBelow(prettyDist(dist))
        }
    }

    private fun setRouteWithStart(context: CommandContext<FabricClientCommandSource>, start: String, dest: String) {
        val nrData = getNRData(context) ?: return

        val start = nrData.network.getActualCode(start)
        val dest = nrData.network.getActualCode(dest)

        val route = nrData.preCalcRoutes.routes[Pair(start, dest)]
        if (route == null) {
            sendError(context, Component.literal("Could not find route."))
            return
        }
        sendFeedback(context, Component.nullToEmpty("Time: %.1f s".format(route.time)))
        val routeObj = Route(start, route.conns, nrData.network)

        setRoute(context, nrData, routeObj, dest)
    }

    private fun setRouteFromCurrentPos(context: CommandContext<FabricClientCommandSource>, dest: String) {
        val nrData = getNRData(context) ?: return
        val dest = nrData.network.getActualCode(dest)
        Thread {
            val playerPos = context.source?.player?.position()
            if (playerPos == null) {
                sendError(context, Component.literal("Could not get player position."))
                return@Thread
            }

            val fastestRouteWithCost = findRouteFromCoords(context, nrData, playerPos, dest)

            if (fastestRouteWithCost == null) {
                sendError(context, Component.literal("Could not find route."))
                return@Thread
            }

            setRoute(context, nrData, fastestRouteWithCost.route, dest)
        }.start()
    }

    private fun setRouteToCoord(context: CommandContext<FabricClientCommandSource>, destCoords: Vec3, dimension: String) {
        val nrData = getNRData(context) ?: return
        Thread {
            val playerPos = context.source?.player?.position()
            if (playerPos == null) {
                sendError(context, Component.literal("Could not get player position."))
                return@Thread
            }

            sendFeedback(context, Component.nullToEmpty("Finding route to coordinate..."))

            var fastestRouteWithCost = RouteWithCost(
                Route(BlockPos.containing(destCoords), dimension), sprintTime(playerPos, destCoords)
            )
            var fastestDestStation: String? = null
            for (destStation in nrData.preCalcRoutes.stations.keys) {
                if (getDim(destStation) != dimension) continue
                val route = findRouteFromCoords(context, nrData, playerPos, destStation, true) ?: continue
                val routeFinish = route.route.stops.lastOrNull()?.coords?.let(Vec3::atBottomCenterOf) ?: continue
                val cost = route.cost + sprintTime(routeFinish, destCoords)
                if (fastestRouteWithCost.cost > cost) {
                    fastestRouteWithCost = RouteWithCost(route.route, cost)
                    fastestDestStation = destStation
                }
            }

            // If fastestDestStation is not null it means that there is an actual route instead of just sprinting there,
            // and in that case the regular constructor for Route was used, which does not add the final leg on foot,
            // so it needs to be added here
            if (fastestDestStation != null) {
                fastestRouteWithCost.route.stops.add(RouteStop(
                    null,
                    BlockPos.containing(destCoords),
                    getDim(fastestDestStation),
                    null,
                    "On foot",
                    null,
                    false
                ))
            }

            setRoute(context, nrData, fastestRouteWithCost.route, fastestDestStation)
        }.start()
    }

    private data class RouteWithCost(val route: Route, val cost: Double)
    private data class FastestRoute(
        val route: PreCalcRoute,
        val start: String,
        val time: Double,
        val warpStart: Boolean = false,
        val homeWarp: HomeWarp? = null
    )

    private fun findRouteFromCoords(context: CommandContext<FabricClientCommandSource>, nrData: NRData, startCoords: Vec3, dest: String, noDebug: Boolean = false): RouteWithCost? {
        // Find which route is fastest
        var fastestRoute: FastestRoute? = null
        var stationHasBeenSeen = false
        // This function finds the fastest route from a set of coordinates, assuming that you walk to stops. Warps are
        // handled later, and this function is called immediately as well as reused later for home/bed warping.
        fun findFastestRouteOnFoot(startCoords: Vec3, homeWarp: HomeWarp? = null): FastestRoute? {
            var fastestRouteForThisRun: FastestRoute? = null
            for (route in nrData.preCalcRoutes.routes) {
                if (route.value.conns.isEmpty())
                    continue
                if (route.key.second == dest) {
                    stationHasBeenSeen = true

                    val dim = if (homeWarp != null) {
                        homeWarp.location.dimension
                    } else {
                        context.source.player.level().dimension().identifier().path
                    }
                    // Skip if the first station is in a different dimension
                    if (!checkStringDim(getDim(route.key.first), dim)) {
                        continue
                    }

                    val firstStop = route.value.conns.getOrNull(0)
                    // We shouldn't start with a connection on foot
                    if (firstStop?.line == "On foot") {
                        continue
                    }

                    val firstStopCoords = firstStop?.fromCoords
                    // If we can't determine the coords for the initial stop we can't use that route
                    if (firstStopCoords == null) {
                        continue
                    }

                    // If in the Nether, you must be on the same side of the Nether ceiling.
                    if (dim == "the_nether" && !sameSideOfCeil(startCoords.y, firstStopCoords.y.toDouble())) {
                        continue
                    }

                    // Add the time it takes to sprint to the stop
                    val time = route.value.time + sprintTime(startCoords, Vec3.atBottomCenterOf(firstStopCoords)) +
                            // If there is a home/bed warp, add the time for typing the warp too
                            if (homeWarp != null) warpTypingCost else 0.0
                    val froute = FastestRoute(route.value, route.key.first, time, homeWarp != null, homeWarp)
                    if (time < (fastestRoute?.time ?: Double.POSITIVE_INFINITY)) {
                        fastestRoute = froute
                    }
                    if (time < (fastestRouteForThisRun?.time ?: Double.POSITIVE_INFINITY)) {
                        fastestRouteForThisRun = froute
                    }
                }
            }
            return fastestRouteForThisRun
        }
        findFastestRouteOnFoot(startCoords)

        if (!stationHasBeenSeen) {
            if (nrData.network.stationNames.containsKey(dest)) {
                // This is the case for where a station is in the "stations" property in network.jsonc,
                // but is not actually anywhere in the network.
                sendError(context, Component.literal("Could not find route to station \"${dest}\"."))
            } else {
                sendError(context, Component.literal("Could not find station \"${dest}\"."))
            }
            return null
        }

        if (config.debug && !noDebug && fastestRoute != null) {
            sendFeedback(context, Component.nullToEmpty("Fastest regular route is %.1f s, from ${fastestRoute.start}".format(fastestRoute.time)))
        }

        // Check if just sprinting there is faster
        fun checkSprinting(startCoords: Vec3, homeWarp: HomeWarp? = null) {
            val dim = if (homeWarp != null) {
                homeWarp.location.dimension
            } else {
                context.source.player.level().dimension().identifier().path
            }
            if (checkStringDim(getDim(dest), dim)) {
                val coords = nrData.network.findAverageStationCoords(dest)
                if (coords != null) {
                    // If in the Nether, you must be on the same side of the Nether ceiling.
                    if (dim == "the_nether" && !sameSideOfCeil(startCoords.y, coords.y.toDouble())) {
                        return
                    }

                    val directTime = sprintTime(startCoords, Vec3.atBottomCenterOf(coords))
                    if (directTime < (fastestRoute?.time ?: Double.POSITIVE_INFINITY)) {
                        val froute = FastestRoute(PreCalcRoute(0.0, listOf()), dest, directTime, homeWarp != null, homeWarp)
                        fastestRoute = froute

                        if (config.debug && !noDebug) {
                            if (homeWarp != null) {
                                sendFeedback(context, Component.nullToEmpty("Sprinting from ${homeWarp.name} is faster, %.1f s".format(froute.time)))
                            } else {
                                sendFeedback(context, Component.nullToEmpty("Sprinting is faster, %.1f s".format(froute.time)))
                            }
                        }
                    }
                }
            }
        }
        checkSprinting(startCoords)

        // Home/bed warping
        fun checkHomeWarp(name: String, homeidentifier: SerializableBlockPosDim?) {
            if (homeidentifier != null) {
                val fastestRouteForHome = findFastestRouteOnFoot(
                    Vec3.atBottomCenterOf(homeidentifier.blockpos()),
                    HomeWarp(name, homeidentifier)
                )
                if (config.debug && !noDebug && fastestRouteForHome != null) {
                    sendFeedback(context, Component.nullToEmpty("Fastest regular route from $name is %.1f s".format(fastestRouteForHome.time)))
                }
                checkSprinting(Vec3.atBottomCenterOf(homeidentifier.blockpos()), HomeWarp(name, homeidentifier))
            }
        }
        checkHomeWarp("Home", config.home_location)
        checkHomeWarp("Bed", config.bed_location)

        fun checkIfWarpIsFaster(code: String, warpCoords: BlockPos, discount: Double) {
            val route = nrData.preCalcRoutes.routes[Pair(code, dest)]
            if (route != null) {
                val firstStopCoords = route.conns.getOrNull(0)?.fromCoords
                    ?: nrData.network.findAverageStationCoords(dest)
                    ?: return
                // Time is the time it takes for the route, plus the time it takes to sprint to the actual station
                // from the warp, plus 3 seconds as an estimate for typing in and performing the warp
                val time = route.time + sprintTime(Vec3.atBottomCenterOf(warpCoords), Vec3.atBottomCenterOf(firstStopCoords)) + warpTypingCost - discount
                if (config.debug && !noDebug)
                    sendFeedback(context, Component.nullToEmpty("Warping to $code is %.1f s".format(time)))
                if (time < (fastestRoute?.time ?: Double.POSITIVE_INFINITY)) {
                    fastestRoute = FastestRoute(route, code, time, true)
                }
            }
        }
        for (warp in nrData.network.warps) {
            if (!config.your_doom && warp.code == "OKD")
                continue
            checkIfWarpIsFaster(warp.code, warp.coords, warp.discount)
        }

        return if (fastestRoute == null) {
            null
        } else {
            RouteWithCost(Route(
                fastestRoute.start,
                fastestRoute.route.conns,
                nrData.network,
                fastestRoute.warpStart,
                fastestRoute.homeWarp
            ), fastestRoute.time)
        }
    }

    private fun setRoute(context: CommandContext<FabricClientCommandSource>, nrData: NRData, route: Route, dest: String?) {
        currRoutePair.set(Pair(route, 0))
        val name = nrData.network.getNameOrCode(dest)
        val msg = if (dest == null) {
            "Started route to $name"
        } else {
            "Started route to $name ($dest)"
        }
        sendFeedback(context, Component.nullToEmpty(msg))
        sendNextStopMessage(route.stops[0], context)
    }

    private fun tick(clientWorld: ClientLevel) {
        currRouteLogic(clientWorld)
        if (tracker != null && tracker!!.active) {
            trackerLogic()
        }
    }

    private fun currRouteLogic(clientWorld: ClientLevel) {
        val currRoutePair = currRoutePair.get() ?: return
        val currRoute = currRoutePair.first
        val currStop = currRoutePair.second
        if (currStop >= currRoute.stops.size) {
            this.currRoutePair.compareAndSet(currRoutePair, null)
            return
        }
        val player = Minecraft.getInstance().player ?: return
        val playerCoords = player.blockPosition()
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
                sendRouteMessage(Component.nullToEmpty("Route finished!"))
            }
        }
    }

    private fun trackerLogic() {
        val player = Minecraft.getInstance().player
        if (player != null) {
            tracker!!.updatePos(player.blockPosition())
        }
    }

    private fun startMeasuring(player: LocalPlayer) {
        tracker = Tracker(player.blockPosition())
        sendPlayerSystemMessage(player, Component.nullToEmpty("Measuring tracker started"))
    }

    private fun stopMeasuring(player: LocalPlayer, context: CommandContext<FabricClientCommandSource>?) {
        val tracker = tracker
        if (tracker == null) {
            sendError(Component.nullToEmpty("Measuring tracker is not active"), context)
            return
        }
        tracker.stop(player.blockPosition())
        sendPlayerSystemMessage(player, Component.nullToEmpty("Measuring tracker stopped"))
        tracker.copyEndStop()
        sendPlayerSystemMessage(player, Component.nullToEmpty("End stop JSON copied to clipboard"))
    }

    private fun copyMeasuring(both: Boolean, player: LocalPlayer, context: CommandContext<FabricClientCommandSource>?) {
        val tracker = tracker
        if (tracker == null) {
            sendError(Component.nullToEmpty("Measuring tracker is not active"), context)
            return
        }
        if (both) {
            tracker.copyBothStops()
            sendPlayerSystemMessage(player, Component.nullToEmpty("Both stops JSON copied to clipboard"))
        } else {
            tracker.copyEndStop()
            sendPlayerSystemMessage(player, Component.nullToEmpty("End stop JSON copied to clipboard"))
        }
    }

    private fun copyBlockCoords(coords: BlockPos) {
        val clipboard = ClipboardManager()
        clipboard.setClipboard("${coords.x}, ${coords.y}, ${coords.z}")
    }

    private fun stationList(context: CommandContext<FabricClientCommandSource>, ngationCode: String) {
        if (ngationCode.length != 2) {
            sendError(context, Component.literal("This command currently only works with 2-letter codes."))
            return
        }
        if (ngationCode[0] == 'X') {
            sendError(context, Component.literal("This command does not work for ŋationless codes (X__)."))
            return
        }
        val nrData = getNRData(context) ?: return

        sendFeedback(context, Component.literal("All stations in $ngationCode:")
            .setStyle(Style.EMPTY
                .withBold(true)
                .withUnderlined(true)))
        val stations = mutableSetOf<String>()
        for (line in nrData.network.lines) {
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
            val name = nrData.network.stationNames[station]?.getOrNull(0) ?: station
            sendFeedback(context, Component.literal("$name ($station)"))
        }
    }

    private fun sendNextStopMessage(stop: RouteStop, context: CommandContext<FabricClientCommandSource>? = null) {
        val nrData = getNRData(context) ?: return
        val name = nrData.network.getNameOrCode(stop.code, stop.nameOverride)
        var text = if (stop.code == null) {
            Component.literal("Next: $name (")
        } else {
            Component.literal("Next: $name (${stop.code}, ")
        }
        val square = getLineColourSquare(stop.lineCode, nrData)
        if (square != null) {
            text = text.append(square)
        }
        text = text.append("${stop.lineName})")
        sendRouteMessage(text)
    }

    private fun sendRouteMessage(msg: Component) {
        val player = Minecraft.getInstance().player ?: return
        player.sendOverlayMessage(msg)
        sendPlayerSystemMessage(player, msg)
    }

    private fun printRoute(context: CommandContext<FabricClientCommandSource>) {
        val currRoutePair = currRoutePair.get()
        if (currRoutePair == null) {
            sendError(context, Component.literal("No active route"))
        } else {
            val nrData = getNRData(context) ?: return
            sendFeedback(context, Component.literal("Active route:")
                .setStyle(Style.EMPTY
                    .withBold(true)
                    .withUnderlined(true)))
            for (i in currRoutePair.first.stops.indices) {
                val stop = currRoutePair.first.stops[i]
                val name = nrData.network.getNameOrCode(stop.code, stop.nameOverride)
                var text = Component.literal(if (i == currRoutePair.second) "> " else "")
                if (stop.code == null) {
                    text = text.append("$name (")
                } else {
                    text = text.append("$name (${stop.code}, ")
                }
                val square = getLineColourSquare(stop.lineCode, nrData)
                if (square != null) {
                    text = text.append(square)
                }
                text = text.append("${stop.lineName})")
                if (config.debug && (stop.debugTime != null || stop.debugExtraTime != null)) {
                    text = text.append(" (")
                    if (stop.debugTime != null) {
                        text = text.append("%.1f s".format(stop.debugTime))
                        if (stop.debugExtraTime != null) {
                            text = text.append(", ")
                        }
                    }
                    if (stop.debugExtraTime != null) {
                        text.append("station extra time: %.1f s, other extra time: %.1f s".format(stationExtraCost, stop.debugExtraTime - stationExtraCost))
                    }
                    text = text.append(")")
                }
                sendFeedback(context, text)
            }
        }
    }

    private fun sendUpdateNotificationIfNeeded() {
        Thread {
            val updateChecker = NRUpdateChecker()
            val updateInfo = updateChecker.checkForUpdates()
            if (updateInfo == null || !updateInfo.isUpdateAvailable) {
                return@Thread
            }

            val mcc = Minecraft.getInstance()
            mcc.gui.toastManager().addToast(SystemToast(
                SystemToast.SystemToastId(),
                Component.literal("New ")
                    .append(Component.literal("NguhRoutes")
                        .setStyle(Style.EMPTY
                            .withItalic(true)
                            .withBold(true)))
                    .append(Component.literal(" Update")
                        .setStyle(Style.EMPTY
                            .withItalic(false)
                            .withBold(false))),
                Component.literal("NguhRoutes")
                    .setStyle(Style.EMPTY
                        .withItalic(true)
                        .withBold(true))
                    .append(Component.literal(" update ${updateInfo.latestVersion} available!")
                        .setStyle(Style.EMPTY
                            .withItalic(false)
                            .withBold(false)))
            ))
            val player = mcc.player
            if (player != null) {
                sendPlayerSystemMessage(player,
                    Component.literal("NguhRoutes")
                        .setStyle(Style.EMPTY
                            .withItalic(true)
                            .withBold(true))
                        .append(Component.literal(" update ${updateInfo.latestVersion} available! ")
                            .setStyle(Style.EMPTY
                                .withItalic(false)
                                .withBold(false))
                            .append(Component.literal("Link")
                                .setStyle(Style.EMPTY
                                    .withClickEvent(ClickEvent.OpenUrl(java.net.URI(updateInfo.downloadLink)))
                                    .withUnderlined(true)
                                    .withColor(ChatFormatting.BLUE)))))
            }
        }.start()
    }

    /**
     * Returns a coloured text square with the appropriate line colour. Returns null if it is unable to determine
     * the colour. If lineCode is null it immediately returns null.
     */
    private fun getLineColourSquare(lineCode: String?, nrData: NRData): MutableComponent? {
        if (lineCode == null) return null
        val lineColour = nrData.network.lines[lineCode]?.colour
        return if (lineColour != null) {
            Component.literal("■").withColor(lineColour.argb())
        } else {
            null
        }
    }

    /**
     * Gets the NRData and prints a message if it does not exist.
     */
    private fun getNRData(context: CommandContext<FabricClientCommandSource>? = null, printMessage: Boolean = true): NRData? {
        val nrDataLoadError = nrDataLoadError.get()
        val nrData = nrDataLoadError.first
        if (nrData == null && printMessage) {
            if (nrDataLoadError.second == null) {
                val text = Component.nullToEmpty("Data has not loaded yet.")
                sendError(text, context)
            } else {
                sendError(Component.nullToEmpty("Error when loading:"), context)
                sendError(Component.nullToEmpty(nrDataLoadError.second), context)
            }
            return null
        }
        return nrData
    }

    private fun sendError(text: Component, context: CommandContext<FabricClientCommandSource>? = null) {
        if (context != null) {
            sendError(context, text)
        } else {
            val player = Minecraft.getInstance().player ?: return
            sendPlayerSystemMessage(player, text.toFlatList(Style.EMPTY.withColor(TextColor.fromLegacyFormat(ChatFormatting.RED)))[0])
        }
    }

    private fun checkPlayerDim(dim: String, clientWorld: Level): Boolean {
        return clientWorld.dimension().identifier() == Identifier.parse(dim)
    }

    private fun checkStringDim(dim: String, otherDim: String): Boolean {
        return Identifier.parse(dim) == Identifier.parse(otherDim)
    }
}
