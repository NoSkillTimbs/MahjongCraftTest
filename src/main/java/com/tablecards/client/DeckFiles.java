package com.tablecards.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Local-only files. Never accepts a server-provided path, and refuses symbolic links. */
public final class DeckFiles {
    private DeckFiles() {}
    private static Path directory(Path config) throws IOException {
        Path parent = config.resolve("tablecards");
        Path dir = parent.resolve("decks");
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(dir)) throw new IOException("Deck folder is a symbolic link");
        Files.createDirectories(dir);
        return dir;
    }
    private static Path path(Path config,String name,String game) throws IOException {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}")) throw new IOException("Use 1-64 letters, digits, spaces, - or _ for the name");
        Path file = directory(config).resolve(name + (game.equals("ygo")?".ydk":".txt"));
        if (Files.isSymbolicLink(file)) throw new IOException("Deck file is a symbolic link");
        return file;
    }
    public static void save(Path config,String name,String game,String text) throws IOException {
        Path target = path(config,name,game);
        Path temp = Files.createTempFile(target.getParent(),"deck-",".tmp");
        try {
            Files.writeString(temp,text,StandardCharsets.UTF_8);
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static String load(Path config,String name,String game) throws IOException {
        Path file = path(config,name,game);
        if (Files.size(file)>65536) throw new IOException("Deck file exceeds 64 KiB");
        return Files.readString(file,StandardCharsets.UTF_8);
    }
    public static List<String> names(Path config,String game) throws IOException {
        String suffix=game.equals("ygo")?".ydk":".txt";
        try (var files=Files.list(directory(config))) {
            return files.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p))
                    .map(p -> p.getFileName().toString()).filter(n -> n.endsWith(suffix))
                    .map(n -> n.substring(0,n.length()-suffix.length())).filter(n -> !n.equalsIgnoreCase("README"))
                    .sorted().toList();
        }
    }
}
