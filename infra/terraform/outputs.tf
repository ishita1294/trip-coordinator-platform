output "document_bucket_name" {
  description = "Bucket name for application document storage configuration."
  value       = aws_s3_bucket.documents.id
}

output "document_bucket_arn" {
  description = "Bucket ARN for future runtime permission configuration."
  value       = aws_s3_bucket.documents.arn
}

output "aws_region" {
  description = "AWS region containing the document bucket."
  value       = var.aws_region
}
