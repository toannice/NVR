package vn.aiview.nvr.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import vn.aiview.nvr.services.RecoveryService

@Serializable
data class SeedRecoveryRequest(val code: String = "AAAAA-BBBBB-CCCCC-DDDDD")

@Serializable
data class SeedRecoveryResponse(val success: Boolean, val code: String)

@Serializable
data class SeedQuestionsRequest(val items: List<QuestionItem>)

fun Route.devRouting() {
    route("/api/dev") {

        // Only active when E2E_TEST_MODE=true — returns 404 otherwise.
        // Lets the E2E suite drive the offline recovery paths without scraping a one-time code.
        post("/seed-recovery") {
            if (System.getenv("E2E_TEST_MODE") != "true") {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val data = call.receive<SeedRecoveryRequest>()
            val code = RecoveryService.normalizeCode(data.code)
            RecoveryService.persistCode(code)
            call.respond(SeedRecoveryResponse(true, code))
        }

        post("/seed-questions") {
            if (System.getenv("E2E_TEST_MODE") != "true") {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val data = call.receive<SeedQuestionsRequest>()
            val err = RecoveryService.setQuestions(data.items.map { it.questionId to it.answer })
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, LockResponse(false, err))
            } else {
                call.respond(LockResponse(true))
            }
        }
    }
}
