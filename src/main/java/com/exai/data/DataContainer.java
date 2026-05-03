package com.exai.data;

import com.exai.mysql.MySQL;

import java.util.HashMap;
import java.util.Map;

public class DataContainer {
    public static MySQL sql = null;
    public static Map<String, Long> playerCDMap = new HashMap<>();
    public static Map<String, Long> dialogueCDMap = new HashMap<>();
    public static Long playerChatResponseCD = 0L;
    public static Map<String, Integer> playerPendingKnowledgeCount = new HashMap<>();
    public static Map<String, String> submitterToUuid = new HashMap<>();
}