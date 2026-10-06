package net.java21.crowfoot.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 입력 규칙 요약 — get_design_context가 돌려준다. 원천은 08-core/17-model-edit.md Section 4.1·4.2다.
 * 판정은 core가 한다. 여기는 MCP 클라이언트가 처음부터 맞게 쓰도록 알려 주는 글이다.
 */
final class DesignRules {

    static final Map<String, Object> RULES = rules();

    private DesignRules() {
    }

    private static Map<String, Object> rules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("physicalName", "테이블·컬럼 물리명은 소문자로 시작하고 소문자·숫자·밑줄만 쓴다(정규식 ^[a-z][a-z0-9_]{0,62}$). "
                + "테이블 물리명은 문서 안에서, 컬럼 물리명은 테이블 안에서 유일하다.");
        rules.put("dataTypes", List.of("INT", "BIGINT", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "CHAR", "VARCHAR",
                "TEXT", "MEDIUMTEXT", "LONGTEXT", "BOOLEAN", "DATE", "TIME", "DATETIME", "TIMESTAMP", "JSON", "UUID", "BLOB",
                "BINARY", "VARBINARY"));
        rules.put("dataTypeNote", "공용 타입 코드만 쓴다. DBMS의 물리 표기(VARCHAR2, TINYINT(1), TIMESTAMPTZ)를 넣지 않는다 — Crowfoot이 대상 DBMS에 맞게 바꾼다. "
                + "length는 CHAR·VARCHAR·BINARY·VARBINARY에만, precision·scale은 DECIMAL·NUMERIC에만 넣는다. "
                + "TIME·DATETIME·TIMESTAMP의 소수 초 자릿수(0~6)는 precision에 넣는다(scale 없음). "
                + "VARCHAR·VARBINARY에는 length를 넣는다(PostgreSQL 문서만 생략할 수 있다). "
                + "타임존 없는 시각은 DATETIME, UTC 순간은 TIMESTAMP다.");
        rules.put("defaultValue", "문자열 기본값은 따옴표 없이 쓴다(ACTIVE). Crowfoot이 DDL에서 컬럼 타입을 보고 따옴표를 붙인다. "
                + "식 기본값은 괄호로 감싸거나(uuid()) 함수 꼴로 쓴다(CURRENT_TIMESTAMP(6)).");
        rules.put("constraints", "CHECK 제약은 테이블의 checks에 {name, expression}으로, 생성 컬럼은 컬럼의 generated에 {expression, stored}로, "
                + "행을 고칠 때 자동으로 넣는 값(MySQL ON UPDATE)은 컬럼의 onUpdate에 쓴다. 식은 SQL 원문이다. "
                + "전문 검색 인덱스는 indexes의 type을 FULLTEXT로 하고 MySQL 파서가 있으면 parser(ngram 등)를 쓴다.");
        rules.put("primaryKey", "기본 키 컬럼은 NOT NULL이 되고 맨 위에 놓인다. autoIncrement는 정수 타입의 단일 컬럼 기본 키에만 쓴다.");
        rules.put("relationships", "관계는 항상 부모(참조되는 쪽) → 자식(외래 키를 갖는 쪽)이다. 부모에 기본 키가 있어야 한다. "
                + "외래 키 컬럼은 직접 만들지 않는다 — 관계를 만들면 자식 테이블에 {부모 테이블}_{부모 기본 키 컬럼} 이름으로 생긴다. "
                + "N:M은 연결 테이블을 만들고 1:N 관계 두 개로 표현한다. 두 테이블 사이의 관계는 하나만 둔다.");
        rules.put("generated", "기본 키 이름, 유니크 키·인덱스 이름, 외래 키 제약 이름, 외래 키 인덱스, 테이블 위치는 Crowfoot이 만든다. 입력하지 않는다.");
        rules.put("requirements", "요구사항은 기능 단위로 쓰고 도메인(그룹)을 붙인다. 업무 규칙은 그 기능의 내용에 적는다. "
                + "문서 전체에 적용되는 규칙은 scope=document인 공통 요구사항으로 등록한다. 모든 테이블에 근거 요구사항을 연결한다.");
        rules.put("areaColors", List.of("default", "red", "orange", "amber", "yellow", "green", "teal", "sky", "blue", "violet", "pink"));
        return rules;
    }
}
