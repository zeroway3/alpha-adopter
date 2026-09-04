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

variable "cluster_name" {
  description = "EKS 클러스터 이름"
  type        = string
  default     = "alphaadopter"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes 버전"
  type        = string
  default     = "1.31"
}

variable "node_instance_type" {
  description = "워커 노드 EC2 인스턴스 타입 (프리티어 계정이라 free-tier-eligible 타입만 사용 가능)"
  type        = string
  default     = "t3.small"
}

variable "node_desired_size" {
  description = "워커 노드 기본 개수 (실측 부하테스트에서 t3.small 2대로는 Kafka+Mongo+core-service를 동시에 감당 못해 메모리 압박이 발생함을 확인, 3대로 조정)"
  type        = number
  default     = 3
}
