package io.web.auth

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import spray.json.*

object ErrorResponses {

    def errorJson(code: String, message: String): HttpEntity.Strict =
        HttpEntity(
            ContentTypes.`application/json`,
            JsObject("error" -> JsString(code), "message" -> JsString(message)).compactPrint
        )

    def usageLimitJson(message: String, limit: Int, requested: Int): HttpEntity.Strict =
        HttpEntity(
            ContentTypes.`application/json`,
            JsObject(
                "error"     -> JsString("usage_limit_exceeded"),
                "message"   -> JsString(message),
                "limit"     -> JsNumber(limit),
                "requested" -> JsNumber(requested)
            ).compactPrint
        )
}