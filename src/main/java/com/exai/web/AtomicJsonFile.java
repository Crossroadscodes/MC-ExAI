package com.exai.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Predicate;

/** Crash-resistant JSON snapshots. A corrupt primary must never replace a valid backup. */
final class AtomicJsonFile {
    private AtomicJsonFile() {}

    static JsonObject read(Path file, Predicate<JsonObject> valid) throws IOException {
        if (!Files.exists(file) && !Files.exists(backup(file))) return null;
        IOException failure;
        try { return readOne(file, valid); } catch (IOException e) { failure = e; }
        try { return readOne(backup(file), valid); } catch (IOException e) {
            failure.addSuppressed(e);
            throw failure;
        }
    }

    private static JsonObject readOne(Path file, Predicate<JsonObject> valid) throws IOException {
        try {
            JsonObject data = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!valid.test(data)) throw new IOException("Invalid snapshot: " + file);
            return data;
        } catch (RuntimeException e) { throw new IOException("Invalid snapshot: " + file, e); }
    }

    static void write(Path file, JsonObject data, Predicate<JsonObject> valid) throws IOException {
        if (!valid.test(data)) throw new IOException("Invalid snapshot: " + file);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        readOne(temp, valid);
        if (Files.exists(file)) {
            boolean good;
            try { readOne(file, valid); good = true; } catch (IOException e) { good = false; }
            if (good) {
                Path backupTemp = file.resolveSibling(file.getFileName() + ".bak.tmp");
                Files.copy(file, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                move(backupTemp, backup(file));
            } else {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        move(temp, file);
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".bak"); }
}
