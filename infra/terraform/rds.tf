resource "aws_db_subnet_group" "main" {
  name       = "alphaadopter-db-subnet-group"
  subnet_ids = aws_subnet.private[*].id

  tags = {
    Name = "alphaadopter-db-subnet-group"
  }
}

resource "aws_security_group" "rds" {
  name        = "alphaadopter-rds-sg"
  description = "RDS PostgreSQL access from within VPC only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "PostgreSQL from VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "alphaadopter-rds-sg"
  }
}

resource "aws_db_instance" "main" {
  identifier     = "alphaadopter-db"
  engine         = "postgres"
  engine_version = "16.15"
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 20
  storage_type          = "gp3"

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az                = false
  publicly_accessible     = false
  skip_final_snapshot     = true
  backup_retention_period = 0

  tags = {
    Name = "alphaadopter-db"
  }
}
