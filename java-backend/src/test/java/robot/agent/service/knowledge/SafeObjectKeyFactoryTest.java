package robot.agent.service.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeObjectKeyFactoryTest {

    @Test
    void buildsRawObjectKeyWithSanitizedFilename() {
        SafeObjectKeyFactory factory = new SafeObjectKeyFactory();

        String key = factory.rawObjectKey(1L, "kb_product", "doc_001", "../产品 手册.pdf");

        assertThat(key).isEqualTo("raw/1/kb_product/doc_001/产品_手册.pdf");
    }

    @Test
    void buildsExtractedTextObjectKey() {
        SafeObjectKeyFactory factory = new SafeObjectKeyFactory();

        String key = factory.extractedTextObjectKey(1L, "kb_product", "doc_001");

        assertThat(key).isEqualTo("extracted/1/kb_product/doc_001/content.json");
    }
}
