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

output "document_processing_queue_url" {
  description = "Queue URL for the document-processing outbox publisher."
  value       = aws_sqs_queue.document_processing.url
}

output "document_processing_queue_arn" {
  description = "Document-processing queue ARN for future runtime permissions."
  value       = aws_sqs_queue.document_processing.arn
}

output "document_processing_dlq_arn" {
  description = "Document-processing dead-letter queue ARN."
  value       = aws_sqs_queue.document_processing_dlq.arn
}
