package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text


class NguhroutesClient : ClientModInitializer {
    var county = 0

    fun registerCommand(command: LiteralArgumentBuilder<FabricClientCommandSource?>) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource?>?, _/*registryAccess*/: CommandRegistryAccess? ->
            dispatcher!!.register(command)
        })
    }

    override fun onInitializeClient() {
        registerCommand(ClientCommandManager.literal("settater")
            .then(ClientCommandManager.argument("county", StringArgumentType.string())
                .executes { context: CommandContext<FabricClientCommandSource?>? ->
                    val newCounty = StringArgumentType.getString(context, "county")
                    county = if (newCounty == "nguh") 31 else 0
                    context!!.getSource()!!.sendFeedback(Text.literal("Settater. $county"))
                    1
                }))
    }
}
