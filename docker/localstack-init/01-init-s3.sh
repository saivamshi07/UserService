#!/bin/bash
set -eo pipefail

echo "==> [LocalStack Init] Creating S3 bucket: user-avatars"
awslocal s3 mb s3://user-avatars || true

echo "==> [LocalStack Init] Configuring CORS for user-avatars bucket"
awslocal s3api put-bucket-cors --bucket user-avatars --cors-configuration '{
  "CORSRules": [
    {
      "AllowedHeaders": ["*"],
      "AllowedMethods": ["GET", "PUT", "POST", "HEAD", "DELETE"],
      "AllowedOrigins": ["*"],
      "ExposeHeaders": ["ETag"]
    }
  ]
}' 2>/dev/null || true

echo "==> [LocalStack Init] user-avatars bucket initialization complete!"
