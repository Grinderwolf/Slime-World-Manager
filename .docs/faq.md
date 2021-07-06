
## FAQ

* Which Spigot versions is this compatible with?

Currently, SWM can run on any Spigot version from 1.8.8 up to 1.17.1.

* Can I override the default world?

Yes, you can! However, that requires doing some extra steps. Take a look at the [Installing Slime World Manager](usage/install.md) page.

* My server stops when booting up with a 'Failed to find ClassModifier classes' error.

That's because you haven't followed the extra steps needed for Spigot 1.14 correctly. Go to the [Installing Slime World Manager](usage/install.md) and follow the steps described there.

* I'm getting a `javassist.CannotCompileException: [source error] acceptConnections() not found in net.minecraft.server.v1_14_R1.ServerConnection` error on startup.

You are running an outdated spigot version. Please update to the latest version. If this keeps happening after updating, open an issue.

* Is SWM compatible with Multiverse-Core?

Multiverse-Core detects SWM worlds as unloaded, as it cannot find the world directory, and then just ignores them. Although there should be no issues, MV commands won't work with SWM worlds.

* What's the world size limit?

The Slime Region Format can handle up a 46340x4630 chunk area. That's the maximum size that SWM can _theoretically_ handle, given enough memory. However, having a world so big is not recommended at all.

There's not an specific value that you shouldn't exceed _- except for the theoretical limit, of course_. SWM keeps a copy of all the chunks loaded in memory until the world is unloaded, so the more chunks you have, the bigger the ram usage is. How far you want to go depends on how much ram you are willing to let SWM use. Moreover, the ram usage per chunk isn't a constant value, as it depends on the actual data stored in the chunk.
