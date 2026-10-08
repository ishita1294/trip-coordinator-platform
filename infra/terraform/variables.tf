variable "aws_region" {
  description = "AWS region for the travel document bucket."
  type        = string
}

variable "enable_nat_gateway" {
  description = "Enable one dev NAT Gateway for outbound internet access from worker private subnets."
  type        = bool
  default     = false
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

variable "developer_cidr" {
  description = "Trusted developer public IPv4 CIDR for temporary dev RDS access. Use /24 through /32."
  type        = string

  validation {
    condition = (
      can(cidrnetmask(var.developer_cidr)) &&
      can(regex("/(24|25|26|27|28|29|30|31|32)$", var.developer_cidr)) &&
      var.developer_cidr != "0.0.0.0/32"
    )
    error_message = "developer_cidr must be a valid IPv4 CIDR with a /24 through /32 mask."
  }
}
