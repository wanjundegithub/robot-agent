package robot.agent.service.knowledge;

import java.io.InputStream;
import java.net.URL;

public interface KnowledgeObjectStorage {
    StoredKnowledgeObject put(String objectKey, InputStream inputStream, long size, String contentType);

    URL presignedGetUrl(String objectKey);
}
