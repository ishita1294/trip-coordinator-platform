locals {
  worker_network_tags = {
    Project     = var.project_name
    Environment = var.environment
    Purpose     = "document-processing"
  }
}

resource "aws_subnet" "document_processing_private" {
  count = 2

  vpc_id                  = aws_vpc.dev_database.id
  cidr_block              = "10.0.${count.index + 11}.0/24"
  availability_zone       = aws_subnet.dev_database_public[count.index].availability_zone
  map_public_ip_on_launch = false

  tags = merge(local.worker_network_tags, {
    Name = "${var.project_name}-${var.environment}-private-${count.index + 1}"
  })
}

resource "aws_route_table" "document_processing_private" {
  vpc_id = aws_vpc.dev_database.id

  tags = merge(local.worker_network_tags, {
    Name = "${var.project_name}-${var.environment}-private"
  })
}

resource "aws_route_table_association" "document_processing_private" {
  count = 2

  subnet_id      = aws_subnet.document_processing_private[count.index].id
  route_table_id = aws_route_table.document_processing_private.id
}

resource "aws_eip" "document_processing_nat" {
  count  = var.enable_nat_gateway ? 1 : 0
  domain = "vpc"

  tags = merge(local.worker_network_tags, {
    Name = "${var.project_name}-${var.environment}-nat"
  })
}

resource "aws_nat_gateway" "document_processing" {
  count = var.enable_nat_gateway ? 1 : 0

  allocation_id = aws_eip.document_processing_nat[0].id
  subnet_id     = aws_subnet.dev_database_public[0].id

  tags = merge(local.worker_network_tags, {
    Name = "${var.project_name}-${var.environment}-nat"
  })

  depends_on = [
    aws_internet_gateway.dev_database,
    aws_route.dev_database_internet,
    aws_route_table_association.dev_database_public
  ]
}

resource "aws_route" "document_processing_nat" {
  count = var.enable_nat_gateway ? 1 : 0

  route_table_id         = aws_route_table.document_processing_private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.document_processing[0].id
}

resource "aws_security_group" "document_processing_lambda" {
  name        = "${var.project_name}-${var.environment}-document-processing-lambda"
  description = "Document-processing worker outbound access; no inbound access"
  vpc_id      = aws_vpc.dev_database.id
  tags        = local.worker_network_tags
}

resource "aws_vpc_security_group_egress_rule" "document_processing_lambda" {
  security_group_id = aws_security_group.document_processing_lambda.id
  description       = "Worker outbound IPv4 access for PostgreSQL and external services"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  tags              = local.worker_network_tags
}

resource "aws_vpc_security_group_ingress_rule" "dev_database_lambda" {
  security_group_id            = aws_security_group.dev_database.id
  referenced_security_group_id = aws_security_group.document_processing_lambda.id
  description                  = "Document-processing Lambda PostgreSQL access"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  tags                         = local.worker_network_tags
}
