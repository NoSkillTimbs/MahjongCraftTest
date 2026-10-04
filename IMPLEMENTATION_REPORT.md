# MahjongCraft implementation report

Base: `NoSkillTimbs/MahjongCraftTest`, commit `5cdc10e78e47febb5de405e457d883112f7756d7`.
Work continued in the existing checkout. All nine requested features have implementation code.
The baseline-build gate was waived by the user. No Java, Minecraft, Fabric, Gradle, dependency
version, or dependency-resolution configuration was changed.

**Full Java 21/Fabric/Kotlin compilation and Minecraft runtime testing: NOT VERIFIED due to
environment limitations.** No mod JAR is supplied or claimed to have been built. The narrower
checks listed below actually ran and passed. No changes were pushed to GitHub.

## 1. Architecture discovered

- Minecraft **1.21.1**, Fabric Loader **0.17.2**, Fabric API **0.116.6+1.21.1**;
  Gradle wrapper **8.8**, Loom **1.7-SNAPSHOT**, Java **21**, Kotlin **2.0.21**.
- `engine/ygo` and `engine/ptcg` are independent, Minecraft-free Java rules engines with bots.
  `BaseGame`, `Board`, `Decision`, `ViewJson` and `DeckLists` provide shared plumbing.
- `Sessions` owns server table sessions; `TableSession` owns seats, captured deck choices and
  the engine. `ChoosePayload` carries sequence-checked choices; `ViewPayload` carries each
  recipient's permitted view. `TableWorld` stages entities and sends public views to spectators.
- `TableLayout` computes world card/zone geometry. `TableRenderer` renders it and projects the
  same items into hit regions. `GameView` already supports world and flat-board presentation;
  `TableGameScreen` connects it to Minecraft input and networking.
- `CardPools` owns the imported card libraries and server decks. `DeckLists.ydk` and
  `DeckLists.ptcg` already load external decks from `config/tablecards/decks/`.
- Kotlin `MahjongTable`, its payload listener and the existing Mahjong game manager control
  Mahjong entry. `MahjongStool` and `SeatEntity` already provide mounting/furniture behavior.
- The existing GUI card-back assets match the supplied references. The Yu-Gi-Oh! item model
  referenced a missing item texture. The Pokémon item model used a different inventory asset.
- In the supplied upload order, image 6 is the broken inventory screenshot, image 7 is the
  Yu-Gi-Oh! back and image 8 is the Pokémon back; those contents resolve the numbering mismatch.

## 2. Requirement-by-requirement implementation

| # | Requirement | Implemented behavior |
|---|---|---|
| 1 | Yu-Gi-Oh! View Mode | `View Mode: Default View / Duel View` sits between Card art and Hide. Default retains the world view; Duel uses the existing dedicated board UI. Switching neither sends a gameplay choice nor creates an engine/session. |
| 2 | Yu-Gi-Oh! field geometry | Equal side margins center the five core columns in world and screen coordinates. Opposing side zones are mirrored in Duel View. Rendering, selection, projected world hits and interaction arrows use the corresponding layout coordinates. Pokémon's layout is unchanged. |
| 3 | Shared full-size reveal | The engines emit public-play events for normal/flip/special summons, activations and trigger activations; Pokémon bench plays, evolution, Energy attachment and Trainer plays, including effects that deliberately put a Pokémon on the Bench. Faces are supplied by the server. Cards are temporarily concealed in their normal rendered location while a large preview appears, then return. |
| 4 | Yu-Gi-Oh! interaction lines | Red directional lines use declared attacker/defender relationships. White lines use explicit chain-link targets, including targets selected by costs. Both endpoints must be field cards at event creation. No line is fabricated for a direct attack, untargeted choice, or unrelated cards in a shared event. |
| 5 | Shared deck builder | `/deckbuilder` and the table lobby's Deck builder button open the editor. Select the game; browse/search its server card pool; hover for art/details; add/remove copies; page/scroll the catalog and deck; name, save, load and edit local decks. Only a page of catalog widgets exists at once. |
| 6 | YDK support | The existing parser is reused and extended with a section-aware document reader. The editor writes standard `#main`, `#extra`, `!side` sections and numeric passcodes. Extra Deck cards are routed automatically; Side is separately selectable and retained for editing while remaining unused by gameplay. |
| 7 | Inventory icons | Both generated-item models reference existing `gui/back_ygo` / `gui/back_ptcg` textures. GUI-only horizontal scaling preserves their actual PNG aspect ratios. No new image assets or physical table-card texture changes. |
| 8 | Table Bot stool behavior | Bot spawning checks its intended seat block and the four directly adjacent blocks, with a 1.25-block horizontal distance bound. Only compatible, unoccupied stools with headroom qualify. The existing seat entity mounts the bot; its standing renderer is placed at the stool surface and oriented toward the table. Without a qualifying stool, the original floor position remains. |
| 9 | Mahjong Tile entry | Table use requires a Mahjong Tile in either hand. A Fabric block-use path intercepts tile clicks before the tile's placement handler, including offhand/sneaking use. Server JOIN and START paths also verify the held tile and proximity. No tile is consumed. Existing card-table interaction routing runs first. |

