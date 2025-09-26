package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
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
    var stuff: AtomicReference<JsonData> = AtomicReference(null)

    init {
        loadJson()
    }

    fun loadJson() {
        Thread {
            val url = URI("https://nguhroutes.viklo.workers.dev/json/routes.json").toURL()
            val data = url.openStream().readAllBytes().decodeToString()
            val json = Json.parseToJsonElement(data)
            stuff.set(JsonData(json))
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
                context!!.getSource()!!.sendFeedback(Text.literal(stuff.get().routes.date))
                1
            })
    }
}
