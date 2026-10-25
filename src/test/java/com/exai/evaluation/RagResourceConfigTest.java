package com.exai.evaluation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RagResourceConfigTest {
    @Test
    public void bundledLocalesContainRagPromptsAndMessages() {
        for (String locale : new String[]{"zh_CN", "en_US"}) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new File("src/main/resources/lang/" + locale + ".yml"));
            assertNotNull(yaml.getString("prompt.knowledge-id"));
            assertNotNull(yaml.getString("prompt.rag-judge"));
            assertNotNull(yaml.getString("book.rag-test-title"));
            assertNotNull(yaml.getString("gui.rag-annotation-title"));
            assertNotNull(yaml.getString("gui.rag-annotation-input-question"));
            assertNotNull(yaml.getString("gui.rag-annotation-save-title"));
            assertNotNull(yaml.getString("gui.rag-dataset-title"));
            assertNotNull(yaml.getString("gui.rag-dataset-new"));
            assertNotNull(yaml.getString("rag.test-summary"));
            assertNotNull(yaml.getString("rag.test-metrics-retrieval"));
            assertNotNull(yaml.getString("rag.test-metrics-quality"));
            assertNotNull(yaml.getString("rag.test-metrics-risk"));
            assertNotNull(yaml.getString("rag.test-metrics-latency"));
        }
    }

    @Test
    public void defaultEvaluationConfigIsPresent() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml"));
        assertEquals(4, yaml.getInt("evaluation.judge.passScore"));
        assertEquals(20, yaml.getInt("evaluation.resultRetention"));
    }
}
