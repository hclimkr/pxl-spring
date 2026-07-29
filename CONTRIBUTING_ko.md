[English](CONTRIBUTING.md) · **한국어**

# 기여 가이드

이 문서는 저장소 구조·빌드·테스트·코드 규칙을 정리한 개발자용 안내다.  
라이브러리 사용법은 [README_ko.md](README_ko.md)를 참고한다.

---

## 저장소 구조

Maven 멀티모듈 프로젝트다.

| 모듈 | 대상 | 스프링 | 컴파일 |
|---|---|---|---|
| `pxl-spring-javax` | Java 8+, `javax.*` | Spring 5.3 / Boot 2.7 | source/target 8 |
| `pxl-spring-jakarta` | Java 17+, `jakarta.*` | Spring 6.2 / Boot 3.5 | source/target 17 |

> ⚠️ 소스 코드는 `pxl-spring-javax`에만 있다. `pxl-spring-jakarta`는 직접 편집하지 않는다.

`pxl-spring-jakarta`에는 직접 작성한 소스 코드가 없다.
빌드 시 형제 모듈 `pxl-spring-javax`의 소스 트리(`src/main/java`, test는 `src/test`)를 파일시스템 복사해 아래 문자열 치환을 거친 뒤 컴파일해 생성된다(`pxl-spring-jakarta/pom.xml`의 `maven-antrun-plugin`).

```
import javax.annotation.   →   import jakarta.annotation.
import javax.validation.   →   import jakarta.validation.
import javax.servlet.      →   import jakarta.servlet.
```

따라서:

- 코드 수정은 항상 `pxl-spring-javax`에서 한다. `pxl-spring-jakarta` 쪽 동작을 바꾸려면 `pxl-spring-javax`를 고쳐 다시 빌드한다.
- `pxl-spring-javax`의 이식 대상 import는 정확히 위 세 계열(`javax.annotation.*` / `javax.validation.*` / `javax.servlet.*`)로만 제한한다 — 그 외 `javax.*`는 치환되지 않으므로 jakarta 변형에서 그대로 남는다.

베이스 패키지는 `io.github.hclimkr.pxl.spring`이고 여기에는 `PxlSpring` 파사드와 튜닝 상수 `PxlSpringConstants`만 있다. 다섯 개의 공개 `@Component @Validated` 클래스는 `component/`에 있으며, 내부 구현(`internal/*`)과의 경계는 `.internal.` 네이밍 관례로 유지한다(사용자는 `internal.*`를 참조하지 않는다).

---

## 개발 환경

- JDK 17이 PATH에 있어야 한다(`pxl-spring-javax`는 8로 컴파일하지만 빌드 자체는 JDK 17에서 수행).
- Lombok이 전반에 쓰이며 각 모듈의 `maven-compiler-plugin`에 애노테이션 프로세서로 연결돼 있다. IDE에서 Lombok 플러그인을 활성화한다.

---

## 빌드 & 테스트

`pxl-spring-jakarta`는 `pxl-spring-javax`의 소스 트리를 직접 복사하므로 아티팩트 install에 의존하지 않지만(reactor 순서는 루트 `<modules>` 선언 순서로 유지된다),
두 모듈을 한 번에 빌드하려면 루트에서 실행하기를 권장한다.

```bash
mvn clean install                                        # 두 모듈 빌드 + 테스트
mvn -pl pxl-spring-javax clean install                   # javax 모듈 (원본)
mvn -pl pxl-spring-jakarta clean install                 # jakarta 모듈 (소스 재생성)
mvn -pl pxl-spring-javax test -Dtest=PxlExcelExporterTests            # 단일 테스트 클래스
mvn -pl pxl-spring-javax test -Dtest=PxlExcelImporterTests#someMethod # 단일 테스트 메서드
mvn install -DskipTests                                  # 테스트 없이 빌드
```

- 테스트는 JUnit 5(Jupiter) + AssertJ 기반이다.
- export 결과 파일은 `pxl-spring-javax/target/test-outputs/`(`TestPaths.EXPORT_DIR`)에 `*.xlsx` / `*.xls` / `*.zip`로 기록되며, `target/` 하위이므로 `mvn clean` 시 삭제된다.

---

## 테스트 작성 규칙

