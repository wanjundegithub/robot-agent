package robot.agent.service.knowledge;

import org.springframework.stereotype.Component;

@Component
public class SafeObjectKeyFactory {

    public String rawObjectKey(Long workspaceId, String kbCode, String docId, String originalFilename) {
        return "raw/%s/%s/%s/%s".formatted(
                workspaceId,
                cleanPathPart(kbCode),
                cleanPathPart(docId),
                sanitizeFilename(originalFilename)
        );
    }

    public String longTextObjectKey(Long workspaceId, String kbCode, String docId) {
        return "raw/%s/%s/%s/content.txt".formatted(
                workspaceId,
                cleanPathPart(kbCode),
                cleanPathPart(docId)
        );
    }

    public String extractedTextObjectKey(Long workspaceId, String kbCode, String docId) {
        return "extracted/%s/%s/%s/content.json".formatted(
                workspaceId,
                cleanPathPart(kbCode),
                cleanPathPart(docId)
        );
    }

    private String sanitizeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "knowledge.txt" : filename.trim();
        value = value.replace("\\", "/");
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\r\\n\\t]+", "_").replaceAll("[ ]+", "_");
        value = value.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}._-]", "_");
        return value.isBlank() ? "knowledge.txt" : value;
    }

    private String cleanPathPart(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.isBlank() ? "default" : normalized;
    }
}
