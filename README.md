# SwordPickupFX

A small, open-source Paper plugin for the **Bloomheart SMP** Minecraft server.

## What it does

- Announces in chat when a player picks up the Lifesteal Sword.
- Plays thunder and end-portal sounds to everyone online.
- Sends a short private message to the player who picked it up.
- Adds an opening-lid altar animation with particles and sounds for the sword chest.

## What it does NOT do

- It does not connect to the internet or any outside server.
- It does not collect data, run commands on your computer, or download anything.
- It is a server-side plugin. It never runs on players' computers.

You can read every line of the code in `src/main/java`. All messages and sounds can be changed in `config.yml`.

## Build it yourself

Requires Java 25 and Gradle:

    gradle build

The jar will be in `build/libs/`. A GitHub Actions workflow in `.github/workflows` builds it automatically on every push.

## Related

Resource pack: https://github.com/Lamoura4/LifestealSwordPack
