# Table Cards (Minecraft 1.21.1, Fabric)

An add-on for MahjongCraft that lets the mahjong table also host **Yu-Gi-Oh!** and
**Pokemon TCG** games, between two players or against a bot.

Not affiliated with or endorsed by Konami, Nintendo, The Pokemon Company or Creatures. The game
rules are implemented from scratch. The starter decks that ship with the mod are original cards;
no real card text or artwork is included in the mod. Real cards can be imported on your own server
for private play (see below).

## Playing

1. Craft a deck: 3 paper + brown dye = **Yu-Gi-Oh! Deck**, 3 paper + blue dye = **Pokemon TCG Deck**
   (also in the Tools creative tab).
2. Right-click a mahjong table with the deck to open a game there.
3. Another player right-clicks the same table with the same kind of deck to join, or the host
   clicks **Play against a bot** (or sneak + right-clicks with the deck).
4. Each player picks a deck, then the game screen shows the table: both players' zones, your hand
   along the bottom, the opponent's hand as card backs at the top, life points (or Prizes) on the
   left with a big preview of the card under your mouse, and the question being asked on the right.
5. Cards you can use **glow gold**. Click one to see what you can do with it (Summon, Set, Attach,
   Attack...), then click the action. When you pick a target, the possible targets glow. Choices
   about cards that aren't on the table (searching your deck, picking from the graveyard) open a
   tray of cards. Click a graveyard or discard pile to look through it. "All actions as a list" on
   the right shows every option as text. Right-click or Escape closes a menu.
6. **Concede** is at the top right (click it twice). **Hide** closes the screen; right-click the
   table to reopen it.

A table that is in use for mahjong can't host a card game, and vice versa. Leaving the server or
breaking the table ends the game.

## Real cards

An operator can import real cards on their own server:

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
  mod then draws its own card faces. The built-in starter cards always use the mod's own faces.
- **Only cards the game can play exactly as printed are imported.** Today that is:
  - Yu-Gi-Oh!: all Normal Monsters, plus Spells/Traps whose whole text matches a supported effect
    (Pot of Greed, Raigeki, Dark Hole, Monster Reborn, Mystical Space Typhoon, Heavy Storm,
    Harpie's Feather Duster, Fissure, Mirror Force, Magic Cylinder, Sakuretsu Armor, Negate Attack,
    Trap Hole, burn/LP-gain cards and simple Equip Spells). Effect, Extra Deck, Pendulum and Ritual
    monsters aren't supported. Quick-Play Spells can only be used in your own Main Phase.
  - Pokemon: about 5,000 Pokemon (no Abilities; every attack's text must match a supported effect,
    including Special Conditions, coin flips, Bench damage and switching), the common Items and
    Supporters (Professor's Research, Boss's Orders, Rare Candy, Ultra Ball, Nest Ball, Switch,
    Potion, Iono, ...) and all basic Energy. ex/V/GX/VMAX Pokemon give the right number of Prizes.
- `imported/ygo-report.txt` and `ptcg-report.txt` list what was left out and why.

After importing, every game also gets auto-built decks made of real cards (one per Attribute /
energy type), plus any official Pokemon theme deck whose cards are all supported.

### Your own deck lists

Put deck lists in `config/tablecards/decks/` and run `/tablecards reload`:

- Yu-Gi-Oh!: `.ydk` files (Main Deck only; Extra/Side are ignored).
- Pokemon: `.txt` files in the Pokemon TCG Live export format (`4 Scorbunny SSH 30`, `20 Basic {R} Energy SVE 2`).

`/tablecards decks` lists every deck and explains any list that didn't load (for example, which
card isn't supported).

## Rules covered (core rules)

**Yu-Gi-Oh!:** 8000 LP, 40-card decks, draw 5. The player going first skips their first draw and
Battle Phase. One Normal Summon or Set per turn (levels 5-6 need 1 tribute, 7+ need 2), Flip
Summons, one position change per turn, battle damage for attack vs attack and attack vs defense,
direct attacks. Normal and Equip Spells; Traps that respond to an attack or a Normal Summon
(from the turn after they are set). Hand limit 6. Lose at 0 LP or when you can't draw.
Not yet: effect monsters, Extra Deck summons, Continuous/Field Spells and Traps, longer chains.

**Pokemon TCG:** 60-card decks, draw 7 with mulligans, 6 Prize cards, Active + 5 Bench. Bench
Basics, evolve (not on your first turn or the turn a Pokemon arrives), one Energy per turn, Items,
one Supporter per turn, retreat, attacks with Energy costs (including Colorless), weakness x2,
resistance -30. Knock Outs give Prizes; win by Prizes, by your opponent having no Pokemon, or
when they can't draw. The first player can't attack or play a Supporter on turn 1.
Also: Special Conditions (Asleep, Burned, Confused, Paralyzed, Poisoned) with the Pokemon Checkup,
coin flips, Bench damage, multi-Prize Pokemon (ex/V/GX/VMAX). Not yet: Abilities, Tools, Stadiums, special Energy.

## Adding cards and decks

Cards and starter decks live in `src/main/resources/tablecards/ygo_cards.json` and
`ptcg_cards.json`. Add a card under `"cards"` and list it in a deck under `"decks"`; the effect
names each engine understands are listed at the top of `YgoCard.java` and `PtcgCard.java`.

## Layout

- `src/main/java/com/tablecards/engine/` - both rules engines and their bots. **No Minecraft
  imports**, so they compile and run with plain `javac`.
- `src/main/java/com/tablecards/` - the Fabric mod: deck items, table clicks, game sessions.
- `src/main/java/com/tablecards/client/` - the game screen. `GameView` draws the table and handles
  clicks through a small `Canvas` interface (no Minecraft classes), so it can be rendered to an
  image in tests; `McCanvas`/`TableGameScreen` connect it to Minecraft; `CardImages` downloads and
  caches card pictures.
- `../tools/gen_card_textures.py` - generates the card frames, card backs, playmats and Energy
  icons in `assets/tablecards/textures/gui/` (all original artwork).

## Test the engines (no Gradle needed)

```bash
mkdir -p out && javac -d out $(find src/main/java/com/tablecards/engine -name '*.java')
cp -r src/main/resources/tablecards out/
java -cp out com.tablecards.engine.Simulate 2000 both
```

## Build

`./gradlew build` (JDK 21; same Fabric versions as MahjongCraft). In the MahjongCraft repo the
GitHub Actions workflow builds it and uploads `tablecards-artifacts-<run>`.
