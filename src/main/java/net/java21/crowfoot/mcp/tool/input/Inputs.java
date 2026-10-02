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
            @JsonProperty @JsonPropertyDescription("이 요구사항을 구현하는 테이블의 물리명 전체 목록. 주면 연결을 이 목록으로 바꾼다") List<String> tables) {
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
            @JsonProperty @JsonPropertyDescription("이 테이블의 근거가 되는 요구사항 코드. 그 요구사항에 이 테이블을 연결하고 반영한 것으로 표시한다") List<String> requirementCodes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnInput(
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명. 소문자·숫자·밑줄") String physicalName,
            @JsonProperty @JsonPropertyDescription("물리명을 바꿀 때 새 물리명. 기존 컬럼에만 쓴다") String rename,
            @JsonProperty @JsonPropertyDescription("논리명") String logicalName,
            @JsonProperty @JsonPropertyDescription("컬럼 설명") String description,
            @JsonProperty @JsonPropertyDescription("공용 타입 코드: INT, BIGINT, SMALLINT, TINYINT, DECIMAL, NUMERIC, FLOAT, DOUBLE, CHAR, VARCHAR, TEXT, BOOLEAN, DATE, TIME, DATETIME, TIMESTAMP, JSON, UUID, BLOB. DBMS의 물리 표기를 넣지 않는다. 새 컬럼에 필수(domainType을 주면 생략)") String dataType,
            @JsonProperty @JsonPropertyDescription("길이 — CHAR·VARCHAR에만") Integer length,
            @JsonProperty @JsonPropertyDescription("정밀도 — DECIMAL·NUMERIC에만") Integer precision,
            @JsonProperty @JsonPropertyDescription("스케일 — DECIMAL·NUMERIC에만") Integer scale,
            @JsonProperty @JsonPropertyDescription("NULL 허용 여부. 새 컬럼에서 생략하면 true") Boolean nullable,
            @JsonProperty @JsonPropertyDescription("기본값 표현. 예: 0, CURRENT_TIMESTAMP") String defaultValue,
            @JsonProperty @JsonPropertyDescription("자동 증가 — 정수 타입의 단일 컬럼 기본 키에만") Boolean autoIncrement,
            @JsonProperty @JsonPropertyDescription("워크스페이스 도메인 타입의 이름(get_design_context로 확인). 타입·길이·NULL 허용·기본값을 그 값으로 채운다") String domainType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UniqueInput(
            @JsonProperty @JsonPropertyDescription("키 이름. 생략하면 uk_테이블_컬럼… 으로 만든다") String name,
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명(복합 키는 순서대로)") List<String> columns) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexInput(
            @JsonProperty @JsonPropertyDescription("인덱스 이름. 생략하면 idx_테이블_컬럼… 으로 만든다") String name,
            @JsonProperty(required = true) @JsonPropertyDescription("인덱스 컬럼(순서대로)") List<IndexColumnInput> columns) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexColumnInput(
            @JsonProperty(required = true) @JsonPropertyDescription("컬럼 물리명") String name,
            @JsonProperty @JsonPropertyDescription("정렬: ASC(기본) 또는 DESC") String order) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipInput(
            @JsonProperty(required = true) @JsonPropertyDescription("부모 테이블(참조되는 쪽, 기본 키가 있어야 한다)의 물리명") String parent,
            @JsonProperty(required = true) @JsonPropertyDescription("자식 테이블(외래 키를 갖는 쪽)의 물리명. 부모와 같으면 자기 참조") String child,
            @JsonProperty @JsonPropertyDescription("ONE_TO_MANY(기본) 또는 ONE_TO_ONE. N:M은 연결 테이블과 1:N 두 개로 표현한다") String type,
            @JsonProperty @JsonPropertyDescription("식별 관계 여부 — true면 외래 키가 자식의 기본 키에 들어간다. 기본 false") Boolean identifying,
            @JsonProperty @JsonPropertyDescription("부모 쪽 기수: EXACTLY_ONE(외래 키 NOT NULL, 기본) 또는 ZERO_OR_ONE(외래 키 NULL 허용)") String parentMultiplicity,
            @JsonProperty @JsonPropertyDescription("자식 쪽 기수: 1:N은 ONE_OR_MORE(기본)·ZERO_OR_MORE, 1:1은 EXACTLY_ONE(기본)·ZERO_OR_ONE. 특별한 이유가 없으면 생략한다") String childMultiplicity,
            @JsonProperty @JsonPropertyDescription("NO_ACTION(기본), RESTRICT, CASCADE, SET_NULL, SET_DEFAULT") String onDelete,
            @JsonProperty @JsonPropertyDescription("NO_ACTION(기본), RESTRICT, CASCADE, SET_NULL, SET_DEFAULT") String onUpdate,
            @JsonProperty @JsonPropertyDescription("자식의 기존 컬럼을 외래 키로 쓸 때만. 생략하면 외래 키 컬럼을 새로 만든다") List<ColumnMappingInput> columnMappings) {
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

    public record RelationshipRef(
            @JsonProperty(required = true) @JsonPropertyDescription("부모 테이블 물리명") String parent,
            @JsonProperty(required = true) @JsonPropertyDescription("자식 테이블 물리명") String child) {
    }

    /** 샘플 데이터 — 테이블 하나와 넣을 행들 */
    public record SampleTable(
            @JsonPropertyDescription("테이블 물리명") String name,
            @JsonPropertyDescription("넣을 행 목록. 행 하나는 컬럼 물리명과 값의 맵이다. 값은 문자열, 숫자, 불리언, null. 날짜와 시각은 '2026-01-15', '2026-01-15 09:30:00' 같은 문자열로 적는다. 자동 증가 컬럼은 비워 두면 데이터베이스가 채운다") List<java.util.Map<String, Object>> rows) {
    }
}
