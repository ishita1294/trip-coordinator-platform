locals {
  document_processing_function_name = "${var.project_name}-${var.environment}-document-processing"
  document_processing_package       = abspath("${path.module}/../../build/distributions/document-processing-lambda.zip")
  document_processing_tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "document-processing"
  }
}

# Only secret metadata is managed here. Populate its JSON value manually after apply.
resource "aws_secretsmanager_secret" "document_processing_openai" {
  name        = "${var.project_name}-${var.environment}-document-processing-openai"
  description = "OpenAI API key for the document-processing worker"
  tags        = local.document_processing_tags
}

resource "aws_s3_bucket" "lambda_artifacts" {
  bucket_prefix = "${var.project_name}-${var.environment}-artifacts-"
  force_destroy = true

  tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "lambda-deployment-artifacts"
  }
}

resource "aws_s3_bucket_public_access_block" "lambda_artifacts" {
  bucket = aws_s3_bucket.lambda_artifacts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "lambda_artifacts" {
  bucket = aws_s3_bucket.lambda_artifacts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_object" "document_processing_package" {
  bucket                 = aws_s3_bucket.lambda_artifacts.id
  key                    = "document-processing/document-processing-lambda.zip"
  source                 = local.document_processing_package
  source_hash            = filebase64sha256(local.document_processing_package)
  content_type           = "application/zip"
  server_side_encryption = "AES256"

  depends_on = [
    aws_s3_bucket_public_access_block.lambda_artifacts,
    aws_s3_bucket_server_side_encryption_configuration.lambda_artifacts
  ]
}

resource "aws_cloudwatch_log_group" "document_processing" {
  name              = "/aws/lambda/${local.document_processing_function_name}"
  tags              = local.document_processing_tags
  retention_in_days = 14
}

resource "aws_iam_role" "document_processing_lambda" {
  name = "${local.document_processing_function_name}-lambda"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = local.document_processing_tags
}

resource "aws_iam_role_policy_attachment" "document_processing_vpc" {
  role       = aws_iam_role.document_processing_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy" "document_processing_lambda" {
  name = "document-processing"
  role = aws_iam_role.document_processing_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.document_processing.arn}:*"
      },
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.document_processing.arn
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.documents.arn}/*"
      },
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_db_instance.dev_postgres.master_user_secret[0].secret_arn,
          aws_secretsmanager_secret.document_processing_openai.arn
        ]
      }
    ]
  })
}

resource "aws_lambda_function" "document_processing" {
  function_name    = local.document_processing_function_name
  role             = aws_iam_role.document_processing_lambda.arn
  runtime          = "java25"
  handler          = "com.ishita.tripcoordinatorplatform.lambda.DocumentProcessingLambdaHandler::handleRequest"
  timeout          = 60
  memory_size      = 1024
  architectures    = ["x86_64"]
  s3_bucket        = aws_s3_object.document_processing_package.bucket
  s3_key           = aws_s3_object.document_processing_package.key
  source_code_hash = filebase64sha256(local.document_processing_package)

  vpc_config {
    subnet_ids         = aws_subnet.document_processing_private[*].id
    security_group_ids = [aws_security_group.document_processing_lambda.id]
  }

  environment {
    variables = {
      DOCUMENT_STORAGE_S3_BUCKET = aws_s3_bucket.documents.id
      DOCUMENT_STORAGE_S3_REGION = var.aws_region
      SPRING_DATASOURCE_URL      = "jdbc:postgresql://${aws_db_instance.dev_postgres.address}:${aws_db_instance.dev_postgres.port}/${aws_db_instance.dev_postgres.db_name}"
      SPRING_DATASOURCE_USERNAME = aws_db_instance.dev_postgres.username
      RDS_SECRET_ARN             = aws_db_instance.dev_postgres.master_user_secret[0].secret_arn
      OPENAI_SECRET_ARN          = aws_secretsmanager_secret.document_processing_openai.arn
    }
  }

  tags = local.document_processing_tags
  depends_on = [
    aws_iam_role_policy.document_processing_lambda,
    aws_iam_role_policy_attachment.document_processing_vpc
  ]
}

# Consumption remains enabled independently of the NAT cost-control toggle.
# The handler fails whole batches, so batch size must remain exactly one.
resource "aws_lambda_event_source_mapping" "document_processing" {
  event_source_arn = aws_sqs_queue.document_processing.arn
  function_name    = aws_lambda_function.document_processing.arn
  batch_size       = 1
  enabled          = true

  depends_on = [aws_iam_role_policy.document_processing_lambda]
}
