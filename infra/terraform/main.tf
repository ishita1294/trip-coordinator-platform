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

resource "aws_s3_bucket_cors_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  cors_rule {
    allowed_origins = ["http://localhost:5173"]
    allowed_methods = ["PUT"]
    allowed_headers = ["Content-Type"]
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_sqs_queue" "document_processing_dlq" {
  name                      = "${var.project_name}-${var.environment}-document-processing-dlq"
  sqs_managed_sse_enabled   = true
  message_retention_seconds = 1209600

  tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "document-processing-dlq"
  }
}

resource "aws_sqs_queue" "document_processing" {
  name                       = "${var.project_name}-${var.environment}-document-processing"
  sqs_managed_sse_enabled    = true
  visibility_timeout_seconds = 180

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.document_processing_dlq.arn
    maxReceiveCount     = 5
  })

  tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "document-processing"
  }
}
