# ***NguhRoutes*** mod
This is a mod that provides route planning for Cenrail and other rail networks on the Nguhcraft Minecraft server. Its main functionality is that you can set a route to a station and it tells you what station to go to next to get there.

**To download the mod, go to [the releases page](https://github.com/therealviklo/nguhroutes-mod/releases).** Note that the mod requires you to have the Kotlin support installed, which you probably have for the main Nguhcraft mod, but you may have to get a newer version. If there is a such an issue it will give you an error when you try to launch.

The main command is `/nguhroutes`, with a shortcut `/nr`, and there is also `/nrs` as a shortcut for `/nguhroutes start`. If you don't want to include routes that cross into the Nether, do `/nguhroutes reload nonether`. **See [the wiki page](https://mc.nguh.org/wiki/NguhRoutes) for more information on how to use the mod.**

The mod uses [this file on the wiki](https://mc.nguh.org/wiki/Data:NguhRoutes/network.json) for its data. It downloads this file and precalculates all shortest routes whenever you start the game. For me this is done by the time I have actually gotten onto the server, but if this becomes a problem for some people I can look into adding caching so that it saves the routes on your computer and uses those if the network file on the wiki has not been updated.
