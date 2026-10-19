# 로컬 앱과 Git 업로드 보안 검토

단일 소유자가 사용하는 로컬 앱의 소스·설정·업로드 후보·Git 이력·현재 API·런타임 의존성을 검토했다. 침투 테스트를 수행하거나 모든 미래 취약점의 부재를 보장한 결과는 아니다.

## 2026-10-19 GitHub 게시 전 재점검

### 확인한 결과

| 항목 | 이번 결과 |
|---|---|
| 게시 후보 | 272개 파일 · 로컬 비밀값/토큰/개인 경로/이메일 검출0건 |
| Git | 기존 커밋0건 · 원격 저장소 미설정 · 게시/푸시 미실행 |
| 비공개 설정 | 로컬 설정9개 모두 권한600·Git 제외·심볼릭 링크 아님 |
| 코드/빌드 | Java115개·Python28개 통과, bootJar 빌드 성공 |
| 현재 API | 인증·CSRF·헤더·예산 등 읽기 전용55개 통과 |
| 민감 경로 | 환경 파일·Git 설정·Actuator 민감 경로·문서 경로 접근18개 차단 확인 |
| 개인 HTTPS | Secure/HttpOnly/SameSite Strict 쿠키·HSTS 확인, 앱/DB loopback 바인딩 확인 |
| 의존성 | 해석된 런타임 Maven81개를 OSV에 재조회 · 알려진 권고 검출0건 |
| Gradle | Wrapper JAR 해시가 공식 체크섬 및 저장된 체크섬과 일치 |

현재 로컬 비밀값의 직접/URL 인코딩/JSON 이스케이프/Base64 복사본과 일반 토큰 형식, 인용된 불투명 고엔트로피 문자열을 확인했다. 검출0건은 현재 후보·알려진 값/형식에 대한 결과다. 모든 숨김 방식·미래에 추가할 파일·취약점의 부재를 보장하지 않는다. 모델명·API 주소·정책 버전·요율 가정 같은 공개 상수와 인증 비밀값은 구분한다.

### 수정한 문제

1. **원시 식별 파일 검사 누락:** `KEY=value` 형식이 아닌 계좌 식별 salt·연구 식별 파일도 업로드 검사에 포함했다. URL/JSON/Base64와 Basic 인증 조합의 현재 비밀값 복사본, 개인 tailnet 주소와 인증정보 포함 URL도 검사한다. 진단에는 원문 없이 파일·위치·분류만 남긴다.
2. **키 저장의 예측 가능한 임시 파일:** KIS 설정을 무작위 이름·권한600 임시 파일로 작성한 뒤 교체한다. 심볼릭 링크·일반 파일이 아닌 목적지를 거절한다. 잘못된 입력은 기존 파일을 보존한다. macOS 기본 Bash에서 긴 정규식 반복 범위가 거절되는 문제도 길이 검사와 문자 검사로 분리했다.
3. **개인 실행 자료 유입 방지:** SQLite/TSV/아카이브·개인 키·heap/JFR 덤프·추가 IDE 출력/설정 경로를 게시 검사에서 거절한다. 로컬 Git 훅이 연결된 상태도 확인했다.

키 저장 테스트는 합성 값과 임시 폴더만 사용했다. 실제 키를 교체하거나 검증 API에 보내지 않았다. 실제 주문·유료 AI·Discord 시험 메시지는 실행하지 않았으며 앱 재시작도 필요하지 않은 스크립트/문서 보완이다. 과거 거래·AI/비용 원장은 변경하지 않았다.

### 게시 시 운영자가 확인할 것

- 첫 커밋/푸시 직전에 같은 검사를 다시 실행한다. 초기에는 비공개 저장소에서 게시 범위와 작성자 이름·이메일 공개 여부를 확인한다.
- 이미 외부에 노출한 키/웹훅은 파일에서 숨기는 것만으로 회수되지 않는다. 제공처에서 폐기·재발급한 뒤 로컬 입력 도구로 교체한다. 실제 로그인 비밀번호와 길이 예외는 별도 비공개 운영 설정이며 강도를 직접 확인한다.
- 공개 GitHub 저장소의 secret scanning은 Git 이력도 검사한다. 비공개 저장소에서는 계정/상품에 따라 사용 가능한 보호 기능이 다르므로 로컬 검사를 유지하고 지원되는 보호 기능을 확인한다.