### Presentation and multiplayer choices

- `PresentationPayload` is **server-to-client only**, separate from historical board snapshots.
  Events drain once after an accepted engine action and are sent to the players and already
  registered, in-range spectators. A new spectator receives the current board, not old events.
- Drawing, deck-to-hand searching, milling, generic movement, hidden sets, Pokémon's private
  setup and snapshot serialization do not generate public-play reveals.
- Per-engine object tokens identify actual card instances across successive view IDs. Arrow
  endpoints follow current card positions. If immediate resolution removes an endpoint, the
  event retains its actual last field zone/slot rather than inventing a replacement target.
- Reveals last approximately one second and shrink briefly before disappearing. Lines expire
  after approximately 1.4 seconds. Client cleanup covers disconnect, closed tables and pruned
  watched tables/dimensions. Cosmetic state never modifies the server game.
- The display queue is bounded (128 events; at most about eight seconds of queued reveal delay)
  to avoid an unbounded cosmetic backlog. Extreme bursts can drop excess visual effects while
  all gameplay actions still resolve. This is a presentation limit, not a deck or gameplay rule.
- The repository's existing player-disconnect behavior still ends/forfeits a match; this change
  does not add saved-match resumption. Returning spectators do not replay previous effects.

### Deck persistence and validation choices

- The server pages its current `CardPools` snapshot; the client does not maintain another card
  registry or trust local files to define playable cards.
- Saving writes only the client's `config/tablecards/decks/`. Yu-Gi-Oh! uses `.ydk`; Pokémon
  uses the existing TCG Live-style `.txt` format. Saving incomplete drafts is allowed.
- `Use` reparses and validates with the server's existing game rules, then supplies the captured
  deck to the requesting player's unselected seat in a matching deck-selection lobby. It checks
  session membership, game type, dimension and table proximity. No arbitrary server file path
  is supplied or written, and no extra gameplay deck restrictions were introduced.
- Existing server-deck menus continue to work. A client-local deck can be used without copying
  it onto the multiplayer server or granting the player operator access.
- Unknown IDs and malformed lines produce messages. A partial import requires another explicit
  Load click, and the recovered deck gets a different filename so the source is preserved.
- File writes use a temporary file and atomic replacement when supported. Names are constrained,
  path traversal and symbolic-link destinations are rejected, and input sizes/counts are bounded.
- Requests carry IDs so stale search/load replies do not replace newer editor results. The
  catalog uses twelve results per page and debounced search. Long preview text scrolls; hover
  the status line to read long errors.

## 3. Files/classes changed

Under `src/main/java/com/tablecards/`:

- `CardPools.java`: correct local-deck documentation for existing Extra Deck support.
- `Sessions.java`, `TableSession.java`: live-event dispatch, tile interaction routing, validated
  local-deck selection using the existing session/engine start path.
- `TableWorld.java`: bounded stool selection and mounting.
- `TableCardsMod.java`: new payload registrations and deck-service receiver.
- `engine/BaseGame.java`, `engine/CardGame.java`: transient presentation event plumbing and
  stable presentation tokens.
- `engine/ViewJson.java`: reusable card snapshot serialization and instance tokens in views.
- `engine/DeckLists.java`: section-aware YDK editing plus defensive Pokémon count parsing.
- `engine/ygo/YgoGame.java`, `engine/ptcg/PtcgGame.java`: semantic public-play hooks; YGO
  target/attack hooks; reusable definition-only card previews.
- `client/GameView.java`, `client/TableGameScreen.java`: view toggle, board arrows, reveal
  suppression/overlay, lobby editor entry.
