package com.exai.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class YamlContentSearchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root;

    @Before public void setup() throws Exception { root = temp.newFolder("plugins"); YamlContentSearch.clearCache(); }
    @After public void teardown() { YamlContentSearch.clearCache(); }

    private Path write(String file, String text) throws Exception {
        Path path = new File(root, file).toPath();
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private JsonObject query(String keyword) { JsonObject args = new JsonObject(); args.addProperty("keyword", keyword); return args; }
    private JsonObject search(JsonObject args) throws Exception { return YamlContentSearch.search(root, root, args, false); }
    private JsonArray results(JsonObject args) throws Exception { return search(args).getAsJsonArray("results"); }
    private String repeat(String value, int count) { StringBuilder text = new StringBuilder(); for (int index = 0; index < count; index++) text.append(value); return text.toString(); }

    @Test public void searchesFullScalarMultilineAndLongListBeyondPreview() throws Exception {
        write("Example/config.yml", "description: '" + repeat("x", 150) + "佣兵'\n"
                + "message: |\n  " + repeat("y", 100) + "\n  佣兵\n"
                + "items:\n" + repeat("  - filler\n", 20) + "  - 佣兵\n");
        JsonArray hits = results(query("佣兵"));
        assertEquals(3, hits.size());
        for (JsonElement hit : hits) {
            assertEquals("value", hit.getAsJsonObject().get("matched_in").getAsString());
            assertTrue(hit.getAsJsonObject().get("preview").getAsString().length() <= 80);
        }
    }

    @Test public void secretValuesAndNestedListSecretsRemainMaskedAndUnsearchable() throws Exception {
        write("Example/config.yml", "password: hidden-password\ncredentials:\n  nested: hidden-nested\n"
                + "items:\n  - label: visible\n    token: hidden-token\n");
        JsonArray hits = results(query("password"));
        assertEquals(1, hits.size());
        assertEquals("******", hits.get(0).getAsJsonObject().get("preview").getAsString());
        assertTrue(hits.get(0).getAsJsonObject().get("masked").getAsBoolean());
        JsonArray list = results(query("visible"));
        assertEquals(1, list.size());
        assertFalse(list.toString().contains("hidden-token"));
        assertEquals(0, results(query("hidden-password")).size());
        assertEquals(0, results(query("hidden-nested")).size());
        assertEquals(0, results(query("hidden-token")).size());
    }

    @Test public void supportsAnyAllAndMultipleTermsAcrossPathAndValue() throws Exception {
        write("Example/config.yml", "mercenary: '佣兵系统'\nquest: adventure\n");
        JsonObject args = new JsonObject(); JsonArray terms = new JsonArray();
        terms.add("佣兵"); terms.add("adventure"); args.add("keywords", terms);
        assertEquals(2, results(args).size());
        args.addProperty("match_mode", "all"); assertEquals(0, results(args).size());
        terms.remove(1); terms.add("mercenary");
        assertEquals(1, results(args).size());
        args.addProperty("keyword", "佣兵");
        assertEquals(2, search(args).getAsJsonArray("keywords").size());
    }

    @Test public void directoryAndSectionScopesExcludeUnrelatedFilesAndPaths() throws Exception {
        write("First/config.yml", "rewards:\n  label: target\nother: target\n");
        write("Second/config.yml", "label: target\n");
        JsonObject args = query("target"); args.addProperty("path", "rewards");
        JsonObject output = YamlContentSearch.search(root, new File(root, "First"), args, false);
        assertEquals("First", output.get("scope").getAsString());
        assertEquals(1, output.get("scanned_files").getAsInt());
        assertEquals(1, output.getAsJsonArray("results").size());
        assertEquals("rewards.label", output.getAsJsonArray("results").get(0).getAsJsonObject().get("path").getAsString());
    }

    @Test public void fileNameMatchIsOneResultRatherThanEveryKey() throws Exception {
        write("Example/target.yml", "alpha: one\nbeta: two\ngamma: three\n");
        JsonArray hits = results(query("target"));
        assertEquals(1, hits.size());
        assertEquals("file", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
    }

    @Test public void exactKeysRankAboveValuesAndFileNames() throws Exception {
        write("Example/a-target.yml", "unrelated: text\n");
        write("Example/b.yml", "unrelated: target\n");
        write("Example/c.yml", "target: text\n");
        JsonArray hits = results(query("target"));
        assertEquals(3, hits.size());
        assertEquals("path", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
        assertEquals("value", hits.get(1).getAsJsonObject().get("matched_in").getAsString());
        assertEquals("file", hits.get(2).getAsJsonObject().get("matched_in").getAsString());
    }

    @Test public void diversityAndResultPagesKeepEveryHitWithoutDuplicates() throws Exception {
        StringBuilder yaml = new StringBuilder();
        for (int index = 0; index < 120; index++) yaml.append("entry").append(index).append(": target\n");
        write("First/config.yml", yaml.toString()); write("Second/config.yml", "label: target\n");
        JsonObject args = query("target"); args.addProperty("limit", 6);
        JsonObject first = search(args);
        assertEquals(121, first.get("total_matches").getAsInt());
        assertTrue(first.getAsJsonArray("results").toString().contains("Second/config.yml"));
        Set<String> seen = new HashSet<>();
        JsonObject page = first;
        while (true) {
            for (JsonElement element : page.getAsJsonArray("results")) {
                JsonObject hit = element.getAsJsonObject();
                assertTrue(seen.add(hit.get("file").getAsString() + ":" + hit.get("path").getAsString()));
            }
            if (!page.has("next_offset")) break;
            args.addProperty("offset", page.get("next_offset").getAsInt()); page = search(args);
        }
        assertEquals(121, seen.size());
        assertTrue(page.get("complete").getAsBoolean());
    }

    @Test public void fileBatchesReachFilesAfterTheOldHundredFileLimit() throws Exception {
        for (int index = 0; index < 105; index++) write(String.format("Example/%03d.yml", index), "label: target\n");
        JsonObject args = query("target"); args.addProperty("limit", 100);
        JsonObject first = search(args);
        assertEquals(100, first.get("scanned_files").getAsInt());
        assertFalse(first.get("scan_complete").getAsBoolean());
        assertEquals(100, first.get("next_file_offset").getAsInt());
        args.addProperty("file_offset", first.get("next_file_offset").getAsInt());
        JsonObject last = search(args);
        assertEquals(5, last.getAsJsonArray("results").size());
        assertTrue(last.get("complete").getAsBoolean());
        assertFalse(last.has("next_file_offset"));
    }

    @Test public void reportsInvalidAndOversizedFilesWithoutHidingOtherMatches() throws Exception {
        write("Example/good.yml", "label: target\n"); write("Example/bad.yml", "broken: [\n");
        Path large = write("Example/large.yml", "");
        try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) { file.setLength(2_000_001L); }
        JsonObject output = search(query("target"));
        assertEquals(1, output.getAsJsonArray("results").size());
        assertEquals(3, output.get("processed_files").getAsInt());
        assertEquals(1, output.get("scanned_files").getAsInt());
        assertEquals(2, output.get("skipped_files").getAsInt());
        assertEquals(1, output.getAsJsonObject("skipped_counts").get("invalid_yaml").getAsInt());
        assertEquals(1, output.getAsJsonObject("skipped_counts").get("too_large").getAsInt());
        assertTrue(output.get("scan_complete").getAsBoolean());
        assertFalse(output.get("complete").getAsBoolean());
        assertFalse(output.get("truncated").getAsBoolean());
    }

    @Test public void cacheUsesPathModificationTimeAndSizeAndNotStaleValues() throws Exception {
        Path file = write("Example/config.yml", "label: alpha\n");
        FileTime original = Files.getLastModifiedTime(file);
        assertEquals(0, search(query("alpha")).get("cache_hits").getAsInt());
        assertEquals(1, search(query("alpha")).get("cache_hits").getAsInt());
        write("Example/config.yml", "label: bravo\n"); Files.setLastModifiedTime(file, FileTime.fromMillis(original.toMillis() + 5000));
        assertEquals(0, search(query("alpha")).getAsJsonArray("results").size());
        assertEquals(1, results(query("bravo")).size());
        FileTime current = Files.getLastModifiedTime(file);
        write("Example/config.yml", "label: larger-value\n"); Files.setLastModifiedTime(file, current);
        assertEquals(0, search(query("larger-value")).get("cache_hits").getAsInt());
        assertEquals(1, results(query("larger-value")).size());
    }

    @Test public void commentsAreOptionalCompleteAndDoNotConfuseQuotedHashesOrBlockScalars() throws Exception {
        write("Example/config.yml", "# " + repeat("x", 100) + "commentword\nlabel: plain # inlineword\n"
                + "quoted: '# not-a-comment'\nblock: |\n  # scalarword\npassword: hidden-value\n# note: hidden-value visibleword\n");
        assertEquals(0, results(query("commentword")).size());
        JsonObject args = query("commentword"); args.addProperty("include_comments", true);
        JsonArray hits = results(args);
        assertEquals(1, hits.size());
        assertEquals("comment", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
        assertEquals(1, hits.get(0).getAsJsonObject().get("line").getAsInt());
        args.addProperty("keyword", "inlineword"); assertEquals(1, results(args).size());
        args.addProperty("keyword", "scalarword");
        assertEquals(1, results(args).size());
        assertEquals("value", results(args).get(0).getAsJsonObject().get("matched_in").getAsString());
        args.addProperty("keyword", "visibleword"); assertFalse(results(args).toString().contains("hidden-value"));
        args.addProperty("keyword", "hidden-value"); assertEquals(0, results(args).size());
    }

    @Test public void explicitInvalidationHandlesSameSizeAndModificationTime() throws Exception {
        Path file = write("Example/config.yml", "label: alpha\n");
        FileTime original = Files.getLastModifiedTime(file);
        assertEquals(1, results(query("alpha")).size());
        write("Example/config.yml", "label: bravo\n"); Files.setLastModifiedTime(file, original);
        YamlContentSearch.invalidate(file.toFile());
        assertEquals(0, search(query("bravo")).get("cache_hits").getAsInt());
        assertEquals(0, results(query("alpha")).size());
    }

    @Test public void multilineQuotedStringsAndPlainApostrophesDoNotBecomeComments() throws Exception {
        write("Example/config.yml", "single: 'first\n  # singleword\n  last'\n"
                + "double: \"first\n  # doubleword\n  last\"\n"
                + "plain: player's item # actualword\n");
        JsonObject args = query("singleword"); args.addProperty("include_comments", true);
        JsonArray hits = results(args);
        assertEquals(1, hits.size());
        assertEquals("value", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
        args.addProperty("keyword", "doubleword"); hits = results(args);
        assertEquals(1, hits.size());
        assertEquals("value", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
        args.addProperty("keyword", "actualword"); hits = results(args);
        assertEquals(1, hits.size());
        assertEquals("comment", hits.get(0).getAsJsonObject().get("matched_in").getAsString());
    }

    @Test public void boundedCacheEvictsLeastRecentlyUsedFiles() throws Exception {
        for (int index = 0; index < 129; index++) write(String.format("Example/%03d.yml", index), "label: target\n");
        JsonObject args = query("target"); search(args);
        args.addProperty("file_offset", 100); search(args);
        JsonObject output = YamlContentSearch.search(root, new File(root, "Example/000.yml"), query("target"), false);
        assertEquals(0, output.get("cache_hits").getAsInt());
        assertEquals(1, output.getAsJsonArray("results").size());
    }

    @Test public void symbolicLinksAreReportedWithoutReadingOutsideRoot() throws Exception {
        Path outside = temp.newFile("outside.yml").toPath();
        Files.write(outside, "label: outside-secret\n".getBytes(StandardCharsets.UTF_8));
        Path link = new File(root, "link.yml").toPath();
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException error) { org.junit.Assume.assumeNoException(error); }
        JsonObject output = search(query("label"));
        assertEquals(0, output.getAsJsonArray("results").size());
        assertEquals(1, output.getAsJsonObject("skipped_counts").get("symbolic_link").getAsInt());
        assertFalse(output.toString().contains("outside-secret"));
    }

    @Test public void keysOnlyDoesNotMatchValueContents() throws Exception {
        write("Example/config.yml", "section:\n  option: target\n");
        assertEquals(0, YamlContentSearch.search(root, root, query("target"), true).getAsJsonArray("results").size());
        assertTrue(YamlContentSearch.search(root, root, query("section"), true).getAsJsonArray("results").size() > 0);
    }

    @Test public void invalidArgumentsAreRejectedAndHugeOffsetsAreSafe() throws Exception {
        for (String key : new String[]{"limit", "file_limit", "per_file_limit"}) {
            JsonObject args = query("target"); args.addProperty(key, 0);
            try { search(args); fail(); } catch (IllegalArgumentException expected) { }
        }
        JsonObject args = query("target"); args.addProperty("offset", Integer.MAX_VALUE); args.addProperty("file_offset", Integer.MAX_VALUE);
        assertEquals(0, results(args).size());
        assertTrue(search(args).get("complete").getAsBoolean());
        args = query("target"); args.addProperty("include_comments", "true");
        try { search(args); fail(); } catch (IllegalArgumentException expected) { }
        args = query("target"); args.addProperty("offset", 1.5);
        try { search(args); fail(); } catch (IllegalArgumentException expected) { }
        args = new JsonObject(); JsonArray terms = new JsonArray();
        for (int index = 0; index < 11; index++) terms.add("term" + index);
        args.add("keywords", terms);
        try { search(args); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void outsideRootIsRejected() throws Exception {
        try { YamlContentSearch.search(root, temp.getRoot(), query("target"), false); fail(); }
        catch (SecurityException expected) { }
    }
}
