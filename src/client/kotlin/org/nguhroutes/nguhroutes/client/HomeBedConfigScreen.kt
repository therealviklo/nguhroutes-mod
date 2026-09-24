package org.nguhroutes.nguhroutes.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component


class HomeBedConfigScreen(val config: Config, parent: Screen) : OptionsSubScreen(
    parent,
    Minecraft.getInstance().options,
    Component.literal("Home/Bed Config")
) {
    override fun addOptions() {
        if (list != null) {
            list?.addSmall(config.homeBedScreenWidgets())
        }
    }
}