- `client/TableLayout.java`, `client/TableRenderer.java`: centered/mirrored geometry, field
  anchors, world lines and temporary reveal suppression.
- `client/TableViews.java`, `client/ViewModel.java`: instance tokens and transient cleanup.
- `client/TableCardsClient.java`: client packet handlers, `/deckbuilder`, shared overlay and
  reopening the current game after editing.

Under `src/main/kotlin/doublemoon/mahjongcraft/`:

- `block/MahjongTable.kt`: public held-tile entry helper shared with the normal block-use path.
- `network/mahjong_table/MahjongTablePayloadListener.kt`: server JOIN/START tile checks.

Resources/documentation:

- `src/main/resources/assets/tablecards/models/item/yugioh_deck.json`
- `src/main/resources/assets/tablecards/models/item/pokemon_deck.json`
- `CARD_GAMES.md`

## 4. New files

- `src/main/java/com/tablecards/DeckBuilderService.java`
- `src/main/java/com/tablecards/client/CardUi.java`
- `src/main/java/com/tablecards/client/DeckBuilderModel.java`
- `src/main/java/com/tablecards/client/DeckBuilderScreen.java`
- `src/main/java/com/tablecards/client/DeckFiles.java`
- `src/main/java/com/tablecards/client/Presentation.java`
- `src/main/java/com/tablecards/net/DeckBuilderPayload.java`
- `src/main/java/com/tablecards/net/PresentationPayload.java`
- `tools/check_changes.py`
- `tools/tests/SourceSyntaxCheck.java`
- `tools/tests/com/tablecards/client/ChangeChecks.java`
- `tools/tests/com/tablecards/client/ViewModeChecks.java`
- `tools/tests/com/tablecards/engine/ygo/PresentationChecks.java`
- `tools/tests/com/tablecards/engine/ptcg/PresentationChecks.java`
- This report. **No new texture assets or external dependencies.**

## 5. Verification actually performed

Command: `python tools/check_changes.py`.

| Check | Result |
|---|---|
| Minecraft-independent engine/UI/editor Java compilation | PASS using the installed Java 17 compiler module; no source/target/dependency downgrade was made |
| Syntax parsing of all 63 main Java files | PASS; syntax only, not Minecraft API type-checking |
| Persistence, malformed inputs, path safeguards, field geometry, event expiry and reconnect snapshots | PASS, 99 assertions |
| View-mode button ordering and actual click handlers | PASS for both seats at GUI widths 320, 480 and 854 |
| Toggle preserves the same view/sequence and sends no gameplay choice | PASS |
| Five opposing monster/spell columns in world layout | PASS for both seats and all four table orientations |
| Duel-screen interaction rectangles | PASS for both seats at three GUI widths |
| YGO fixture games and direct semantic checks | PASS: public summons, hidden sets, attack/target events, correct instance/face, silent draws/snapshots |
| Pokémon fixture games | PASS: bench/Energy events, private setup, silent snapshots |
| Resource JSON | PASS, 91 files |
| Both inventory texture paths, PNG headers and GUI aspect ratios | PASS |
| New payload registration/receiver paths | PASS static inspection/checks |
| Diff whitespace checks | PASS |
| Dependency/build configuration unchanged | PASS, git comparison |

These use small test fixtures, not a downloaded production card database. They do not prove all
existing card scripts or all interactions in Minecraft. Initial checks exposed a preview-refactor
compile error and later review exposed an optional-anchor parsing error; both were fixed before
the final successful run.

## 6. Unverified items and known limitations

- **Full mod compilation and Minecraft runtime testing: NOT VERIFIED due to environment
  limitations.** Gradle distribution downloads failed; the environment provides Java 17, not
  the project's JDK 21. Its compiler module enabled only the narrower checks above.
- Minecraft/Fabric method mappings, Kotlin-to-Java integration, item atlas rendering, actual
  GUI screenshots, stool height/orientation and live two-client/spectator behavior require the
  local acceptance run below. No successful in-game test is claimed.
- Reveals and their ordering come from the same server events; actual network-latency behavior
  has not been measured. UI animations do not pause gameplay or add a server delay.
- Art still depends on the existing per-client card image downloads; missing/unavailable art
  uses the card's name, stats and text in the existing frame.
