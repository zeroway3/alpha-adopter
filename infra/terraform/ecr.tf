resource "aws_ecr_repository" "core_service" {
  name                 = "alphaadopter/core-service"
  image_tag_mutability = "MUTABLE"
  # 비용 관리를 위해 실측 후 terraform destroy로 내리는 환경이라, 이미지가 남아 있어도 저장소를
  # 지울 수 있게 한다(태그 없는 레이어가 남아 destroy가 중간에 멈춘 적이 있음).
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "alphaadopter-core-service"
  }
}

resource "aws_ecr_lifecycle_policy" "core_service" {
  repository = aws_ecr_repository.core_service.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 5개 이미지만 보관"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
