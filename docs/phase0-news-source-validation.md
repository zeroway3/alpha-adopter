# Phase 0 — 뉴스 데이터 소스 검증

## 결론

NAVER API HUB의 Search News API로 뉴스 데이터 수집이 가능함을 확인했습니다. (검증일: 2026-09-04)

## 사용 API

- **NAVER API HUB** (기존 NAVER Developers Center의 검색 API는 신규 신청 종료, API HUB로 이관됨)
- Endpoint: `https://naverapihub.apigw.ntruss.com/search/v1/news`
- 인증 헤더: `X-NCP-APIGW-API-KEY-ID`, `X-NCP-APIGW-API-KEY`
- 할당량: 25,000회/일, 775,000회/월

## 요청 예시

```bash
curl -G "https://naverapihub.apigw.ntruss.com/search/v1/news" \
  --data-urlencode "query=삼성전자" \
  --data-urlencode "display=5" \
  -H "X-NCP-APIGW-API-KEY-ID: $NAVER_CLIENT_ID" \
  -H "X-NCP-APIGW-API-KEY: $NAVER_CLIENT_SECRET"
```

한글/공백이 포함된 검색어는 `--data-urlencode`로 넘겨야 합니다. URL에 직접 넣으면 400 Bad Request가 발생합니다.

## 응답 구조

```json
{
  "lastBuildDate": "...",
  "total": 539229,
  "start": 1,
  "display": 5,
  "items": [
    {
      "title": "...",
      "originallink": "...",
      "link": "...",
      "description": "...",
      "pubDate": "Fri, 04 Sep 2026 15:58:00 +0900"
    }
  ]
}
```

## 수집/파싱 시 주의할 점

- `title`, `description`에 검색어와 일치하는 부분이 `<b>...</b>`로 하이라이트되어 옴 → 저장/매칭 전에 HTML 태그 제거 필요
- `link`는 네이버 뉴스 링크, `originallink`는 원 언론사 링크로 다를 수 있음
- 저작권 이슈 방지를 위해 본문 전체가 아니라 `title`·`description`(요약)·`link`만 저장·전달하는 방향으로 설계

## 다음 단계

Phase 1 — Spring Boot + JPA로 핵심 도메인(User, Subscription, NewsArticle, Notification) 설계
