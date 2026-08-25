# Dungons

Shared source repository for the dungeon server plugins.

## Plugins

- [`ClassSkills`](plugins/classskills) — class progression, skills, kits, combat passives, and abilities.

## Building ClassSkills

Use Java 21 or newer (the current build is configured for Java 25), then run:

```powershell
cd plugins/classskills
.\gradlew.bat shadowJar
```

The build output is deliberately ignored by Git. Share a finished plugin JAR through a GitHub Release rather than committing it under `build/`.
