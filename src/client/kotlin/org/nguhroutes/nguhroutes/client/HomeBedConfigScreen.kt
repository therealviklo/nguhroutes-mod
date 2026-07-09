package org.nguhroutes.nguhroutes.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.option.GameOptionsScreen
import net.minecraft.text.Text


class HomeBedConfigScreen(val config: Config, parent: Screen?) : GameOptionsScreen(
    parent,
    MinecraftClient.getInstance().options,
    Text.literal("Home/Bed Config")
) {
    override fun addOptions() {
        if (body != null) {
            body?.addAll(config.homeBedScreenWidgets())
        }
    }
}