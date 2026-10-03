# Shandalar (Minecraft 1.21.1, Fabric)

A Minecraft take on the classic Magic: The Gathering game *Shandalar*: an overworld
map, random duels, quests and deckbuilding. Real card names are used, so this build is
for private play only and must not be published without Wizards of the Coast's permission.

## Layout

- `src/main/java/com/shandalar/ShandalarMod.java` - Fabric entry point; loads the card pool at startup.
- `src/main/resources/fabric.mod.json` - mod metadata (needs Fabric API).
- `src/main/java/com/shandalar/engine/` - the duel engine. **No Minecraft imports**, so it
  compiles and runs with plain `javac`.
- `src/main/resources/data/shandalar/cards.json` - card data.
- `engine/Simulate.java` - headless AI-vs-AI runner for smoke tests and deck balancing.

## Try the engine (no Gradle needed)

```bash
mkdir -p out
javac -d out $(find src/main/java/com/shandalar/engine -name '*.java')
java -cp out com.shandalar.engine.Simulate src/main/resources/data/shandalar/cards.json 2000 sample-game.log
```

## Build the mod

Needs JDK 21. Uses Fabric Loom 1.7 with the bundled Gradle 8.8 wrapper, and the same Minecraft,
Yarn, Fabric Loader and Fabric API versions as MahjongCraft (see `gradle.properties`).

```bash
./gradlew build       # jar in build/libs
./gradlew runClient   # dev client
```

In the MahjongCraft repo the GitHub Actions workflow builds this project too and uploads
`shandalar-artifacts-<run>` next to the MahjongCraft jar.

## Engine rules today

- Turn: untap, draw, main, combat, main, discard to 7.
- Card types: land, creature, instant, sorcery. Keywords: flying, reach, first strike,
  haste, vigilance.
- Effects: damage, draw, gain life, destroy, pump.
- Spells resolve immediately and are cast in main phases only (no stack or priority).
- `Agent` interface is how the engine asks questions, so the AI and a future GUI player
  plug in the same way.

## Roadmap

1. **Engine depth:** stack and priority, instant-speed play, mana abilities (Llanowar Elves),
   auras and enchantments, triggered abilities, counterspells, trample and deathtouch,
   AI that uses pump spells in combat. Keep tests via `Simulate`.
2. **Duel GUI:** a `Menu`/`Screen` showing hand, battlefield and graveyards; a card item
   with textures; a client-to-server action packet that feeds a human `Agent`.
3. **Card collection and deckbuilder:** per-player data attachment (Fabric API) storing owned cards and
   decks, a deck editor screen, booster pack items, deck legality (40 cards minimum).
4. **Overworld:** a Shandalar dimension (or a map item) with towns, dungeons and
   color-themed regions; wandering duelists as entities that start a duel on contact.
5. **Quests and progression:** quest givers in towns, rewards (cards, gold, amulets),
   ante rules, and a color-aligned enemy wizard campaign.
