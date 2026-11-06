@echo off
rem ============================================================
rem Starts the RuneLite client JVM directly, WITHOUT the launcher.
rem
rem Why: RuneLite's client ignores --developer-mode whenever a
rem launcher started it (decompiled gate: developerMode =
rem --developer-mode AND getLauncherVersion() == null, and the
rem launcher always sets -Drunelite.launcher.version). Only a
rem launcher-less start lets PluginManager.loadSideLoadPlugins()
rem actually scan ~\.runelite\sideloaded-plugins.
rem
rem Classpath = the same artifacts the launcher uses:
rem   repository2\client-1.13.6.jar            (net.runelite.client)
rem   + repository2\k\*                        (runelite-api, libs, Kuri framework)
rem kuri-runelite-v1.7.65.jar is listed explicitly first so the
rem older cached framework versions (56/58/64) can't shadow it.
rem ============================================================
"C:\Users\sahij\AppData\Local\RuneLite\jre\bin\java.exe" -Xmx768m -cp "C:\Users\sahij\.runelite\repository2\client-1.13.6.jar;C:\Users\sahij\.runelite\repository2\k\kuri-runelite-v1.7.65.jar;C:\Users\sahij\.runelite\repository2\k\*" net.runelite.client.RuneLite --developer-mode
echo.
echo Client exited. For sideload confirmation check:
echo   %USERPROFILE%\.runelite\logs\client.log  for "Side-loading plugin"
pause
