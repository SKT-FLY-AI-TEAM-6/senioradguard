package com.guradian.store

/**
 * 악성 URL 판정. — 이음매 (task 4가 여기에 붙는다)
 *
 * ## 크롬에서만 가능하다
 * 인스타그램·유튜브·당근에는 **URL이라는 개념이 접근성 트리에 없다.** 앱은
 * 화면을 노드로 그릴 뿐이고 주소창이 없으므로 여기에 넘길 host 자체가 존재하지
 * 않는다. 그래서 이 판정은 크롬 한정이고, 그 한계는 구현체를 아무리 좋게 만들어도
 * 사라지지 않는다. 사용자에게도 "크롬에서만"이라고 말해야 한다.
 *
 * 지금은 [EmptyMaliciousUrlSource]가 항상 false를 준다. 호출부는 이미 전부
 * 배선돼 있으므로 task 4에서 구현체 하나만 갈아끼우면 된다.
 */
interface MaliciousUrlSource {
    suspend fun isMalicious(host: String): Boolean
}

/**
 * 지금의 구현. 항상 false.
 *
 * task 4에서 KISA 공공데이터포털 기반 구현으로 교체한다. 목록을 단말에 두든
 * 서버에 물어보든 이 인터페이스 뒤에서 끝나야 한다 — **host 원문이 이 경계 밖으로
 * 나가는 설계라면 프라이버시 약속을 다시 검토할 것.**
 */
object EmptyMaliciousUrlSource : MaliciousUrlSource {
    override suspend fun isMalicious(host: String): Boolean = false
}
