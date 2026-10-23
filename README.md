# Minecraft Office

Fabric 26.3 mod: hologram monitors in the world that show players' real desktops.

## Build

Needs a JDK to launch Gradle (any 17+; Gradle then provisions JDK 25 for the daemon and compiler).

```
./gradlew build test runGametest   # compile, unit tests (mod/src/test), server gametests (mod/src/gametest)
./gradlew runServer                # dev server (put eula=true in run/eula.txt)
./gradlew runClientGametest        # client gametests; needs a display (xvfb-run on Linux CI)
./gradlew runClient                # dev client
```

Layout: one Gradle build at the repo root; all mod sources live under `mod/src/{main,client,test,gametest}`.
