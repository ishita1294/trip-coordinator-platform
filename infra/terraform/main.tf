# The provider appends a unique suffix to this bucket prefix.
# Application object keys: trips/{tripId}/documents/{uuid}-{filename}
# TravelDocument.storageKey holds only the object key; the bucket is configuration.
resource "aws_s3_bucket" "documents" {
  bucket_prefix = "${var.project_name}-${var.environment}-documents-"
  force_destroy = false

  # Versioning is intentionally left disabled for unique, immutable uploads.
  tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "travel-documents"
  }
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket = aws_s3_bucket.documents.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
