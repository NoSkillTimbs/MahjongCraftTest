# Card games on the Game Table

MahjongCraft's table also hosts **Yu-Gi-Oh!** and **Pokemon TCG** games, between two players or
against a bot. It's all in the one MahjongCraft jar.

Not affiliated with or endorsed by Konami, Nintendo, The Pokemon Company or Creatures. The game
rules are implemented from scratch. The mod only plays **real cards**, but none are included in it:
your server downloads the card data from the public card databases the first time it starts, for
private play (see below).

## Playing

1. Craft a **Game Table** (see below) and place it.
2. Craft a deck: 3 paper + brown dye = **Yu-Gi-Oh! Deck**, 3 paper + blue dye = **Pokemon TCG Deck**
   (also in the Tools creative tab).
3. Right-click the Game Table with the deck to open a game there. Another player right-clicks the
   same table with the same kind of deck to join, or the host clicks **Play against a bot** (or
   sneak + right-clicks with the deck). The bot sits at the far end of the table as MahjongCraft's
   mahjong bot figure.
4. Each player picks a deck. Then the cards are played **on the table in the world**: each player's
   zones (Monster Zone, Spell & Trap Zone, Active, Bench...) appear in front of them when the game
   starts and are cleared away when it ends; each deck is one stack, and drawn cards fly from it to
   the hand, which stands at the table's edge facing its owner. Everyone nearby can watch the game.
   Only you see the faces of your hand; your face-down cards show to you darkened with **SET** on
   them and to everyone else as card backs.
5. Your game screen is a see-through layer over the table: the question and buttons on the right,
   scores at the top left. Cards you can use **glow gold**. **Left-click any card** for a close-up
   to read it, with the things you can do with it (Summon, Set, Attach, Attack...). Pokemon TCG:
   **Retreat** is at the bottom left of the close-up, apart from the attacks. Click a graveyard or
   discard pile to look through it. Choices about cards that aren't on the table open a tray.
   Hold the **right mouse button and drag** to look around; right-click or Escape closes what's
   open.
6. **Concede** is at the top right. Concede and Retreat both ask "are you sure?" first. **Hide**
   closes the screen (the game stays on the table); right-click the table to reopen it.

A table that is in use for mahjong can't host a card game, and vice versa. Leaving the server or
breaking the table ends the game.

## The Game Table

The mahjong table is now the **Game Table**. Its recipe (3 green or lime carpet on top, a fence in
the middle, a slab at the bottom) uses one kind of wood, and the wood picks the design:

| Wood | Design |
|---|---|
| Bamboo | Classic (the original mahjong table) |
| Oak | Forest |
| Spruce | Snowy Taiga |
| Birch | Flower Meadow |
| Jungle | Jungle |
| Acacia | Desert |
| Dark oak | Dark Forest |
| Mangrove | Swamp |
| Cherry | Cherry Grove |
| Crimson | Volcano |
| Warped | Warped Forest |

Every design plays mahjong, Yu-Gi-Oh! and Pokemon TCG. Breaking a table gives back the same design.

## Real cards

There are no made-up cards: every deck is built from real cards. The first time a server (or a
single-player world) starts with Table Cards, it downloads the card databases by itself, which
takes a minute or two; until then the tables say there are no decks yet. An operator can also
download them again at any time (for new sets):

```
/tablecards import all        (or: yugioh / pokemon)
```

- **Yu-Gi-Oh!** card data comes from the YGOPRODeck API (one download, as its terms ask).
  **Pokemon** card data comes from the open data set at github.com/PokemonTCG/pokemon-tcg-data.
- The data is saved in `config/tablecards/imported/` on that server, for private play. Nothing is
  redistributed.
- **Card pictures:** each player's game downloads the pictures of the real cards it shows, once,
  from the same sources (images.ygoprodeck.com, images.pokemontcg.io), and keeps them in
  `.minecraft/tablecards-cache/`. The **Card art** button on the game screen turns this off; the
  mod then draws plain card faces with each card's name and stats.
- **Only cards the game can play exactly as printed are imported.** Today that is:
  - Yu-Gi-Oh!: all Normal Monsters; every card of the archetype decks below, each with its own
    script written from its card text (effect monsters, Fusion/Ritual/Synchro monsters, Spells and
    Traps); and other Spells/Traps whose whole text matches a supported effect (Pot of Greed,
    Raigeki, Dark Hole, Monster Reborn, Mystical Space Typhoon, Heavy Storm, Harpie's Feather
    Duster, Fissure, Mirror Force, Magic Cylinder, Sakuretsu Armor, Negate Attack, Trap Hole,
    burn/LP-gain cards and simple Equip Spells).
  - Pokemon: about 5,000 Pokemon (no Abilities; every attack's text must match a supported effect,
    including Special Conditions, coin flips, Bench damage and switching), the common Items and
    Supporters (Professor's Research, Boss's Orders, Rare Candy, Ultra Ball, Nest Ball, Switch,
    Potion, Iono, ...) and all basic Energy. ex/V/GX/VMAX Pokemon give the right number of Prizes.
- `imported/ygo-report.txt` and `ptcg-report.txt` list what was left out and why.

After importing, Yu-Gi-Oh! gets ready-made **archetype decks** built around current competitive and
popular lists (YGOPRODeck / Master Duel Meta, 2025), using only cards the game plays exactly:

