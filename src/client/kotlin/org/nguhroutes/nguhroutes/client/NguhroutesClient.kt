package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.text.Text


class NguhroutesClient : ClientModInitializer {
    var county = 0

    fun registerCommand(name: String, command: Command<FabricClientCommandSource?>) {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher: CommandDispatcher<FabricClientCommandSource?>?, registryAccess: CommandRegistryAccess? ->
            dispatcher!!.register(
                ClientCommandManager.literal(name)
                    .executes(command)
            )
        })
    }

    override fun onInitializeClient() {
       registerCommand("clienttater", Command { context: CommandContext<FabricClientCommandSource?>? ->
           county += 1
            context!!.getSource()!!.sendFeedback(Text.literal("Called /clienttater with no arguments. $county"))
            1
       })
        registerCommand("clienttater2", Command { context: CommandContext<FabricClientCommandSource?>? ->
            county -= 1
            context!!.getSource()!!.sendFeedback(Text.literal("Called /clienttater2 with no arguments. $county"))
            1
        })
    }
}
