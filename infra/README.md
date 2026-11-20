# 인프라 운영 절차

AWS(EKS, RDS, ElastiCache, ECR)는 Terraform으로, 클러스터 안의 구성 요소(Kafka, MongoDB, 모니터링,
core-service)는 매니페스트와 고정된 Helm 차트 버전으로 관리합니다. 비용 관리를 위해 평소에는 내려 두고,
측정이나 시연이 필요할 때만 아래 스크립트로 올렸다가 내립니다.

## 준비물

- 도구: `aws`, `terraform`, `kubectl`, `helm`, `docker`
- AWS 자격 증명: `aws configure` 또는 `aws login`
- 비밀값 파일(저장소 밖, 기본 경로 `~/.alpha-adopter-secrets.env`)

```
DB_PASSWORD=...          # RDS 마스터 비밀번호
JWT_SECRET=...           # 32바이트 이상 무작위 값 (openssl rand -base64 32)
NAVER_CLIENT_ID=...      # NAVER Cloud API HUB > Application 의 Client ID (계정 인증키와 다름)
NAVER_CLIENT_SECRET=...
ADMIN_EMAILS=...         # 관리자로 승격할 가입 이메일
ANTHROPIC_API_KEY=...    # 선택: 없으면 AI 관련도 판단이 꺼짐(fail-open)
VOYAGE_API_KEY=...       # 선택: 없으면 임베딩 중복 제거가 꺼짐
```

## 올리기 / 내리기

```bash
./infra/scripts/cluster-up.sh     # 약 30분: Terraform → Kafka → MongoDB → 모니터링 → 비밀값 → 이미지 빌드·배포
./infra/scripts/cluster-down.sh   # 쿠버네티스가 만든 로드밸런서·볼륨을 먼저 지운 뒤 terraform destroy, 잔여 리소스 확인
```

이미지를 다시 빌드하지 않으려면 `SKIP_IMAGE_BUILD=true`를 붙입니다.

## 구성과 버전

| 구성 요소 | 방식 | 버전 |
|---|---|---|
| EKS | Terraform | Kubernetes 1.35, m7i-flex.large × 2 |
| Kafka | Strimzi 오퍼레이터(Helm) + `k8s/kafka` | Strimzi 1.3.1, Kafka 4.3.2 (KRaft, 단일 브로커) |
| MongoDB | `k8s/mongodb` | mongo 7 |
| 모니터링 | kube-prometheus-stack(Helm) | 93.4.1 |
| core-service | `k8s/core-service` + HPA(1~3) | 커밋 해시 태그 |

모든 워크로드에 메모리 요청·한도를 지정해 둡니다. 지정하지 않았을 때 스케줄러가 노드 메모리를 실제보다
많이 배정해 노드가 NotReady에 빠진 적이 있습니다.

## 확인

```bash
kubectl get pods -A
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80   # Grafana
kubectl port-forward -n monitoring svc/kube-prometheus-stack-prometheus 9090:9090
```

주요 지표: `notification_latency_seconds`(from=published|collected), `news_collector_*`,
`scheduler_lock_total`, `claude_relevance_*`, `news_dedup_total`.

## 비용

평소에는 리소스가 0입니다. 띄워 두면 노드 2대, NAT 게이트웨이, RDS, ElastiCache, 로드밸런서를 합쳐
대략 시간당 $0.45 안팎입니다.
