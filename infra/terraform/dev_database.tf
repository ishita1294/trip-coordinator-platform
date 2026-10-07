locals {
  dev_database_name_prefix = "${var.project_name}-${var.environment}"
  dev_database_tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "development-postgres"
  }
}

data "aws_availability_zones" "dev_database" {
  state = "available"

  filter {
    name   = "zone-type"
    values = ["availability-zone"]
  }
}

resource "aws_vpc" "dev_database" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.dev_database_tags, {
    Name = "${local.dev_database_name_prefix}-vpc"
  })
}

resource "aws_subnet" "dev_database_public" {
  count = 2

  vpc_id            = aws_vpc.dev_database.id
  cidr_block        = "10.0.${count.index + 1}.0/24"
  availability_zone = data.aws_availability_zones.dev_database.names[count.index]

  tags = merge(local.dev_database_tags, {
    Name = "${local.dev_database_name_prefix}-public-${count.index + 1}"
  })
}

resource "aws_internet_gateway" "dev_database" {
  vpc_id = aws_vpc.dev_database.id

  tags = merge(local.dev_database_tags, {
    Name = "${local.dev_database_name_prefix}-igw"
  })
}

resource "aws_route_table" "dev_database_public" {
  vpc_id = aws_vpc.dev_database.id

  tags = merge(local.dev_database_tags, {
    Name = "${local.dev_database_name_prefix}-public"
  })
}

resource "aws_route" "dev_database_internet" {
  route_table_id         = aws_route_table.dev_database_public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.dev_database.id
}

resource "aws_route_table_association" "dev_database_public" {
  count = 2

  subnet_id      = aws_subnet.dev_database_public[count.index].id
  route_table_id = aws_route_table.dev_database_public.id
}

resource "aws_db_subnet_group" "dev_database" {
  name       = "${local.dev_database_name_prefix}-postgres"
  subnet_ids = aws_subnet.dev_database_public[*].id

  tags = merge(local.dev_database_tags, {
    Name = "${local.dev_database_name_prefix}-postgres"
  })
}

resource "aws_security_group" "dev_database" {
  name        = "${local.dev_database_name_prefix}-postgres"
  description = "PostgreSQL access from one trusted developer public IP only"
  vpc_id      = aws_vpc.dev_database.id
  tags        = local.dev_database_tags
}

resource "aws_vpc_security_group_ingress_rule" "dev_database_developer" {
  security_group_id = aws_security_group.dev_database.id
  description       = "Local development API PostgreSQL access"
  cidr_ipv4         = var.developer_cidr
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  tags              = local.dev_database_tags
}

resource "aws_db_instance" "dev_postgres" {
  identifier                  = "${local.dev_database_name_prefix}-postgres"
  engine                      = "postgres"
  engine_version              = "16"
  instance_class              = "db.t4g.micro"
  allocated_storage           = 20
  storage_type                = "gp3"
  storage_encrypted           = true
  port                        = 5432
  db_name                     = "tripcoordinator"
  username                    = "tripadmin"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.dev_database.name
  vpc_security_group_ids = [aws_security_group.dev_database.id]
  publicly_accessible    = true
  multi_az               = false

  deletion_protection          = false
  skip_final_snapshot          = true
  backup_retention_period      = 1
  auto_minor_version_upgrade   = true
  apply_immediately            = true
  performance_insights_enabled = false
  monitoring_interval          = 0

  tags = local.dev_database_tags

  # Public RDS must have both subnet routes to the Internet Gateway ready.
  depends_on = [
    aws_route.dev_database_internet,
    aws_route_table_association.dev_database_public,
    aws_vpc_security_group_ingress_rule.dev_database_developer
  ]
}