- **Blue-Eyes White Dragon Deck**: Blue-Eyes Alternative, Dragon Spirit of White, Sage with Eyes of
  Blue, the White Stones, Chaos MAX + Chaos Form, Trade-In, Silver's Cry, Return of the Dragon Lords;
  Extra Deck Ultimate Dragon, Twin Burst, Spirit Dragon, Azure-Eyes.
- **Dark Magician Deck**: Magician's Rod, Apprentice Illusion Magician, Magicians' Souls, Mahad, Dark
  Magical Circle, Eternal Soul, Magician's Salvation, Secrets of Dark Magic, The Eye of Timaeus...;
  Extra Deck Dragon Knights and The Dark Magicians.
- **Elemental HERO Deck**: Stratos, Bubbleman, Wildheart, Shadow Mist, Polymerization, Miracle
  Fusion, E - Emergency Call, Hero Signal, Skyscraper...; nine Elemental HERO Fusions.

More archetypes (Monarch, Dragonmaid...) come in later batches. Generic Link/Synchro/Xyz staples
that meta lists also run are left out until the game supports those summons. Every game also gets
auto-built decks of Normal Monsters (one per Attribute / energy type), plus any official Pokemon
theme deck whose cards are all supported.

### Your own deck lists

Put deck lists in `config/tablecards/decks/` and run `/tablecards reload`:

- Yu-Gi-Oh!: `.ydk` files (Main Deck only; Extra/Side are ignored).
- Pokemon: `.txt` files in the Pokemon TCG Live export format (`4 Scorbunny SSH 30`, `20 Basic {R} Energy SVE 2`).

`/tablecards decks` lists every deck and explains any list that didn't load (for example, which
card isn't supported).

## Rules covered (core rules)

**Yu-Gi-Oh!:** 8000 LP, 40-60 card Main Deck plus up to 15 Extra Deck cards, draw 5. The player
going first skips their first draw and Battle Phase. One Normal Summon or Set per turn (levels 5-6
need 1 tribute, 7+ need 2), Flip Summons, one position change per turn, battle with piercing and
direct attacks. Card effects with chains: when something is activated the other player can respond
with a faster effect (Quick Effects and hand traps like Ash Blossom, Quick-Play Spells, Traps from
the turn after they're set), and the chain resolves backwards; trigger effects activate after the
chain, the turn player's first. Once-per-turn limits, targeting and "can't be targeted/destroyed"
protections, Special Summon procedures, Fusion, Ritual and Synchro Summons, Continuous and Field
Spells/Traps, End Phase effects. Hand limit 6. Lose at 0 LP or when you can't draw.
Not yet: Xyz, Link and Pendulum Summons, and the cards that use them.

**Pokemon TCG:** 60-card decks, draw 7 with mulligans, 6 Prize cards, Active + 5 Bench. Bench
Basics, evolve (not on your first turn or the turn a Pokemon arrives), one Energy per turn, Items,
one Supporter per turn, retreat, attacks with Energy costs (including Colorless), weakness x2,
resistance -30. Knock Outs give Prizes; win by Prizes, by your opponent having no Pokemon, or
when they can't draw. The first player can't attack or play a Supporter on turn 1.
Also: Special Conditions (Asleep, Burned, Confused, Paralyzed, Poisoned) with the Pokemon Checkup,
coin flips, Bench damage, multi-Prize Pokemon (ex/V/GX/VMAX). Not yet: Abilities, Tools, Stadiums, special Energy.

## Supporting more cards

Which real cards can be imported depends on the wordings the importers recognise
(`YgoImporter.java`, `PtcgImporter.java`) and the effects the engines implement (listed at the
top of `YgoCard.java` and `PtcgCard.java`). Yu-Gi-Oh! cards with their own effects are scripted
by name in `engine/ygo/Scripts*.java` (one class per archetype), and the ready-made decks are
listed in `YgoPrebuilt.java`. `config/tablecards/imported/*-report.txt` lists what
was left out and why.

## Layout

- `src/main/java/com/tablecards/engine/` - both rules engines and their bots. **No Minecraft
  imports**, so they compile and run with plain `javac`.
- `src/main/java/com/tablecards/` - deck items, table clicks, game sessions, and what a game does
  around its table (the bot figure, sending the table to people watching).
- `src/main/java/com/tablecards/client/` - `TableLayout` places every card on the table and
  `TableRenderer` draws it in the world (called from MahjongCraft's table renderer);
  `GameView` is the see-through game screen (drawn through a small `Canvas` interface, so it can
  be rendered to an image in tests); `CardImages` downloads and caches card pictures.
- `tools/gen_card_textures.py` - card frames, playmats and Energy icons; `tools/gen_table_themes.py`
  - the Game Table designs.

## Test the engines (no Gradle needed)

With real card data downloaded (the build does the same):

```bash
mkdir -p out && javac -d out $(find src/main/java/com/tablecards/engine -name '*.java')
curl -sSL -o cardinfo.json https://db.ygoprodeck.com/api/v7/cardinfo.php
git clone --depth 1 https://github.com/PokemonTCG/pokemon-tcg-data
java -cp out com.tablecards.engine.ImportFiles ygo cardinfo.json ygo.json
java -cp out com.tablecards.engine.ImportFiles ptcg pokemon-tcg-data ptcg.json
java -cp out com.tablecards.engine.Simulate 2000 both ygo.json ptcg.json
```

## Build

The MahjongCraft build (`./gradlew build`, JDK 21) includes the card games; GitHub Actions builds
the jar and also tests both engines with real card data.
