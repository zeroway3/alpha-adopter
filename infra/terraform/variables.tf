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
  default     = "1.35"
}

variable "node_instance_type" {
  description = "워커 노드 EC2 인스턴스 타입. 무료 플랜 계정은 free-tier-eligible 타입만 쓸 수 있다(t3.medium 불가). t3.small(2GB) 4대에서 Kafka·MongoDB·모니터링을 함께 띄우다 노드가 메모리 부족으로 NotReady에 빠진 적이 있어, 같은 비용대에서 메모리가 가장 큰 m7i-flex.large(8GB)로 바꿨다"
  type        = string
  default     = "m7i-flex.large"
}

variable "node_desired_size" {
  description = "워커 노드 기본 개수. 노드 장애 시 다른 노드로 재배치되는지 검증할 수 있도록 2대(총 16GB)"
  type        = number
  default     = 2
}