- 새 `*Tests.java`를 만들지 않는다. 실행 클래스는 메인 패키지 구조를 그대로 따라간다. 컴포넌트별 다섯 클래스(`PxlExcelImporterTests`, `PxlCsvImporterTests`, `PxlExcelExporterTests`, `PxlSampleExcelExporterTests`, `PxlExcelZipExporterTests`)는 `io.github.hclimkr.pxl.spring.component`에, `PxlSpringTests`(파사드)·`PxlValidationTests`(`@Validated` 인자 검증)·`PxlRequiredHeaderStylerTests`(javax/jakarta shadowing 가드)는 루트 테스트 패키지에 있으니 해당 클래스에 메서드를 추가한다. 컴포넌트가 아닌 클래스는 `internal/support/Pxl*SupportTests`와 `logging/PxlPerformanceLoggingAspectTests`가 담당하며, 내부 클래스를 새로 만들 때만 그 옆에 전용 테스트 클래스를 추가한다.
- 그중 둘은 바깥에서 `component` 패키지를 참조하는데, 둘 다 그 자체로는 관측되지 않는 불변식을 지키기 위해서다. `PxlCoreSupportTests`(모든 컴포넌트가 공유 코어 홀더를 쓴다)와 `PxlPerformanceLoggingAspectTests`(백엔드 17개가 자기 클래스명 태그로 `@PxlPerformanceLogging`을 달고 있고, 시작 메서드와 파사드는 달지 않는다). 프로덕션 의존성은 그와 무관하게 `component` → `internal.support` 한 방향을 유지한다.
- 동작 테스트는 컴포넌트 인스턴스가 아니라 `PxlSpring`(`private final PxlSpring pxlSpring = new PxlSpring();`)으로 구동한다 — README가 안내하는 진입점이고, 파사드가 소유 컴포넌트의 빌더를 그대로 돌려주므로 검증 대상은 같다. 예외는 위의 불변식 테스트 둘과, 프록시된 컴포넌트와 파사드를 일부러 대조하는 `PxlValidationTests`다.
- 명명 규칙: 클래스명 = 컴포넌트, 메서드명 = `기능_상황_결과` camelCase.
- 바인딩 대상이 되는 애노테이션 붙은 DTO 픽스처는 `tcdata/` 하위 패키지에 둔다. 입력 데이터는 대부분 각 테스트에서 코드로 직접 생성한다.
- 로직·성능을 바꾸는 수정에는 회귀/특성화 테스트를 함께 추가한다.

---

## 코드 규칙

- JavaDoc(`/** */`)과 인라인·블록 주석(`//`·`/* */`)은 영어로 작성한다.
- 공개 컴포넌트의 필수 인자는 `@Validated` 클래스에 붙인 Bean Validation 파라미터 애노테이션(`@NotNull` 등)으로 검증한다 — `hibernate-validator` 구현체가 호출 시점에 검사한다. 단 한 가지 예외가 있다: 이 애노테이션들은 Spring 프록시를 통과할 때만 발동하므로, 인자를 그대로 역참조하게 되는 백엔드는 첫 문장에서 `PxlArgumentSupport.requireNonNull(...)`으로 뒷받침해야 한다. 그래야 평문으로 만든 컴포넌트(`new PxlExcelExporter()`, `new PxlSpring()`이 하는 일)도 raw `NullPointerException` 대신 "모든 실패는 `PxlException`" 계약 안에 남는다.
- 다섯 컴포넌트의 빌더 백엔드 메서드(`...To*` / `...From*`, 총 17개)는 모두 `@PxlPerformanceLogging(TAG)`(각자 클래스명 태그)를 단다.AOP 아스펙트는 opt-in(`pxl.performance.logging.enabled=true`)이며 기본 비활성화다.
- 작업 트리는 CRLF 줄바꿈을 쓰지만 `.gitattributes`가 없어, `core.autocrlf=true`(이 저장소 기준) 설정에서 Git이 커밋되는 blob을 LF로 정규화한다. 대량 치환 후 EOL이 섞이지 않도록 주의한다.

---

## Pull Request

1. `main`에서 브랜치를 딴다.
2. 변경에 대응하는 테스트를 추가/수정한다.
3. `mvn clean install`이 통과하는지 로컬에서 확인한다.
4. PR을 올린다.

---

## 라이선스

기여한 코드는 프로젝트와 동일하게 [Apache License 2.0](LICENSE) 하에 배포되는 데 동의하는 것으로 간주한다.
