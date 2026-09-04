variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "environment" {
  description = "환경 구분 (dev/prod)"
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "사용할 가용 영역"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "db_name" {
  description = "PostgreSQL 데이터베이스 이름"
  type        = string
  default     = "alpha_adopter"
}

variable "db_username" {
  description = "PostgreSQL 마스터 사용자명"
  type        = string
  default     = "alpha_adopter"
}

variable "db_password" {
  description = "PostgreSQL 마스터 비밀번호"
  type        = string
  sensitive   = true
}
