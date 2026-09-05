# 5단계 — 실측 부하테스트 및 관측성 구축 결과

## 관측성 스택

- `kube-prometheus-stack` Helm 차트로 Prometheus + Grafana + kube-state-metrics + node-exporter를 `monitoring` 네임스페이스에 구성 (`infra/k8s/monitoring/kube-prometheus-stack-values.yaml`)
- t3.small(2GB) x4 클러스터에서 Kafka+MongoDB+core-service와 같이 떠야 해서 Alertmanager는 끄고, Prometheus/Grafana 리소스 요청을 최소화. 클러스터 전역 ServiceMonitor를 인식하도록 `serviceMonitorSelectorNilUsesHelmValues: false` 설정
- core-service에 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 추가, `/actuator/prometheus`를 `ServiceMonitor`(`infra/k8s/core-service/deployment.yaml`)로 15초 간격 스크랩
- Grafana는 비용(ELB)·보안을 위해 공개 LoadBalancer 없이 ClusterIP로만 노출, `kubectl port-forward`로 접근
- 알아둘 점: Grafana는 RWO(gp3) PVC를 쓰는 단일 레플리카라 기본 RollingUpdate 전략으로 갱신하면 새 파드가 볼륨을 얻지 못해 Multi-Attach 에러로 멈춘다. `strategy.type: Recreate`로 고정해서 해결

## 부하테스트 (k6)

`loadtest/subscription-flow.js` — 구독 등록(`POST /api/subscriptions`) → 조회(`GET /api/subscriptions`) 흐름을 시뮬레이션. 20 VU → 50 VU로 램프업, 총 3분 30초.

```
BASE_URL=http://<core-service LoadBalancer> k6 run loadtest/subscription-flow.js
```

### 결과 (2026-09-05, t3.small x4, core-service HPA 적용 상태)

| 지표 | 값 |
|---|---|
| 총 요청 수 | 12,278건 (58.4 req/s) |
| 실패율 | 0.00% |
| create_subscription p95 | 18.33ms |
| list_subscriptions p95 | 15.44ms |
| 평균 응답시간 | 13.37ms |
| 최대 응답시간 | 68.76ms |

- 부하 중 core-service HPA가 1 → 3 파드로 정상 스케일 (재시작/장애 없음) — 이전 Apache Bench 즉흥 테스트 때 겪었던 노드 다운 없이 안정적으로 처리
- 4개 노드 모두 Ready 유지, 메모리 사용률 65~103% 사이 (일부 노드 일시적으로 100% 초과했으나 OOM/파드 재시작 없음 — 페이지 캐시 등 포함 수치로 추정, 실사용 여유는 충분)

### 참고

- SSE 알림 스트림(`GET /api/notifications/stream/{userId}`)은 롱-라이브 스트리밍 응답이라 k6 기본 기능으로 검증 불가 — 이 스크립트 범위에서 제외. SSE 자체 동작은 3단계 구현 시 `curl -N`으로 수동 확인 완료된 상태
