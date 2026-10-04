#!/usr/bin/env python3
"""Offline checks; does not replace the Java 21/Fabric build or Minecraft acceptance tests."""
from pathlib import Path
import json
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def run(command):
    subprocess.run(command, cwd=ROOT, check=True)

def main():
    java = shutil.which("java")
    if not java:
        raise SystemExit("Install a JDK (Java 21 for the project) and put java on PATH")
    client = ROOT / "src/main/java/com/tablecards/client"
    sources = sorted((ROOT / "src/main/java/com/tablecards/engine").glob("**/*.java"))
    sources += [client / (name + ".java") for name in (
        "Canvas", "ViewModel", "TableLayout", "TableViews", "CardUi", "Presentation", "GameView", "DeckFiles", "DeckBuilderModel")]
    sources += sorted((ROOT / "tools/tests").glob("**/*.java"))
    with tempfile.TemporaryDirectory(prefix="mahjong-checks-") as tmp:
        argsfile = Path(tmp) / "sources.txt"
        argsfile.write_text("\n".join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding="utf-8")
        run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-d", tmp, "@" + str(argsfile)])
        for test in ("SourceSyntaxCheck", "com.tablecards.client.ChangeChecks", "com.tablecards.client.ViewModeChecks",
                     "com.tablecards.engine.ygo.PresentationChecks", "com.tablecards.engine.ptcg.PresentationChecks"):
            run([java, "-cp", tmp, test])
    assets = ROOT / "src/main/resources/assets/tablecards"
    for item in ("yugioh", "pokemon"):
        model = json.loads((assets / f"models/item/{item}_deck.json").read_text())
        domain, path = model["textures"]["layer0"].split(":")
        texture = ROOT / f"src/main/resources/assets/{domain}/textures/{path}.png"
        data = texture.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n"
        w, h = struct.unpack(">II", data[16:24])
        assert abs(model["display"]["gui"]["scale"][0] - w/h) < 0.00001
    count = 0
    for path in (ROOT / "src/main/resources").rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8")); count += 1
    mod = (ROOT / "src/main/java/com/tablecards/TableCardsMod.java").read_text()
    client_code = (client / "TableCardsClient.java").read_text()
    for payload in ("PresentationPayload", "DeckBuilderPayload"):
        assert f"playS2C().register(com.tablecards.net.{payload}.ID" in mod
        assert f"registerGlobalReceiver(com.tablecards.net.{payload}.ID" in client_code
    assert "playC2S().register(com.tablecards.net.DeckBuilderPayload.ID" in mod
    assert "registerGlobalReceiver(com.tablecards.net.PresentationPayload.ID" not in mod
    run(["git", "diff", "--check"])
    print(f"PASS: {count} resource JSON files, both inventory PNG references/aspects, packet registration paths, diff whitespace")
    print("NOT VERIFIED: full Java 21/Fabric compilation, Kotlin/Java mapping compatibility, Minecraft rendering, live multiplayer")

if __name__ == "__main__":
    main()
