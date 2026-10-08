package com.platform.userservice.service;

import com.platform.userservice.dto.AvatarPresignedUrlRequest;
import com.platform.userservice.dto.AvatarPresignedUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarStorageService {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final PolicyValidatorService policyValidator;

    @Value("${storage.s3.bucket-name:user-avatars}")
    private String bucketName;

    @Value("${storage.s3.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${storage.s3.presigned-url-expiration-minutes:15}")
    private long expirationMinutes;

    public AvatarPresignedUrlResponse createPresignedUploadUrl(UUID userId, AvatarPresignedUrlRequest request) {
        policyValidator.validateAvatarUpload(request.getContentType(), request.getFileSizeBytes());

        String fileExtension = extractExtension(request.getFileName());
        String s3Key = "avatars/" + userId + "/" + UUID.randomUUID() + fileExtension;

        Duration duration = Duration.ofMinutes(expirationMinutes);
        Instant expiresAt = Instant.now().plus(duration);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(request.getContentType())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedPutObjectRequest = s3Presigner.presignPutObject(presignRequest);
        String uploadUrl = presignedPutObjectRequest.url().toString();

        // The permanent URL where the image is stored
        String fileUrl = endpoint + "/" + bucketName + "/" + s3Key;

        log.info("Generated presigned upload URL for user {} with key {}", userId, s3Key);

        return AvatarPresignedUrlResponse.builder()
                .uploadUrl(uploadUrl)
                .fileUrl(fileUrl)
                .s3Key(s3Key)
                .expiresAt(expiresAt)
                .build();
    }

    public void deleteAvatar(String pictureUrl) {
        if (!StringUtils.hasText(pictureUrl)) {
            return;
        }

        try {
            String prefix = "/" + bucketName + "/";
            int index = pictureUrl.indexOf(prefix);
            String s3Key;
            if (index != -1) {
                s3Key = pictureUrl.substring(index + prefix.length());
            } else if (pictureUrl.startsWith("avatars/")) {
                s3Key = pictureUrl;
            } else {
                log.warn("Could not determine S3 key from pictureUrl: {}", pictureUrl);
                return;
            }

            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build());
            log.info("Successfully deleted avatar from S3 with key: {}", s3Key);
        } catch (Exception ex) {
            log.error("Failed to delete avatar from S3 (url: {}): {}", pictureUrl, ex.getMessage());
        }
    }

    private String extractExtension(String fileName) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
        }
        return ".jpg";
    }
}
