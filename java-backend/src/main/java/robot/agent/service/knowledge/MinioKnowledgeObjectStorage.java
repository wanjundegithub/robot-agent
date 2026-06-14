package robot.agent.service.knowledge;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import robot.agent.config.KnowledgeProperties;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
public class MinioKnowledgeObjectStorage implements KnowledgeObjectStorage {
    private final KnowledgeProperties properties;
    private final MinioClient minioClient;

    public MinioKnowledgeObjectStorage(KnowledgeProperties properties) {
        this.properties = properties;
        KnowledgeProperties.Minio minio = properties.getStorage().getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .region(minio.getRegion())
                .build();
    }

    @Override
    public StoredKnowledgeObject put(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket();
            String bucket = properties.getStorage().getMinio().getBucket();
            String effectiveContentType = contentType == null || contentType.isBlank()
                    ? "application/octet-stream"
                    : contentType;
            var result = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(effectiveContentType)
                    .build());
            return new StoredKnowledgeObject(bucket, objectKey, result.etag(), effectiveContentType, size);
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to write knowledge object to MinIO: " + objectKey, exc);
        }
    }

    @Override
    public URL presignedGetUrl(String objectKey) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getStorage().getMinio().getBucket())
                    .object(objectKey)
                    .expiry(properties.getStorage().getPresignedUrlTtlSeconds(), TimeUnit.SECONDS)
                    .build());
            return URI.create(url).toURL();
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to create MinIO presigned URL: " + objectKey, exc);
        }
    }

    private void ensureBucket() throws Exception {
        String bucket = properties.getStorage().getMinio().getBucket();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
