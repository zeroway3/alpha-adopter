#!/usr/bin/env bash
# AWS 인프라부터 애플리케이션 배포까지 한 번에 올린다.
#
#   SECRETS_FILE=~/.alpha-adopter-secrets.env ./infra/scripts/cluster-up.sh
#
# 비밀값 파일(KEY=VALUE 형식)에는 DB_PASSWORD, JWT_SECRET, NAVER_CLIENT_ID, NAVER_CLIENT_SECRET,
# ADMIN_EMAILS가 필요하고 ANTHROPIC_API_KEY, VOYAGE_API_KEY는 선택이다. 값은 화면에 출력하지 않는다.
#
# 수동으로 올리던 시절 겪은 문제를 순서로 고정했다:
# - 오퍼레이터/차트를 latest로 설치했다가 Kafka 버전 지원이 바뀌어 브로커가 안 뜬 적이 있어 버전을 고정한다
# - 각 단계가 실제로 준비될 때까지 기다린 뒤 다음 단계로 넘어간다
set -euo pipefail

STRIMZI_VERSION="1.3.1"             # Kafka 4.3.x 지원, kafka.strimzi.io/v1 API
KUBE_PROMETHEUS_STACK_VERSION="93.4.1"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
CLUSTER_NAME="alphaadopter"
SECRETS_FILE="${SECRETS_FILE:-$HOME/.alpha-adopter-secrets.env}"
SKIP_IMAGE_BUILD="${SKIP_IMAGE_BUILD:-false}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K8S="$ROOT/infra/k8s"

log() { printf '\n==> %s\n' "$*"; }

secret() { grep -E "^$1=" "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//'; }

require_secrets() {
  [[ -f "$SECRETS_FILE" ]] || { echo "비밀값 파일이 없습니다: $SECRETS_FILE" >&2; exit 1; }
  local missing=()
  for key in DB_PASSWORD JWT_SECRET NAVER_CLIENT_ID NAVER_CLIENT_SECRET ADMIN_EMAILS; do
    [[ -n "$(secret "$key")" ]] || missing+=("$key")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "비밀값 파일에 다음 값이 비어 있습니다: ${missing[*]}" >&2
    exit 1
  fi
}

require_tools() {
  for tool in aws terraform kubectl helm docker; do
    command -v "$tool" >/dev/null || { echo "필요한 도구가 없습니다: $tool" >&2; exit 1; }
  done
}

require_tools
require_secrets
export TF_VAR_db_password="$(secret DB_PASSWORD)"

log "1/7 Terraform으로 AWS 인프라 생성 (VPC, EKS, RDS, ElastiCache, ECR, IAM)"
terraform -chdir="$ROOT/infra/terraform" init -input=false >/dev/null
terraform -chdir="$ROOT/infra/terraform" apply -input=false -auto-approve
aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null
kubectl wait --for=condition=Ready nodes --all --timeout=10m

log "2/7 스토리지 클래스(gp3)"
kubectl apply -f "$K8S/kafka/storageclass.yaml"

log "3/7 Kafka (Strimzi $STRIMZI_VERSION)"
helm upgrade --install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator \
  --version "$STRIMZI_VERSION" -n kafka --create-namespace --wait --timeout 5m
kubectl apply -f "$K8S/kafka/kafka-cluster.yaml"
kubectl wait kafka/alphaadopter-kafka -n kafka --for=condition=Ready --timeout=15m

log "4/7 MongoDB"
kubectl apply -f "$K8S/mongodb/mongodb.yaml"
kubectl rollout status deployment/mongodb -n alphaadopter --timeout=5m

log "5/7 모니터링 (kube-prometheus-stack $KUBE_PROMETHEUS_STACK_VERSION)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KUBE_PROMETHEUS_STACK_VERSION" -n monitoring --create-namespace \
  -f "$K8S/monitoring/kube-prometheus-stack-values.yaml" --wait --timeout 15m
kubectl apply -f "$K8S/monitoring/github-actions-rbac.yaml"

log "6/7 애플리케이션 비밀값 (값은 출력하지 않음)"
tmp_env="$(mktemp)"
trap 'rm -f "$tmp_env"' EXIT
for key in DB_PASSWORD JWT_SECRET NAVER_CLIENT_ID NAVER_CLIENT_SECRET ADMIN_EMAILS ANTHROPIC_API_KEY VOYAGE_API_KEY; do
  value="$(secret "$key")"
  [[ -n "$value" ]] && printf '%s=%s\n' "$key" "$value" >> "$tmp_env"
done
kubectl create secret generic core-service-secrets -n alphaadopter \
  --from-env-file="$tmp_env" --dry-run=client -o yaml | kubectl apply -f -

log "7/7 core-service 이미지 빌드·푸시와 배포"
registry="$(terraform -chdir="$ROOT/infra/terraform" output -raw ecr_repository_url)"
tag="$(git -C "$ROOT" rev-parse --short HEAD)"
if [[ "$SKIP_IMAGE_BUILD" != "true" ]]; then
  aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${registry%%/*}"
  docker build --platform linux/amd64 -f "$ROOT/core-service/Dockerfile" -t "$registry:$tag" -t "$registry:latest" "$ROOT"
  docker push "$registry:$tag"
  docker push "$registry:latest"
fi
sed "s#image: .*/alphaadopter/core-service:.*#image: $registry:$tag#" "$K8S/core-service/deployment.yaml" | kubectl apply -f -
kubectl apply -f "$K8S/core-service/hpa.yaml"
kubectl rollout status deployment/core-service -n alphaadopter --timeout=10m

lb=""
for _ in $(seq 1 30); do
  lb="$(kubectl get svc core-service -n alphaadopter -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
  [[ -n "$lb" ]] && break
  sleep 10
done
log "완료: http://$lb  (DNS 전파에 1~2분 걸릴 수 있음)"
echo "Grafana: kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80"
echo "내릴 때: ./infra/scripts/cluster-down.sh"
