package net.java21.crowfoot.mcp.tool.input;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * 도구 입력 — 모양은 문서 편집 API의 요청 본문과 같다 (08-core/17-model-edit.md Section 3.2~3.4).
 * 필드 설명은 도구의 입력 스키마(JSON Schema)로 나가 MCP 클라이언트가 읽는다. 값의 규칙은 core가 판정한다.
 * 비어 있는 필드는 core에 보내지 않는다 — "주지 않은 필드는 그대로 둔다"가 편집 API의 규칙이다.
 * 스키마 생성기는 표시가 없는 필드를 필수로 본다. 선택 필드에는 @JsonProperty(기본 required=false)를 붙인다.
 */
public final class Inputs {

    private Inputs() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequirementItem(
            @JsonProperty @JsonPropertyDescription("요구사항 코드(REQ-001 형식). 있으면 그 요구사항을 고치고, 생략하면 다음 번호로 새로 만든다") String code,
            @JsonProperty @JsonPropertyDescription("한 줄 제목(1~100자). 새로 만들 때 필수") String title,
            @JsonProperty @JsonPropertyDescription("내용과 업무 규칙(2,000자 이하). 기능에 딸린 규칙은 여기에 적는다") String description,
            @JsonProperty @JsonPropertyDescription("상태: draft(검토 중), confirmed(확정), dropped(제외). 새로 만들 때 생략하면 draft") String status,
            @JsonProperty @JsonPropertyDescription("범위: tables(기능 요구사항 — 테이블에 연결한다, 기본) 또는 document(문서 전체에 적용되는 공통 요구사항). 고칠 때는 바꿀 수 없다") String scope,
            @JsonProperty @JsonPropertyDescription("도메인 = 그룹 이름. 없는 이름이면 빈 그룹을 만든다. 빈 문자열은 미분류로 돌린다") String domain,
            @JsonProperty @JsonPropertyDescription("이 요구사항을 구현하는 테이블의 물리명 전체 목록. 주면 연결을 이 목록으로 바꾼다") List<String> tables,
            @JsonProperty @JsonPropertyDescription("수용 기준 전체 목록(20개까지). 주면 목록째 바꾼다 — 문구가 같은 기준은 체크 상태를 이어받는다. "
                    + "기준마다 sql(데이터로 확인하는 SELECT — 한 값을 돌려준다)과 expect(기대값, 생략하면 0)를 둘 수 있다. 수용 기준만 바꾸면 반영 대기가 되지 않는다") List<CriterionInput> criteria) {
    }

