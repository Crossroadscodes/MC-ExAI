package com.exai.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class MaterialCompat {

    private static final boolean MODERN = Material.getMaterial("LIME_STAINED_GLASS_PANE") != null;

    @SuppressWarnings("deprecation")
    public static ItemStack limeGlassPane() {
        if (MODERN) {
            return new ItemStack(Material.getMaterial("LIME_STAINED_GLASS_PANE"));
        }
        return new ItemStack(Material.getMaterial("STAINED_GLASS_PANE"), 1, (short) 5);
    }

    @SuppressWarnings("deprecation")
    public static ItemStack redGlassPane() {
        if (MODERN) {
            return new ItemStack(Material.getMaterial("RED_STAINED_GLASS_PANE"));
        }
        return new ItemStack(Material.getMaterial("STAINED_GLASS_PANE"), 1, (short) 14);
    }

    public static Material BOOK_AND_QUILL() {
        if (MODERN) {
            return Material.getMaterial("BOOK");
        }
        return Material.getMaterial("BOOK_AND_QUILL");
    }

    public static Material WRITABLE_BOOK() {
        if (MODERN) {
            return Material.getMaterial("WRITABLE_BOOK");
        }
        return Material.getMaterial("BOOK_AND_QUILL");
    }

    public static Material ITEM_FRAME() {
        return Material.getMaterial("ITEM_FRAME");
    }

    public static ItemStack steveHead() {
        Material head = Material.getMaterial("PLAYER_HEAD");
        if (head == null) {
            head = Material.SKULL_ITEM;
            ItemStack item = new ItemStack(head);
            item.setDurability((short) 3);
            return item;
        }
        return new ItemStack(head);
    }
}
