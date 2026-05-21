package com.exai.i18n;

import com.exai.ExAI;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Lang {
    private static YamlConfiguration messages;
    private static YamlConfiguration fallback;
    private static String currentLocale = "zh_CN";

    private Lang() {}

    public static void load(String locale) {
        if (locale == null || locale.trim().isEmpty()) {
            locale = "zh_CN";
        }
        currentLocale = locale;
        fallback = loadBundled("zh_CN");
        messages = loadOrCopy(locale);
        if (messages == null) {
            ExAI.getInstance().getLogger().warning(
                    "Language file '" + locale + ".yml' not found, falling back to zh_CN");
            messages = fallback;
        }
    }

    private static YamlConfiguration loadOrCopy(String locale) {
        File folder = new File(ExAI.getInstance().getDataFolder(), "lang");
        if (!folder.exists() && !folder.mkdirs()) {
            ExAI.getInstance().getLogger().warning("Failed to create lang folder");
        }
        File target = new File(folder, locale + ".yml");
        if (!target.exists()) {
            try {
                InputStream in = ExAI.getInstance().getResource("lang/" + locale + ".yml");
                if (in == null) {
                    return null;
                }
                java.nio.file.Files.copy(in, target.toPath());
            } catch (IOException e) {
                ExAI.getInstance().getLogger().warning("Failed to copy " + locale + ".yml: " + e.getMessage());
            }
        }
        if (!target.exists()) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(
                new java.io.FileInputStream(target), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            ExAI.getInstance().getLogger().warning("Failed to read " + target.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static YamlConfiguration loadBundled(String locale) {
        InputStream in = ExAI.getInstance().getResource("lang/" + locale + ".yml");
        if (in == null) return new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    public static String get(String key) {
        if (messages == null) {
            return key;
        }
        String value = messages.getString(key);
        if (value == null && fallback != null) {
            value = fallback.getString(key);
        }
        return value == null ? key : translateColor(value);
    }

    public static String get(String key, Object... args) {
        String template = get(key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    public static String currentLocale() {
        return currentLocale;
    }

    private static String translateColor(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace('&', '§');
    }
}