    /** 수용 기준 한 줄 — check_requirements가 sql을 원천 데이터베이스에서 읽기 전용으로 실행해 expect와 견준다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CriterionInput(
            @JsonProperty(required = true) @JsonPropertyDescription("기준 문장(1~200자). 예: 주문은 회원만 만든다") String text,
            @JsonProperty @JsonPropertyDescription("데이터로 확인하는 SELECT 한 문장. 첫 행 첫 열의 값을 expect와 견준다. "
                    + "예: SELECT COUNT(*) FROM orders WHERE member_id IS NULL. 위반 건수를 세어 0을 기대하는 꼴이 좋다") String sql,
            @JsonProperty @JsonPropertyDescription("기대값(문자열). 숫자는 수로 견준다. 생략하면 0") String expect) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableInput(
            @JsonProperty(required = true) @JsonPropertyDescription("테이블 물리명. 소문자로 시작하고 소문자·숫자·밑줄만 쓴다(63자 이하). 같은 물리명의 테이블이 있으면 고치고 없으면 만든다") String physicalName,
            @JsonProperty @JsonPropertyDescription("물리명을 바꿀 때 새 물리명. 기존 테이블에만 쓴다") String rename,
            @JsonProperty @JsonPropertyDescription("논리명(사람이 읽는 이름)") String logicalName,
            @JsonProperty @JsonPropertyDescription("테이블 설명") String description,
            @JsonProperty @JsonPropertyDescription("컬럼. 물리명이 같은 컬럼은 고치고 없는 컬럼은 더한다. 여기에 없는 기존 컬럼은 그대로 둔다. 외래 키 컬럼은 넣지 않는다 — 관계가 만든다") List<ColumnInput> columns,
            @JsonProperty @JsonPropertyDescription("기본 키 컬럼의 물리명. 주면 기본 키를 이 목록으로 바꾼다") List<String> primaryKey,
            @JsonProperty @JsonPropertyDescription("유니크 키. 같은 컬럼 조합이 없으면 만든다") List<UniqueInput> uniques,
            @JsonProperty @JsonPropertyDescription("인덱스. 같은 컬럼 조합이 없으면 만든다. 관계의 외래 키 인덱스는 자동으로 생기므로 넣지 않는다") List<IndexInput> indexes,
            @JsonProperty @JsonPropertyDescription("이 테이블의 근거가 되는 요구사항 코드. 그 요구사항에 이 테이블을 연결하고 반영한 것으로 표시한다") List<String> requirementCodes,
            @JsonProperty @JsonPropertyDescription("CHECK 제약. 같은 이름이 있으면 식을 바꾸고 없으면 더한다. 지우려면 remove_objects의 checks") List<CheckInput> checks) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckInput(
            @JsonProperty @JsonPropertyDescription("제약 이름. 생략하면 ck_테이블_n 으로 만든다") String name,
            @JsonProperty(required = true) @JsonPropertyDescription("CHECK 식(SQL 원문). 예: status IN ('DRAFT', 'PUBLISHED')") String expression) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeneratedInput(
            @JsonProperty(required = true) @JsonPropertyDescription("생성식(SQL 원문). 빈 문자열이면 생성 컬럼을 해제한다") String expression,
            @JsonProperty @JsonPropertyDescription("저장형(STORED)이면 true(기본), 가상형(VIRTUAL)이면 false") Boolean stored) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnInput(
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명. 소문자·숫자·밑줄") String physicalName,
            @JsonProperty @JsonPropertyDescription("물리명을 바꿀 때 새 물리명. 기존 컬럼에만 쓴다") String rename,
            @JsonProperty @JsonPropertyDescription("논리명") String logicalName,
            @JsonProperty @JsonPropertyDescription("컬럼 설명") String description,
            @JsonProperty @JsonPropertyDescription("공용 타입 코드: INT, BIGINT, SMALLINT, TINYINT, DECIMAL, NUMERIC, FLOAT, DOUBLE, CHAR, VARCHAR, TEXT, MEDIUMTEXT, LONGTEXT, BOOLEAN, DATE, TIME, DATETIME, TIMESTAMP, JSON, UUID, BLOB, BINARY, VARBINARY. DBMS의 물리 표기를 넣지 않는다. 새 컬럼에 필수(domainType을 주면 생략)") String dataType,
            @JsonProperty @JsonPropertyDescription("길이 — CHAR·VARCHAR·BINARY·VARBINARY에만") Integer length,
            @JsonProperty @JsonPropertyDescription("정밀도 — DECIMAL·NUMERIC에만. TIME·DATETIME·TIMESTAMP에서는 소수 초 자릿수(0~6)") Integer precision,
            @JsonProperty @JsonPropertyDescription("스케일 — DECIMAL·NUMERIC에만") Integer scale,
            @JsonProperty @JsonPropertyDescription("NULL 허용 여부. 새 컬럼에서 생략하면 true") Boolean nullable,
            @JsonProperty @JsonPropertyDescription("기본값. 문자열은 따옴표 없이 쓴다(ACTIVE — Crowfoot이 DDL에서 따옴표를 붙인다). 빈 문자열 기본값은 ''로 쓴다(빈 값은 기본값을 지운다). 예: 0, ACTIVE, '', CURRENT_TIMESTAMP(6), (uuid())") String defaultValue,
            @JsonProperty @JsonPropertyDescription("자동 증가 — 정수 타입의 단일 컬럼 기본 키에만") Boolean autoIncrement,
            @JsonProperty @JsonPropertyDescription("워크스페이스 도메인 타입의 이름(get_design_context로 확인). 타입·길이·NULL 허용·기본값을 그 값으로 채운다") String domainType,
            @JsonProperty @JsonPropertyDescription("생성 컬럼(계산 컬럼). 생성 컬럼에는 기본값·자동 증가·onUpdate를 두지 않는다") GeneratedInput generated,
            @JsonProperty @JsonPropertyDescription("행을 고칠 때 자동으로 넣는 값(MySQL ON UPDATE). 예: CURRENT_TIMESTAMP(6). 빈 문자열이면 해제") String onUpdate,
            @JsonProperty @JsonPropertyDescription("IDENTITY 종류 — 자동 증가 컬럼에서 ALWAYS(GENERATED ALWAYS — 값을 직접 넣을 수 없다) 또는 BY_DEFAULT(기본). PostgreSQL·Oracle에서만 DDL이 달라진다") String identityGeneration) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UniqueInput(
            @JsonProperty @JsonPropertyDescription("키 이름. 생략하면 uk_테이블_컬럼… 으로 만든다") String name,
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명(복합 키는 순서대로)") List<String> columns) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexInput(
            @JsonProperty @JsonPropertyDescription("인덱스 이름. 생략하면 idx_테이블_컬럼… 으로 만든다. 이 테이블에 같은 이름의 인덱스가 있으면 그 인덱스를 고친다") String name,
            @JsonProperty @JsonPropertyDescription("인덱스 컬럼(순서대로). 식이 든 키는 columns 대신 expression에 적는다") List<IndexColumnInput> columns,
            @JsonProperty @JsonPropertyDescription("종류: BTREE(기본), FULLTEXT(전문 검색)·SPATIAL(공간) — MySQL, HASH — MySQL·PostgreSQL, GIN·GIST·BRIN·SPGIST — PostgreSQL. 지원하지 않는 DBMS의 DDL에서는 빠지고 경고가 난다") String type,
            @JsonProperty @JsonPropertyDescription("FULLTEXT 인덱스의 MySQL 파서 이름. 예: ngram") String parser,
            @JsonProperty @JsonPropertyDescription("유니크 인덱스. 컬럼만으로 된 유니크는 uniques에 적고, 조건(where)이나 식이 붙은 유니크만 여기에 true로 적는다") Boolean unique,
            @JsonProperty @JsonPropertyDescription("식이 든 키 목록 원문 — columns 대신. 예: lower(nickname), tenant_id, lower(email) DESC. 빈 문자열이면 해제") String expression,
            @JsonProperty @JsonPropertyDescription("부분 인덱스 조건(WHERE 없이). 예: deleted_at IS NULL. PostgreSQL·SQL Server만 DDL에 낸다. 빈 문자열이면 해제") String where,
            @JsonProperty @JsonPropertyDescription("INCLUDE 컬럼 물리명(키가 아닌 덮개 컬럼). PostgreSQL·SQL Server만 DDL에 낸다. 빈 배열이면 해제") List<String> include) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexColumnInput(
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명") String name,
            @JsonProperty @JsonPropertyDescription("정렬: ASC(기본) 또는 DESC") String order,
            @JsonProperty @JsonPropertyDescription("PostgreSQL 연산자 클래스. 예: gin_trgm_ops, varchar_pattern_ops") String opclass) {
    }

    /**
     * 관계 — 자식 쪽 기수(childMultiplicity)는 받지 않는다. 그림의 표기에만 쓰이는 값이고(DDL에 영향 없음),
     * 클라이언트가 임의로 "0개 이상"을 고르면 이 서버로 만든 문서만 자식 쪽이 ○로 그려져 손으로 그린 문서와 달라 보인다.
     * core가 에디터의 기본값(1:N은 ONE_OR_MORE, 1:1은 EXACTLY_ONE)으로 채우고, 있던 관계의 값은 그대로 둔다.
     * 바꾸려면 Crowfoot 화면의 관계 편집에서 바꾼다 (docs 10-mcp/00-mcp-server.md Section 4.3)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipInput(
            @JsonProperty(required = true) @JsonPropertyDescription("부모 테이블(참조되는 쪽, 기본 키가 있어야 한다)의 물리명") String parent,
            @JsonProperty(required = true) @JsonPropertyDescription("자식 테이블(외래 키를 갖는 쪽)의 물리명. 부모와 같으면 자기 참조") String child,
            @JsonProperty @JsonPropertyDescription("ONE_TO_MANY(기본) 또는 ONE_TO_ONE. N:M은 연결 테이블과 1:N 두 개로 표현한다") String type,
            @JsonProperty @JsonPropertyDescription("식별 관계 여부 — true면 외래 키가 자식의 기본 키에 들어간다. 기본 false") Boolean identifying,
            @JsonProperty @JsonPropertyDescription("부모 쪽 기수: EXACTLY_ONE(외래 키 NOT NULL, 기본) 또는 ZERO_OR_ONE(외래 키 NULL 허용)") String parentMultiplicity,
            @JsonProperty @JsonPropertyDescription("NO_ACTION(기본), RESTRICT, CASCADE, SET_NULL, SET_DEFAULT") String onDelete,
            @JsonProperty @JsonPropertyDescription("NO_ACTION(기본), RESTRICT, CASCADE, SET_NULL, SET_DEFAULT") String onUpdate,
            @JsonProperty @JsonPropertyDescription("자식의 기존 컬럼을 외래 키로 쓸 때만. 생략하면 외래 키 컬럼을 새로 만든다. 부모와 자식이 같은 관계가 여럿이고 name이 없으면 외래 키 컬럼이 같은 관계를 고친다") List<ColumnMappingInput> columnMappings,
            @JsonProperty @JsonPropertyDescription("외래 키 이름(get_document의 관계 name). 부모와 자식이 같은 관계가 여럿일 때 고칠 관계를 고른다. 문서에 없는 이름이면 그 이름으로 관계를 하나 더 만든다(예: member → follow의 fk_follow_follower와 fk_follow_followee)") String name) {
    }

    public record ColumnMappingInput(
            @JsonProperty(required = true) @JsonPropertyDescription("부모의 기본 키 컬럼 물리명") String parentColumn,
            @JsonProperty(required = true) @JsonPropertyDescription("자식의 컬럼 물리명") String childColumn) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AreaInput(
            @JsonProperty(required = true) @JsonPropertyDescription("그룹(도메인) 이름. 같은 이름의 그룹이 있으면 고치고 없으면 만든다") String name,
            @JsonProperty @JsonPropertyDescription("이름을 바꿀 때 새 이름") String rename,
            @JsonProperty @JsonPropertyDescription("색: default, red, orange, amber, yellow, green, teal, sky, blue, violet, pink") String color,
            @JsonProperty @JsonPropertyDescription("그룹 설명") String description,
            @JsonProperty @JsonPropertyDescription("멤버 테이블의 물리명 전체 목록. 주면 멤버를 이 목록으로 바꾼다") List<String> tables) {
    }

    public record ColumnRef(
            @JsonProperty(required = true) @JsonPropertyDescription("테이블 물리명") String table,
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명") String column) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipRef(
            @JsonProperty(required = true) @JsonPropertyDescription("부모 테이블 물리명") String parent,
            @JsonProperty(required = true) @JsonPropertyDescription("자식 테이블 물리명") String child,
            @JsonProperty @JsonPropertyDescription("외래 키 이름(get_document의 관계 name). 부모와 자식이 같은 관계가 여럿이면 있어야 한다") String name) {
    }

    public record CheckRef(
            @JsonProperty(required = true) @JsonPropertyDescription("테이블 물리명") String table,
            @JsonProperty(required = true) @JsonPropertyDescription("CHECK 제약 이름(get_document의 checks name)") String name) {
    }

    public record IndexRef(
            @JsonProperty(required = true) @JsonPropertyDescription("테이블 물리명") String table,
            @JsonProperty(required = true) @JsonPropertyDescription("인덱스 이름(get_document의 indexes name)") String name) {
    }

    /** 샘플 데이터 — 테이블 하나와 넣을 행들 */
    public record SampleTable(
            @JsonPropertyDescription("테이블 물리명") String name,
            @JsonPropertyDescription("넣을 행 목록. 행 하나는 컬럼 물리명과 값의 맵이다. 값은 문자열, 숫자, 불리언, null. 날짜와 시각은 '2026-01-15', '2026-01-15 09:30:00' 같은 문자열로 적는다. 자동 증가 컬럼은 비워 두면 데이터베이스가 채운다") List<java.util.Map<String, Object>> rows) {
    }
}
