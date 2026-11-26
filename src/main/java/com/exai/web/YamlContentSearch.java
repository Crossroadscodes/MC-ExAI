package com.exai.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class YamlContentSearch {
    private static final long MAX_FILE_BYTES = 2_000_000L;
    private static final long MAX_CACHE_BYTES = 32_000_000L;
    private static final int MAX_CACHE_FILES = 128;
    private static final Map<String, Snapshot> CACHE = new LinkedHashMap<String, Snapshot>(16, 0.75f, true);
    private static long cachedBytes;

    private YamlContentSearch() {}

    static List<String> keywords(JsonObject args) {
        Set<String> terms = new LinkedHashSet<>();
        if (args.has("keyword") && !args.get("keyword").isJsonNull()) addTerm(terms, args.get("keyword"));
        if (args.has("keywords")) {
            if (!args.get("keywords").isJsonArray()) throw new IllegalArgumentException("keywords must be an array");
            for (JsonElement term : args.getAsJsonArray("keywords")) addTerm(terms, term);
        }
        if (terms.isEmpty() || terms.size() > 10) throw new IllegalArgumentException("Supply 1-10 keywords");
        return new ArrayList<>(terms);
    }

    private static void addTerm(Set<String> terms, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid keyword");
        String term = value.getAsString().trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty() || term.length() > 200) throw new IllegalArgumentException("Invalid keyword length");
        terms.add(term);
    }

    static JsonObject search(File root, File scope, JsonObject args, boolean keysOnly) throws IOException {
        List<String> terms = keywords(args);
        String mode = string(args, "match_mode", "any");
        if (!"any".equals(mode) && !"all".equals(mode)) throw new IllegalArgumentException("Invalid match_mode");
        boolean all = "all".equals(mode);
        boolean comments = !keysOnly && bool(args, "include_comments");
        int fileOffset = integer(args, "file_offset", 0, 0, Integer.MAX_VALUE);
        int fileLimit = integer(args, "file_limit", 100, 1, 100);
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = integer(args, "limit", 30, 1, 100);
        int perFile = integer(args, "per_file_limit", 5, 1, 100);
        String section = string(args, "path", "");
        Path base = root.getCanonicalFile().toPath();
        Path target = scope.getCanonicalFile().toPath();
        if (!target.startsWith(base)) throw new SecurityException("Search outside plugins root");
        if (!Files.exists(target)) throw new FileNotFoundException("Search scope not found");
        Scan scan = new Scan(base);
        scan.collect(target);
        Collections.sort(scan.files, Comparator.comparing(path -> relative(base, path)));
        int end = (int) Math.min(scan.files.size(), (long) fileOffset + fileLimit);
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        int cacheHits = 0;
        for (int index = Math.min(fileOffset, end); index < end; index++) {
            Path file = scan.files.get(index);
            String relative = relative(base, file);
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (attributes.size() > MAX_FILE_BYTES) { scan.skip(file, "too_large"); continue; }
                if (!file.toFile().getCanonicalFile().toPath().startsWith(base)) { scan.skip(file, "outside_root"); continue; }
                if (!Files.isReadable(file)) { scan.skip(file, "access_denied"); continue; }
                Loaded loaded = load(file, attributes);
                scanned++;
                if (loaded.cached) cacheHits++;
                List<Hit> fileHits = match(relative, loaded.snapshot, terms, all, section, keysOnly, comments);
                fileHits.sort(Hit.ORDER);
                for (int rank = 0; rank < fileHits.size(); rank++) {
                    Hit hit = fileHits.get(rank);
                    hit.round = rank / perFile;
                    hits.add(hit);
                }
            } catch (InvalidConfigurationException error) { scan.skip(file, "invalid_yaml"); }
            catch (SkippedFileException error) { scan.skip(file, error.reason); }
            catch (java.nio.file.AccessDeniedException error) { scan.skip(file, "access_denied"); }
            catch (IOException error) { scan.skip(file, "io_error"); }
            catch (SecurityException error) { scan.skip(file, "access_denied"); }
        }
        hits.sort(Comparator.comparingInt((Hit hit) -> hit.round).thenComparing(Hit.ORDER));
        int resultEnd = (int) Math.min(hits.size(), (long) offset + limit);
        JsonArray results = new JsonArray();
        for (int index = Math.min(offset, resultEnd); index < resultEnd; index++) results.add(hits.get(index).json());
        boolean scanComplete = end == scan.files.size();
        boolean resultsComplete = resultEnd == hits.size();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray query = new JsonArray(); terms.forEach(query::add);
        out.add("keywords", query);
        if (terms.size() == 1) out.addProperty("keyword", terms.get(0));
        out.addProperty("scope", relative(base, target));
        out.addProperty("match_mode", mode);
        out.addProperty("path", section);
        out.addProperty("comments_searched", comments && section.isEmpty());
        out.add("results", results);
        out.addProperty("file_offset", fileOffset);
        out.addProperty("file_limit", fileLimit);
        out.addProperty("offset", offset);
        out.addProperty("limit", limit);
        out.addProperty("total_files", scan.files.size());
        out.addProperty("scanned_files", scanned);
        out.addProperty("processed_files", Math.max(0, end - fileOffset));
        out.addProperty("cache_hits", cacheHits);
        out.addProperty("cache_misses", scanned - cacheHits);
        out.addProperty("matched_files", hits.stream().map(hit -> hit.file).distinct().count());
        out.addProperty("total_matches", hits.size());
        out.addProperty("scan_complete", scanComplete);
        out.addProperty("results_complete", resultsComplete);
        out.addProperty("complete", scanComplete && resultsComplete && scan.skippedCount == 0);
        out.addProperty("truncated", !scanComplete || !resultsComplete);
        if (!scanComplete) out.addProperty("next_file_offset", end);
        if (!resultsComplete) out.addProperty("next_offset", resultEnd);
        out.addProperty("skipped_files", scan.skippedCount);
        out.add("skipped_counts", scan.skippedCounts);
        out.add("skipped", scan.skipped);
        out.addProperty("skipped_details_truncated", scan.skippedCount > scan.skipped.size());
        return out;
    }

    private static List<Hit> match(String file, Snapshot snapshot, List<String> terms, boolean all,
                                   String section, boolean keysOnly, boolean comments) {
        List<Hit> hits = new ArrayList<>();
        String lowerFile = file.toLowerCase(Locale.ROOT);
        int fileMatches = 0;
        for (String term : terms) if (lowerFile.contains(term)) fileMatches++;
        if (section.isEmpty() && accepts(fileMatches, terms.size(), all)) hits.add(new Hit(file, "", "file", file, "file", 10 + fileMatches * 10, false, 0));
        for (Entry entry : snapshot.entries) {
            if (!section.isEmpty() && !entry.path.equals(section) && !entry.path.startsWith(section + ".")) continue;
            if (!keysOnly && entry.section) continue;
            String lowerPath = entry.path.toLowerCase(Locale.ROOT);
            String leaf = lowerPath.substring(lowerPath.lastIndexOf('.') + 1);
            int matched = 0;
            int score = 0;
            boolean keyHit = false;
            boolean valueHit = false;
            for (String term : terms) {
                boolean key = lowerPath.contains(term);
                boolean value = !keysOnly && entry.text.contains(term);
                if (key || value || lowerFile.contains(term)) matched++;
                if (key) { keyHit = true; score += leaf.equals(term) ? 120 : lowerPath.equals(term) ? 100 : leaf.contains(term) ? 80 : 50; }
                if (value) { valueHit = true; score += 40; }
            }
            if ((keyHit || valueHit) && accepts(matched, terms.size(), all)) {
                Hit hit = new Hit(file, entry.path, entry.type, entry.preview, keyHit ? "path" : "value", score + matched * 20, entry.masked, 0);
                hit.section = entry.section;
                hit.valueHit = valueHit;
                hits.add(hit);
            }
        }
        if (comments && section.isEmpty()) for (Comment comment : snapshot.comments) {
            String text = comment.text.toLowerCase(Locale.ROOT);
            int matched = 0;
            for (String term : terms) if (text.contains(term) || lowerFile.contains(term)) matched++;
            boolean contentHit = terms.stream().anyMatch(text::contains);
            if (contentHit && accepts(matched, terms.size(), all)) hits.add(new Hit(file, "", "comment", preview(comment.text), "comment", 30 + matched * 20, false, comment.line));
        }
        return hits;
    }

    private static boolean accepts(int count, int total, boolean all) { return all ? count == total : count > 0; }

    private static Loaded load(Path file, BasicFileAttributes attributes) throws IOException, InvalidConfigurationException {
        String key = file.toFile().getCanonicalPath();
        long size = attributes.size();
        long modified = attributes.lastModifiedTime().toMillis();
        synchronized (YamlContentSearch.class) {
            Snapshot cached = CACHE.get(key);
            if (cached != null && cached.size == size && cached.modified == modified) return new Loaded(cached, true);
            if (cached != null) { CACHE.remove(key); cachedBytes -= cached.size; }
        }
        byte[] bytes;
        try (java.io.InputStream input = Files.newInputStream(file); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_FILE_BYTES) throw new SkippedFileException("too_large");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(raw);
        List<Entry> entries = new ArrayList<>();
        List<String> secrets = new ArrayList<>();
        flatten(yaml, "", entries, secrets);
        Snapshot snapshot = new Snapshot(modified, size, entries, comments(raw, secrets));
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
        if (after.size() != size || after.lastModifiedTime().toMillis() != modified) throw new SkippedFileException("changed_during_read");
        synchronized (YamlContentSearch.class) {
            Snapshot previous = CACHE.put(key, snapshot);
            if (previous != null) cachedBytes -= previous.size;
            cachedBytes += size;
            while (CACHE.size() > MAX_CACHE_FILES || cachedBytes > MAX_CACHE_BYTES) {
                String eldest = CACHE.keySet().iterator().next();
                cachedBytes -= CACHE.remove(eldest).size;
            }
        }
        return new Loaded(snapshot, false);
    }

    private static void flatten(ConfigurationSection section, String prefix, List<Entry> entries, List<String> secrets) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            boolean nested = value instanceof ConfigurationSection;
            boolean masked = PluginConfigTool.isSecretPath(path);
            JsonElement safe = nested ? com.google.gson.JsonNull.INSTANCE : PluginConfigTool.valueToJson(value, false, path);
            String content = nested ? "" : safe.isJsonPrimitive() ? safe.getAsString() : safe.toString();
            entries.add(new Entry(path, nested, masked, nested ? "section" : value == null ? "null" : value.getClass().getSimpleName(),
                    nested || masked ? "" : text(safe).toLowerCase(Locale.ROOT), masked ? "******" : preview(content)));
            if (!nested) collectSecrets(value, path, secrets);
            if (nested) flatten((ConfigurationSection) value, path, entries, secrets);
        }
    }

    private static String text(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return value.getAsString();
        StringBuilder output = new StringBuilder();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) output.append(text(child)).append('\n');
        else for (Map.Entry<String, JsonElement> child : value.getAsJsonObject().entrySet()) output.append(child.getKey()).append(' ').append(text(child.getValue())).append('\n');
        return output.toString();
    }

    private static void collectSecrets(Object value, String path, List<String> secrets) {
        if (value instanceof ConfigurationSection) return;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> child : ((Map<?, ?>) value).entrySet()) collectSecrets(child.getValue(), path + "." + child.getKey(), secrets);
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) collectSecrets(child, path, secrets);
        } else if (value != null && PluginConfigTool.isSecretPath(path)) {
            for (String line : String.valueOf(value).split("\\R")) if (!line.trim().isEmpty()) secrets.add(line.trim());
        }
    }

    private static List<Comment> comments(String raw, List<String> secrets) {
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        List<Comment> comments = new ArrayList<>();
        int blockIndent = -1;
        boolean single = false;
        boolean doubled = false;
        String[] lines = raw.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            if (indent == line.length()) continue;
            if (blockIndent >= 0 && indent > blockIndent) continue;
            blockIndent = -1;
            int comment = -1;
            for (int position = indent; position < line.length(); position++) {
                char current = line.charAt(position);
                if (doubled && current == '\\') { position++; continue; }
                if (single) {
                    if (current == '\'') {
                        if (position + 1 < line.length() && line.charAt(position + 1) == '\'') { position++; continue; }
                        single = false;
                    }
                    continue;
                }
                if (doubled) { if (current == '"') doubled = false; continue; }
                if (quoteStart(line, position)) {
                    if (current == '\'') { single = true; continue; }
                    if (current == '"') { doubled = true; continue; }
                }
                if (!single && !doubled && current == '#' && (position == 0 || Character.isWhitespace(line.charAt(position - 1)))) { comment = position; break; }
            }
            String code = comment < 0 ? line : line.substring(0, comment);
            if (code.trim().matches(".*[:\\-]\\s*[|>][0-9+\\-]*\\s*")) blockIndent = indent;
            if (comment < 0) continue;
            String content = line.substring(comment + 1).trim();
            for (String secret : secrets) content = content.replace(secret, "******");
            content = PluginConfigTool.redactSecretLine(content);
            comments.add(new Comment(index + 1, content));
        }
        return comments;
    }

    private static boolean quoteStart(String line, int position) {
        if (line.charAt(position) != '\'' && line.charAt(position) != '"') return false;
        int previous = position - 1;
        while (previous >= 0 && Character.isWhitespace(line.charAt(previous))) previous--;
        return previous < 0 || ":-[{,?".indexOf(line.charAt(previous)) >= 0;
    }

    private static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        JsonElement value = args.get(key);
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid integer");
            int result = value.getAsBigDecimal().intValueExact();
            if (result < min || result > max) throw new IllegalArgumentException("Integer out of range");
            return result;
        } catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid integer", error); }
    }

    private static boolean bool(JsonObject args, String key) {
        if (!args.has(key)) return false;
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid boolean");
        return value.getAsBoolean();
    }

    private static String string(JsonObject args, String key, String fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid string");
        return value.getAsString().trim();
    }

    private static String preview(String value) { return value.length() > 80 ? value.substring(0, 77).replace('\n', ' ') + "..." : value.replace('\n', ' '); }
    private static String relative(Path root, Path file) { return root.relativize(file.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/'); }
    static synchronized void clearCache() { CACHE.clear(); cachedBytes = 0; }

    static synchronized void invalidate(File file) {
        try {
            Snapshot removed = CACHE.remove(file.getCanonicalPath());
            if (removed != null) cachedBytes -= removed.size;
        } catch (IOException error) { clearCache(); }
    }

    private static final class Scan extends SimpleFileVisitor<Path> {
        final Path root;
        final List<Path> files = new ArrayList<>();
        final JsonArray skipped = new JsonArray();
        final JsonObject skippedCounts = new JsonObject();
        final Set<Path> visitedDirectories = new LinkedHashSet<>();
        int skippedCount;

        Scan(Path root) { this.root = root; }
        void collect(Path target) throws IOException { Files.walkFileTree(target, this); }
        void skip(Path file, String reason) {
            skippedCount++;
            skippedCounts.addProperty(reason, skippedCounts.has(reason) ? skippedCounts.get(reason).getAsInt() + 1 : 1);
            if (skipped.size() >= 100) return;
            JsonObject detail = new JsonObject(); detail.addProperty("file", relative(root, file)); detail.addProperty("reason", reason); skipped.add(detail);
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (attributes.isSymbolicLink()) skip(file, "symbolic_link");
            else if (attributes.isRegularFile() && (name.endsWith(".yml") || name.endsWith(".yaml"))) files.add(file);
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            Path canonical = directory.toFile().getCanonicalFile().toPath();
            if (!canonical.startsWith(root)) { skip(directory, "outside_root"); return FileVisitResult.SKIP_SUBTREE; }
            if (!visitedDirectories.add(canonical)) { skip(directory, "already_visited"); return FileVisitResult.SKIP_SUBTREE; }
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException error) { skip(file, error instanceof java.nio.file.AccessDeniedException ? "access_denied" : "io_error"); return FileVisitResult.CONTINUE; }
        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) { if (error != null) skip(directory, "io_error"); return FileVisitResult.CONTINUE; }
    }

    private static final class SkippedFileException extends IOException {
        final String reason;
        SkippedFileException(String reason) { super(reason); this.reason = reason; }
    }

    private static final class Loaded {
        final Snapshot snapshot;
        final boolean cached;
        Loaded(Snapshot snapshot, boolean cached) { this.snapshot = snapshot; this.cached = cached; }
    }
    private static final class Snapshot {
        final long modified;
        final long size;
        final List<Entry> entries;
        final List<Comment> comments;
        Snapshot(long modified, long size, List<Entry> entries, List<Comment> comments) { this.modified = modified; this.size = size; this.entries = entries; this.comments = comments; }
    }
    private static final class Entry {
        final String path;
        final boolean section;
        final boolean masked;
        final String type;
        final String text;
        final String preview;
        Entry(String path, boolean section, boolean masked, String type, String text, String preview) { this.path = path; this.section = section; this.masked = masked; this.type = type; this.text = text; this.preview = preview; }
    }
    private static final class Comment {
        final int line;
        final String text;
        Comment(int line, String text) { this.line = line; this.text = text; }
    }
    private static final class Hit {
        static final Comparator<Hit> ORDER = Comparator.comparingInt((Hit hit) -> hit.score).reversed().thenComparing(hit -> hit.file).thenComparing(hit -> hit.path).thenComparingInt(hit -> hit.line);
        final String file;
        final String path;
        final String type;
        final String preview;
        final String matched;
        final int score;
        final boolean masked;
        final int line;
        int round;
        boolean section;
        boolean valueHit;
        Hit(String file, String path, String type, String preview, String matched, int score, boolean masked, int line) { this.file = file; this.path = path; this.type = type; this.preview = preview; this.matched = matched; this.score = score; this.masked = masked; this.line = line; }
        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("file", file); out.addProperty("path", path); out.addProperty("type", type);
            out.addProperty("preview", preview); out.addProperty("matched_in", matched); out.addProperty("score", score);
            out.addProperty("section", section);
            JsonArray fields = new JsonArray(); fields.add(matched); if (valueHit && !"value".equals(matched)) fields.add("value"); out.add("matched_fields", fields);
            if (masked) out.addProperty("masked", true);
            if (line > 0) out.addProperty("line", line);
            return out;
        }
    }
}