[OSV 일괄 조회](https://google.github.io/osv.dev/post-v1-querybatch/) · [GitHub secret scanning](https://docs.github.com/en/code-security/concepts/secret-security/secret-scanning) · [노출 비밀값 대응](https://docs.github.com/en/code-security/tutorials/remediate-leaked-secrets/remediating-a-leaked-secret)

## 보호 경계

| 영역 | 적용 |
|---|---|
| 접근 | 앱과 DB의 127.0.0.1 바인딩, 업무 화면과 API의 OWNER 인증 |
| 세션 | BCrypt 12, 세션 고정 방지, HttpOnly/SameSite Strict, 30분 만료 |
| 요청 | CSRF 보호, 폼·헤더 크기 제한, multipart 미사용, CORS 확대 없음 |
| 표시 | Thymeleaf/textContent 이스케이프, CSP·프레임 차단·nosniff·referrer 미전송 |
| 오류 | 인증 API 401/no-store, 비밀 응답·스택 외부 미노출 |
| 외부 자료 | 고정 HTTPS 제공처, 리다이렉트 금지, XML 외부 엔티티 금지, 응답 크기 검사 |
| AI | 고정 근거·출처 ID·역할·버전·표 검증, 주문 도구 없음, 키와 응답 원문 로그 없음, AI 응답 수신 크기 제한 |
| 거래 실행 | 자동 매매/취소 제거 · 소유자 직접 확인·불변 모의 매도 의도·계좌/수량/가격/만료/중복 검사 · LIVE 전송 없음 |
| 파일 | 비밀 파일 권한 600/Git 제외, DB 비밀번호 기본값 제거, 설치별 무작위 비밀번호 |
| 업로드 | 공개 문서 허용 목록, 비밀값·토큰·개인 경로·이메일 검사, 스테이징·이력 검사 훅 |
| 빌드 | 공식 Gradle Wrapper 해시 확인, 배포 파일 SHA-256 고정 |

로그인 비밀번호 기본 최소 길이는12자다. 소유자가 명시적으로 짧은 값을 선택한 설치만 Git 제외 `.env.login`의 `APP_LOGIN_MIN_PASSWORD_LENGTH`로 최소 길이를8~72자 범위에서 지정할 수 있다. 이는 해당 기기의 길이 검사 예외이며 비밀번호 강도를 높이는 조치가 아니다. 실제 비밀번호와 선택한 값은 공개 기준 문서·소스·로그에 남기지 않는다.

로컬 HTTP에서는 Secure 쿠키를 강제하지 않는다. 개인 모바일 접속은 Tailscale Serve의 HTTPS를 거친다. 앱/DB는 계속127.0.0.1이며 프록시 헤더는127.0.0.1/32·::1/128만 신뢰한다. 실제 Host를 사용하고 전달된 호스트/포트를 무시한다. HTTPS에서 Secure·HttpOnly·SameSite Strict 세션 쿠키와 HSTS를 확인한다. Tailscale 로그인과 별도로 앱 OWNER 인증·CSRF가 필요하다. HTTP Basic과 DB 포트를 인터넷/LAN에 노출하지 않는다. Funnel과 공인 포트 개방은 사용하지 않는다. 이후 tailnet 사용자 추가나 기기 공유 시 Serve에 접근할 수 있는 범위를 다시 검토해야 한다.

## Git에 포함하는 자료

소스·README·일반 기준 문서만 업로드 후보에 포함한다. 실제 운용·거래·회의·잔고, 키·로그·백업·화면 캡처는 제외한다. 기존 개인 작업 문서 원본은 로컬에 보관한다. 최초 커밋 전 Git 작성자 이메일과 서명 등의 공개 범위도 확인한다.

`preflight-repository.py --candidates --history`는 일치한 값 대신 파일·위치·분류만 출력한다. 커밋 시 실제 스테이징 내용을, 푸시 시 현재 후보 파일과 도달 가능한 이력의 파일·커밋 메시지·태그를 읽는다.

훅은 기기마다 설치해야 하며 우회 가능하다. 알려지지 않은 비밀값·인코딩을 모두 검출하는 보증도 아니다. 업로드 직전 다시 검사하고 GitHub의 secret scanning/push protection을 함께 확인한다. 검사 스크립트는 파일이나 비밀값을 외부로 전송하지 않는다.

## 코드 정리

참조와 구현체가 없는 `BrokerClient`, 고립된 `StockAssessment`와 `Quote` 타입은 앞선 정리에서 제거했다. 2026-10-19에는 실제 호출 경로가 사라진 `PaperTradingService`·`PaperExecutionGate`·빈 자동매매 스케줄러, 미사용 시장 모델과 중복 잔고 조회를 추가 제거했다. `ManualTradingService`의 일반 매수·추가 매수·자동 익절 분기를 제거하고 사용자 확인 모의 매도만 단일 전송 경로로 남겼다. 일반 `place`의 거절·직접 확인·미확정 대조 경계는 유지한다. 마이그레이션과 과거 JSON 해석 타입은 원본 기록을 읽는 데 필요하므로 보존한다.

## 의존성 확인 결과

앞선 점검에서 런타임 Maven 패키지 59개를 조회하고 PostgreSQL JDBC·Jackson 2·Tomcat·Log4j를 수정 버전으로 올렸다. 남아 있던 Spring Framework 6.2.19의 두 권고는 이번에 **공식 수정 버전 7.0.9로 이전해 해결했다**.

| 항목 | 적용 버전 |
|---|---|
| Spring Boot | 4.0.8 |
| Spring Framework | 7.0.9 |
| Spring Security | 7.0.7 |
| Tomcat | 11.0.27 |
| Jackson 2 / Jackson 3 | 2.21.7 / 3.1.7 |
| PostgreSQL JDBC / Log4j | 42.7.12 / 2.25.5 |

- [CVE-2026-47884](https://spring.io/security/cve-2026-47884/): XsltView·포괄 경로 매핑·암시적 view 이름 조건의 SSRF/RCE 문제. 해당 기능을 사용하지 않는다는 판단에만 의존하지 않고, 권고된 수정 버전을 적용했다.
- [CVE-2026-47890](https://spring.io/security/cve-2026-47890/): SSE로 view fragment를 보내는 조건의 스트림 변조 문제. 같은 수정 버전으로 처리했다.

6.2.20 수정본은 공식 권고상 유료 Enterprise 지원 전용이므로 무료로 제공되는 7.0.9를 포함하는 Boot 4.0.8로 이전했다. 새 기본 의존성의 Jackson 3와 Tomcat에서도 별도 권고가 확인되어 같은 세대의 수정 버전으로 함께 올렸다. 최종 런타임 **81개 패키지**를 OSV에 재조회했으며 알려진 권고는 검출되지 않았다. 이는 조회 시점의 공개 자료 결과이며 알려지지 않은 취약점의 부재를 보장하지 않는다.

기존 JSON 원장을 그대로 읽기 위해 Boot가 제공하는 Jackson 2 호환 모듈과 MVC의 `jackson2` 설정을 사용한다. 신규 Jackson 3도 수정 버전으로 고정했다. Jackson 2 호환 모듈은 공식적으로 폐기 예정이므로 장기적으로는 별도 기록 호환성 검증 후 Jackson 3로 이전해야 한다. 이번 작업에서 기존 DB·마이그레이션 원본·회의 JSON을 변환하거나 덮어쓰지 않았다.

[Boot 4 이전 안내](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) · [JSON 호환 설정](https://docs.spring.io/spring-boot/4.0/reference/features/json.html) · [Tomcat 11 보안 권고](https://tomcat.apache.org/security-11.html)

## 확인한 범위와 한계

인증·CSRF·헤더·비밀 파일 권한·현재 데이터 수집·DB를 포함한 읽기 전용 점검 38개가 통과했다. Java 단위 테스트 74개, Python 검사 테스트 16개와 빌드가 통과했다. 업로드 후보 230개 파일에서 현재 로컬 비밀값·토큰·이메일·개인 홈 경로를 찾지 못했다. 현재 Git 커밋 이력은 없다. 거래 실행 테스트나 실제 돈을 사용하는 주문은 포함하지 않는다.

브라우저의 localhost 접근 제한 때문에 실제 화면 렌더링·휴대폰 접속은 이번 검토에서 확인하지 못했다. API와 JavaScript 구문 확인을 화면 검증 완료로 표현하지 않는다.

## 공식 참고

[GitHub push protection](https://docs.github.com/en/code-security/concepts/secret-security/push-protection) · [Spring 보안 권고](https://spring.io/security/) · [Gradle Wrapper 검증](https://docs.gradle.org/current/userguide/gradle_wrapper.html) · [OSV API](https://google.github.io/osv.dev/post-v1-querybatch/)


## Paid AI consent boundary

Automatic paid meeting creation has been removed. The owner must confirm a CSRF-protected POST; a durable immutable request is required by both the worker and the budget/send claim boundaries. Polling, selection, and a server restart cannot grant that request. Unknown calls retain their reserved costs. The default policy blocks new paid work. An optional, locally owner-enabled review restart can bind a newly confirmed meeting to the exact relevant unknown call IDs after a separate cost acknowledgement. It cannot alter or resend the original calls, clear a reservation, archive a call, grant a general exception, or cover new unknown IDs. Duplicate requests reuse the same control action/restart child. Error JSON contains only safe business messages, separate from network ambiguity.

Pause/resume/restart/close decisions require an immutable owner action, a current state timestamp and an idempotency UUID. A resume can retry only a known terminal call after a later explicit resume approval, with the existing three-attempt and monthly cost limits. Sent calls finish accounting while future sends are blocked; worker failure cannot overwrite an owner pause/close. Server recovery waits for an owner decision. Existing evidence, opinions, summaries and cost rows remain immutable.


## 자동매매 제거와 사용자 수동 거래

자동 후보 선택·위험 청산·익절·취소 호출과 스위치를 제거했다. 주기는 조회/대조만 한다. 일반 전략 주문은 거절한다. 직접 모의 매도는 인증+CSRF, 불변 의도,60초 만료,15초 잔고, 신선 시세, 원가/수량/요율·손실 확인을 검사한다. 소비된 같은 의도와 정확히 연결된 주문만 어댑터가 전송한다. 미확정은 재전송하지 않는다. 잔량 취소도 사용자 확인이 필요하며 LIVE는 미연결이다.

보유 조회·직접 매도 화면은 자동매매와 구분해 유지한다. 실제 주문 없이 단위/모의 전송 테스트를 수행한다. 과거 미요청 AI 호출은 소유자가 보관 승인한 건만 불변 보관 표시하며 예약액을 유지한다. 새 미확정은 차단한다. 유료 AI는 여전히 직접 확인 요청만 실행한다.

## 공개 기사 발췌 경계

피드에 저장된 정확한 `https://www.hankyung.com/article/숫자` 주소만 접근한다. 호스트·경로·포트·사용자정보·쿼리·프래그먼트를 확인하고 리다이렉트를 따르지 않는다. 인증·쿠키 없이 발행처가 isAccessibleForFree=true로 명시한 동일 제목의 NewsArticle과 지정된 본문 구간만 읽는다. 응답1.5MB 이전 취소·전체8초 취소,2분마다3건,24시간 캐시를 적용한다. 외부 스크립트·이미지·본문 안의 링크를 실행/방문하지 않는다. 추출한 최대1200자와 출처시각·해시를 불변 저장하며 화면은 textContent로 표시한다. 기사/공시 안의 문구는 AI 지시가 아닌 비신뢰 근거로 전달한다. 유료 기사나 확인하지 못한 내용은 만들어내지 않는다.

## 종료된 미확정 비용의 새 검토 확인

같은 작업공간·소유자 직접 요청·CLOSED가 모든 미확정 호출에서 확인된 로컬 허용 환경에 한해 새 Brief에서 비용을 확인할 수 있다. 인증/CSRF·confirmed·별도 비용 동의·현재 호출ID의 완전 일치를 검증하고 같은 트랜잭션에서 해당 Brief의 권한만 추가한다. 조회/종료만으로 권한을 만들지 않으며 UNKNOWN·예약액을 변경하지 않는다. 새 UNKNOWN은 예약/전송 직전 기존 검사에서 계속 차단한다.
