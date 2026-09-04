resource "aws_ecr_repository" "core_service" {
  name                 = "alphaadopter/core-service"
  image_tag_mutability = "MUTABLE"

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
