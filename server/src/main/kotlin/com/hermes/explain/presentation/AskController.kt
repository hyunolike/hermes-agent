package com.hermes.explain.presentation

import com.hermes.explain.BackendFacts
import com.hermes.explain.CourseQuestionService
import com.hermes.explain.Explained
import com.hermes.explain.ExplanationUnavailableException
import com.hermes.explain.QuestionTurn
import com.hermes.explain.Unavailable
import com.hermes.facts.FactsSource
import com.hermes.facts.HanjeokUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AskTurn(val question: String, val answer: String)

/**
 * 기본값을 두지 않는다. 모든 파라미터에 기본값이 있으면 Kotlin 이 생성자를 여러 개
 * 만들고, 웹 계층의 Jackson 이 어느 것을 쓸지 정하지 못해 본문 파싱 자체가 실패한다
 * (실측: 모든 요청이 400 INVALID_REQUEST). 없어도 되는 것은 nullable 로 둔다.
 */
data class AskRequest(
    val courseUuid: String,
    val question: String,
    val history: List<AskTurn>?,
)

data class AskResponse(
    val answer: String,
    val citations: List<String>,
    val generatedAt: String,
    val model: String,
)

/** 400 으로 끝나야 하는 클라이언트 실수. 503 으로 내면 재시도 폭풍을 부른다. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidAskRequestException(message: String) : RuntimeException(message)

/**
 * 코스에 대해 이어 묻는다.
 *
 * **사실은 여전히 백엔드에서만 온다.** 클라이언트는 `courseUuid` 와 질문만 보내고,
 * 사실을 실어 보낼 수 없다 — 그 통로가 열리면 위조된 혼잡도를 모델이 그럴듯하게
 * 설명해 주는 경로가 생긴다(스펙 §3).
 *
 * **대화는 클라이언트가 들고 있다.** 서버는 저장하지 않는다. 저장소가 생기면
 * 보존 기간과 삭제가 따라오고, 이 설계의 "DB 없음" 전제가 깨진다.
 */
@RestController
class AskController(
    private val source: FactsSource,
    private val service: CourseQuestionService,
    @param:Value("\${hermes.llm.model}") private val model: String,
) {

    private val log = LoggerFactory.getLogger(AskController::class.java)

    @PostMapping("/agent/ask")
    fun ask(@RequestBody request: AskRequest): AskResponse {
        if (request.courseUuid.isBlank()) throw InvalidAskRequestException("courseUuid is blank")
        // 빈 질문을 모델에게 보내면 돈을 쓰고 아무 질문에도 답하지 않은 답을 받는다.
        if (request.question.isBlank()) throw InvalidAskRequestException("question is blank")

        val facts = try {
            source.fetch(request.courseUuid)
        } catch (e: HanjeokUnavailableException) {
            log.warn("facts unavailable for course {}", request.courseUuid, e)
            throw ExplanationUnavailableException("facts: ${e.message}")
        }

        val history = request.history.orEmpty().map { QuestionTurn(it.question, it.answer) }

        return when (val outcome = service.ask(BackendFacts(facts.courseUuid, facts.json), request.question, history)) {
            is Explained -> AskResponse(
                answer = outcome.explanation.explanation,
                citations = outcome.explanation.citations,
                generatedAt = Instant.now().toString(),
                model = model,
            )
            is Unavailable -> {
                log.warn("answer unavailable for course {}: {}", request.courseUuid, outcome.reason)
                throw ExplanationUnavailableException(outcome.reason)
            }
        }
    }
}