- The bot uses the existing standing model on the stool; this does not add a new seated pose.
  Stool selection occurs when the bot is spawned; changing furniture mid-match is not a new
  dynamic seating feature.
- The existing CI file includes `shandalar-mod/` and `tablecards-mod/` jobs whose directories
  are absent in this checkout. Those pre-existing jobs were not rewritten. Build the root
  project directly; those jobs should be maintained separately.
- The checkout contains a LibGui sources JAR, not the compiled dependency expected by the
  existing build. Supply the compiled LibGui JAR as described below; do not change versions.
- Existing local filenames must meet the editor's conservative name policy (letters/digits,
  spaces, `_`, `-`, up to 64 characters). Rename unusual external filenames before loading.

## 7. Exact local build instructions (JDK 21)

Use the supplied full-source ZIP, or apply the patch to the base checkout. Do not install this
source ZIP as a Minecraft mod.

### Applying the patch to an existing clone

From a clean checkout of the base commit, with the patch one directory above it:

```text
git switch -c mahjongcraft-presentation-deckbuilder 5cdc10e78e47febb5de405e457d883112f7756d7
git apply --check ../MahjongCraft_changes.patch
git apply ../MahjongCraft_changes.patch
```

If your clone already contains different edits, preserve them on their own branch first and
review/merge the patch; do not reset them to apply it.

### Windows PowerShell

1. Install a JDK **21** and ensure both `JAVA_HOME` and PATH select that installation.
2. Open PowerShell in the project root and verify:

```powershell
java -version
javac -version
```

Both should report 21. Supply the compiled dependency used by the existing GitHub workflow
(the following uses GitHub CLI, `gh`):

```powershell
$mahjongLibGuiDir = Join-Path $env:TEMP "mahjongcraft-libgui-11-1-0"
New-Item -ItemType Directory -Force $mahjongLibGuiDir | Out-Null
gh release download 11.1.0 --repo CottonMC/LibGui --pattern "*.jar" --dir $mahjongLibGuiDir --clobber
$mahjongLibGuiJars = @(Get-ChildItem $mahjongLibGuiDir -Filter "*.jar" | Where-Object { $_.Name -notmatch 'sources|javadoc|dev' })
if ($mahjongLibGuiJars.Count -ne 1) { throw "Inspect the release files and select the compiled LibGui runtime JAR." }
Copy-Item -LiteralPath $mahjongLibGuiJars[0].FullName -Destination ".\libs\LibGui-11.1.0+1.21.jar"
.\gradlew.bat --version
.\gradlew.bat clean build --stacktrace
```

The Gradle JVM must also report Java 21. Internet access must permit the repositories already
listed in the project's Gradle files. The mod output belongs in `build/libs/`; use the normal
remapped mod JAR, not a `-sources` JAR.

Run the offline checks with Python 3 installed:

```powershell
py tools/check_changes.py
```

### Linux/macOS

With JDK 21 selected and `libs/LibGui-11.1.0+1.21.jar` supplied as above:

```bash
java -version
javac -version
chmod +x gradlew
./gradlew --version
./gradlew clean build --stacktrace
python3 tools/check_changes.py
```

No changes to dependency versions are needed for these commands. A failure at dependency
resolution is distinct from a Java/Kotlin source compilation error; retain the full build log.

## 8. Minecraft manual acceptance steps

Use a disposable test world and the same newly built mod on the server and both clients, with
Fabric API, Fabric Language Kotlin and Cloth Config matching the existing project. Mod Menu is
optional. Start with card artwork enabled. Allow the automatic imports to finish, or as an
operator run `/tablecards import all`, then `/tablecards decks`.

### Inventory, Mahjong and stools

1. Give yourself `tablecards:yugioh_deck`, `tablecards:pokemon_deck`,
   `mahjongcraft:mahjong_table`, `mahjongcraft:mahjong_tile` and `mahjongcraft:mahjong_stool`.
2. Check the two deck items in the creative inventory, hotbar and a container. Expect distinct
   Yu-Gi-Oh!/Pokémon backs, portrait proportions and no magenta missing texture. Start a card
   match and confirm the existing physical/table cards still render normally.
3. Place a Game Table. Right-click empty-handed and with an unrelated item: Mahjong must not
   open. Hold a tile in the main hand and repeat: the lobby should open. Repeat with only an
   offhand tile, then sneak-right-click with the tile. Check the tile count before/after.
