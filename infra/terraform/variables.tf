variable "aws_region" {
  description = "AWS region for the travel document bucket."
  type        = string
}

variable "project_name" {
  description = "Short project name used in the bucket prefix and tags."
  type        = string
  default     = "trip-coordinator"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,15}$", var.project_name))
    error_message = "project_name must be 1–16 lowercase letters, digits, or hyphens, starting with a letter."
  }
}

variable "environment" {
  description = "Environment name used in the bucket prefix and tags."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,7}$", var.environment))
    error_message = "environment must be 1–8 lowercase letters, digits, or hyphens, starting with a letter."
  }
}
