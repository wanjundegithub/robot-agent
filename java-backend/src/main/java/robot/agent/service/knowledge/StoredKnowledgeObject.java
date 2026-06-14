package robot.agent.service.knowledge;

public record StoredKnowledgeObject(
        String bucket,
        String objectKey,
        String etag,
        String contentType,
        long size
) {
}
