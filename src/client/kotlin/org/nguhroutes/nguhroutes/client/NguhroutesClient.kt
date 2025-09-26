package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.json.Json
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text
import java.net.URI
import java.util.concurrent.atomic.AtomicReference


class NguhroutesClient : ClientModInitializer {
    var jsonData: AtomicReference<JsonData?> = AtomicReference(null)

    init {
        loadJson()
    }

    fun loadJson() {
        Thread {
            val url = URI("https://nguhroutes.viklo.workers.dev/json/routes.json").toURL()
            val data = url.openStream().readAllBytes().decodeToString()
            val json = Json.parseToJsonElement(data)
            jsonData.set(JsonData(json))
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
                    val dest = StringArgumentType.getString(context, "dest").uppercase()
                    val jsonData = jsonData.get()
                    if (jsonData == null) {
                        context!!.getSource()!!.sendFeedback(Text.literal("Data has not loaded yet."))
                        return@executes 1
                    }
                    val route = jsonData.routes.routes["MZS`${dest}"]
                    if (route == null || route.isEmpty()) {
                        context!!.getSource()!!.sendFeedback(Text.literal("Could not find route."))
                        return@executes 1
                    }
                    for (stop in route) {
                        context!!.getSource()!!.sendFeedback(Text.literal("${stop.station} (${stop.line})"))
                    }
                    1
                }))
    }
}
