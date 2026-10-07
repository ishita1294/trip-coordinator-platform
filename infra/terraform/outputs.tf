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

output "rds_address" {
  description = "Development PostgreSQL hostname, without the port."
  value       = aws_db_instance.dev_postgres.address
}

output "rds_port" {
  description = "Development PostgreSQL connection port."
  value       = aws_db_instance.dev_postgres.port
}

output "rds_database_name" {
  description = "Development PostgreSQL database name."
  value       = aws_db_instance.dev_postgres.db_name
}

output "rds_instance_identifier" {
  description = "Development RDS instance identifier."
  value       = aws_db_instance.dev_postgres.identifier
}

output "rds_master_user_secret_arn" {
  description = "ARN of the RDS-managed master credential secret; never its contents."
  value       = aws_db_instance.dev_postgres.master_user_secret[0].secret_arn
}

output "dev_vpc_id" {
  description = "Dedicated development VPC ID."
  value       = aws_vpc.dev_database.id
}