4. Join/ready/start Mahjong while holding the tile. Play a hand to check normal gameplay.
   Drop/swap away the tile before JOIN/START and verify those server actions are rejected.
   Check that right-clicking a card table with its deck still starts/joins that card game.
5. Start a bot card match with no stool; note the normal far-side bot location. End it, place
   a stool at that seat and start again. Check height, feet, facing and normal bot actions.
6. Repeat with a stool immediately beside the expected seat, then only a stool several blocks
   away. The first may qualify; the distant stool must not move the bot. Test an occupied stool
   and a blocked space above it; the bot should fall back to its floor position.

### Yu-Gi-Oh! geometry and view mode

7. Start an existing built-in/server YGO deck against the bot. Confirm existing deck loading.
   Repeat with two human players, and with the host approaching from different table sides.
8. In Default View, inspect Monster/Spell-Trap rows, Field Spell, Deck, Extra, Graveyard and
   Banished. Fill five monsters/spells where the supported deck permits. Hover/click edge cards,
   use pile browsing, attack/defense positions, summons, activations and targeting.
9. Toggle **View Mode** repeatedly mid-turn and during a chain. Expect one continuous game,
   unchanged LP/hand/turn, and corresponding opposing central columns in Duel View. Repeat all
   selections and pile browsing there. Try GUI scales producing small and large windows.
10. Use Card art on/off, Hide/reopen, Cancel/back and Concede confirmation in both presentations.
    Pokémon must not display a View Mode button.

### Live reveal and interaction effects

11. With two players and a third nearby spectator, perform a normal summon, special summon,
    flip summon, Spell activation and Trap response. Each public play should show the correct
    large face, clear cleanly and leave one actual card in its resulting location.
12. Draw normally, search a card into hand, mill, set a hidden monster/Spell, and hide/reopen
    the UI. These must not produce a public face reveal merely because a card moved.
13. Declare a monster-to-monster attack from each side: expect a red arrow toward the chosen
    defender in both views. Declare a direct attack: no fictional target-card line.
14. Activate **Mystical Space Typhoon** targeting a field Spell/Trap, or another supported
    single-target effect. Expect a white arrow from the actual activating field card to its
    actual target. Confirm it expires, including when the target leaves the field immediately.
15. Move the spectator out of range and return after several plays; disconnect/reconnect the
    spectator. Expect the current board without historical reveals/arrows. Keep a spectator
    watching bot turns to verify those also produce the public events.

### Both deck builders and persistence

16. In the table's **Choose your deck** lobby, open **Deck builder**. Also test `/deckbuilder`.
    Search by name/type, turn catalog pages, scroll shorter windows, hover cards and scroll
    long rules text. Ensure the UI stays responsive with the server's imported library.
17. Build a legal YGO Main Deck and add supported Extra Deck cards. Switch Add to Side and add
    a side card. Check counts, remove one copy, enter `YGO acceptance`, then Save.
18. Inspect `config/tablecards/decks/YGO acceptance.ydk`: expect `#main`, `#extra`, `!side`
    and numeric passcodes. Use Files > / Load, edit, save again, and reopen it. Press Use while
    your matching lobby seat is unselected; finish opponent deck selection and play the deck.
19. Load an existing external YDK. Then test a copy with unknown IDs, nonnumeric lines and
    missing cards. Expect clear problems without a client crash or silent overwrite. Confirm
    partial recovery only after the second Load click, under a recovered name. Illegal drafts
    must be rejected by Use. On a dedicated server, confirm another player's seat is unchanged.
20. Switch to Pokémon after saving (unsaved changes require repeating the discard action).
    Confirm the catalog contains only Pokémon TCG cards. Build a legal 60-card deck with a
    Basic Pokémon and existing copy rules; save as `Pokemon acceptance`.
21. Inspect the `.txt` file's quantity/name/set/number lines. Load, remove/add cards, save and
    Use it in a Pokémon lobby. Also test a pre-existing Pokémon export and a malformed/huge
    count. It must report problems rather than hang or crash.
22. In Pokémon, test a normal Bench play, evolution, Energy attachment and Trainer play with
    the other player/spectator watching. Confirm public reveals, normal play afterward, and
    no reveal of private opening setup, ordinary draws or searches into hand.
23. Finally, play through one match of each card game and Mahjong, check server/client logs,
    and record any compiler, renderer or networking errors before using the changes in a
    permanent world.
