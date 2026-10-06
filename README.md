# crowfoot-mcp

Crowfoot ERD 에디터의 **MCP 서버**다. Claude 같은 MCP 클라이언트가 Crowfoot의 요구사항과 ERD 문서를 읽고 쓰고, 데이터베이스에 반영하는 진입점이다.

The MCP server of the Crowfoot ERD editor: lets MCP clients such as Claude read and write requirements and ERD documents in Crowfoot, and apply them to a database.

## 구조

- Java 21 · Spring Boot · Spring AI(MCP 서버, Streamable HTTP 무상태 모드). **자기 저장소가 없다.**
- Gateway(`crowfoot-api-gateway`) 뒤에 선다. 외부 주소는 `https://crowfoot-mcp.java21.net/mcp`이고 구현 경로는 `/mcp`다.
- 토큰을 검증하지 않는다. Gateway가 토큰을 검증하고 사용자와 워크스페이스를 헤더(`X-USER-ID`, `X-TOKEN-WORKSPACE-ID`, `X-ACCESS-TOKEN-ID`)로 넘긴다. 헤더가 없는 요청은 `401`이다.
- 도구 호출을 받아 core(`crowfoot-core-api`)의 API를 부른다. 받은 헤더를 그대로 붙인다. 문서 본체를 직접 조립하지 않는다.
- 도구는 워크스페이스를 입력으로 받지 않는다. 헤더의 워크스페이스에서만 동작한다.

스펙은 docs 리포가 원천이다(`10-mcp/00-mcp-server.md`).

## 도구

| 구분 | 도구 |
|---|---|
| 읽기 | `get_workspace`, `list_documents`, `get_document`, `get_design_context`, `validate_document`, `export_ddl` |
| 문서 만들기 | `create_document`, `import_ddl` |
| 요구사항·ERD | `save_requirements`, `plan_requirements_sync`, `apply_requirements_sync`, `check_requirements`, `apply_schema`, `remove_objects` |
| 데이터베이스 | `list_databases`, `issue_database`, `list_connections`, `plan_deployment`, `deploy_document`, `plan_migration`, `apply_migration`, `plan_sample_data`, `insert_sample_data`, `plan_sync`, `apply_sync` |
| 신고 | `report_bug` |

배포(`deploy_document`)와 변경 반영(`apply_migration`)은 계획 도구가 돌려준 문서 버전과 계획 지문을 입력으로 받는다. 값이 지금과 다르면 실행하지 않는다.

요구사항 동기화(`plan_requirements_sync`, `apply_requirements_sync`)는 기준이 되는 요구사항 전체 목록으로 문서의 요구사항을 맞춘다. 목록에 없는 요구사항은 기본으로 `dropped`로 바꾸고 `acceptRemovals=true`일 때만 지운다.

수용 기준 확인(`check_requirements`)은 요구사항 수용 기준의 확인 SQL을 문서가 연결된 데이터베이스에서 읽기 전용으로 실행해 기대값과 견준다.

동기화(`plan_sync`, `apply_sync`)는 반대 방향이다. 데이터베이스의 구조를 문서로 가져온다. 문서에만 있는 객체(`removals`)는 `includeRemovals=true`일 때만 지운다.

## 로컬 실행

```sh
mvn spring-boot:run        # 포트 8085, 기본 프로필 local
mvn test                   # 테스트(core는 테스트 안의 작은 HTTP 서버가 흉내 낸다)
```

core(8082)가 떠 있어야 요청이 처리된다. 필요한 시크릿은 없다.

Gateway 없이 부를 때는 헤더를 직접 넣는다.

```sh
curl -s -X POST http://localhost:8085/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'X-USER-ID: 2' -H 'X-TOKEN-WORKSPACE-ID: 4' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 클라이언트 등록

워크스페이스의 MCP 탭에서 토큰을 발급한 뒤 등록한다.

```sh
claude mcp add --transport http crowfoot https://crowfoot-mcp.java21.net/mcp --header "Authorization: Bearer <토큰>"
```

## 라이선스

Apache License 2.0
