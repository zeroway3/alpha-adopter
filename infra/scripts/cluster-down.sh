#!/usr/bin/env bash
# 클러스터를 내리고 과금 리소스가 남지 않았는지 확인한다.
#
#   SECRETS_FILE=~/.alpha-adopter-secrets.env ./infra/scripts/cluster-down.sh
#
# 쿠버네티스가 직접 만든 리소스(Service의 로드밸런서, PVC의 EBS 볼륨)는 Terraform이 모른다. 바로
# terraform destroy를 하면 로드밸런서가 VPC 삭제를 막거나, 볼륨이 주인 없이 남아 계속 과금된다(실제로
# 18GB가 남은 적이 있다). 그래서 쿠버네티스 쪽을 먼저 지우고 볼륨이 사라진 것을 확인한 뒤 내린다.
set -euo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
CLUSTER_NAME="alphaadopter"
SECRETS_FILE="${SECRETS_FILE:-$HOME/.alpha-adopter-secrets.env}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

log() { printf '\n==> %s\n' "$*"; }
secret() { grep -E "^$1=" "$SECRETS_FILE" | head -1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//'; }

if aws eks describe-cluster --name "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1; then
  aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null

  log "1/3 로드밸런서와 볼륨을 만든 쿠버네티스 리소스 삭제"
  kubectl delete svc core-service -n alphaadopter --ignore-not-found --wait=true
  kubectl delete namespace alphaadopter kafka monitoring --ignore-not-found --wait=true --timeout=10m

  for _ in $(seq 1 30); do
    remaining="$(kubectl get pv --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    [[ "$remaining" == "0" ]] && break
    echo "EBS 볼륨 삭제 대기 중: ${remaining}개"
    sleep 10
  done
else
  log "1/3 클러스터가 없어 쿠버네티스 정리는 건너뜀"
fi

log "2/3 Terraform으로 AWS 인프라 삭제"
export TF_VAR_db_password="$(secret DB_PASSWORD)"
terraform -chdir="$ROOT/infra/terraform" destroy -input=false -auto-approve

log "3/3 남은 과금 리소스 확인"
volumes="$(aws ec2 describe-volumes --region "$AWS_REGION" \
  --filters "Name=tag-key,Values=kubernetes.io/created-for/pvc/name" --query 'Volumes[].VolumeId' --output text)"
lbs="$(aws elbv2 describe-load-balancers --region "$AWS_REGION" --query 'LoadBalancers[].LoadBalancerName' --output text)"
classic_lbs="$(aws elb describe-load-balancers --region "$AWS_REGION" --query 'LoadBalancerDescriptions[].LoadBalancerName' --output text)"
if [[ -n "$volumes$lbs$classic_lbs" ]]; then
  echo "남은 리소스가 있습니다. 확인 후 직접 삭제하세요." >&2
  echo "  EBS: ${volumes:-없음}" >&2
  echo "  로드밸런서: ${lbs:-없음} ${classic_lbs}" >&2
  exit 1
fi
echo "남은 과금 리소스 없음"
