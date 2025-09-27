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
    var jsonData: AtomicReference<JsonData?> = AtomicReference(null)
    var currRoute: Route? = null
    var currStop = 0

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
            val routesJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/routes.json")
            val networkJson = downloadJson("https://nguhroutes.viklo.workers.dev/json/network.json")
            jsonData.set(JsonData(routesJson, networkJson))
        }.start()
    }

    private fun registerCommand(command: LiteralArgumentBuilder<FabricClientCommandSource?>) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource?>?, _/*registryAccess*/: CommandRegistryAccess? ->
            dispatcher!!.register(command)
        })
    }

    override fun onInitializeClient() {
        registerCommand(ClientCommandManager.literal("snoop")
            .executes { context: CommandContext<FabricClientCommandSource?>? ->
                context!!.getSource()!!.sendFeedback(Text.literal(jsonData.get()?.routes?.date))
                1
            })
        registerCommand(ClientCommandManager.literal("nr")
            .then(ClientCommandManager.argument("dest", StringArgumentType.string())
                .executes { context: CommandContext<FabricClientCommandSource?>? ->
                    val start = "MZS"
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    val jsonData = jsonData.get()
                    if (jsonData == null) {
                        context!!.getSource()!!.sendFeedback(Text.literal("Data has not loaded yet."))
                        return@executes 1
                    }
                    val route = jsonData.routes.routes["$start`$dest"]
                    if (route == null || route.isEmpty()) {
                        context!!.getSource()!!.sendFeedback(Text.literal("Could not find route."))
                        return@executes 1
                    }
                    currStop = 0
                    currRoute = Route(start, route, jsonData.network)
                    for (stop in currRoute!!.stops) {
                        context!!.getSource()!!.sendFeedback(Text.literal("${stop.code} (${stop.line}) (${stop.coords})"))
                    }
                    1
                }))
        ClientTickEvents.END_WORLD_TICK.register { clientWorld -> tick(clientWorld) }
    }

    private fun tick(clientWorld: ClientWorld) {
        val currRoute = currRoute ?: return
        if (currStop >= currRoute.stops.size) {
            this.currRoute = null
            this.currStop = 0
            return
        }
        val inst = MinecraftClient.getInstance()
        val player = inst.player ?: return
        val playerCoords = player.blockPos
        if (distLess(playerCoords, currRoute.stops[currStop].coords, 50) &&
            clientWorld.registryKey.value == Identifier.of(currRoute.stops[currStop].dimension) &&
            (currStop == 0 || currRoute.stops[currStop].dimension != currRoute.stops[currStop - 1].dimension || distCloser(
                playerCoords,
                currRoute.stops[currStop].coords,
                currRoute.stops[currStop - 1].coords))) {
            currStop += 1
            if (currStop < currRoute.stops.size) {
                val nextStop = currRoute.stops[currStop]
                val text = Text.of("Next: ${nextStop.code} (${nextStop.line})")
                player.sendMessage(text, true)
                player.sendMessage(text, false)
            }
        }
    }
}
