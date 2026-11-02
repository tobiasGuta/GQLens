package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import dev.gqlens.core.GraphqlAnalyzer
import dev.gqlens.core.ObservationContext
import dev.gqlens.core.ObservationRow
import dev.gqlens.core.ObservationStore
import dev.gqlens.core.ResponseAnalyzer
import dev.gqlens.ui.GqlensPanel

/** Passive Burp Suite Community extension. It never issues or mutates HTTP traffic. */
class BurpExtender : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("GQLens")
        val store = ObservationStore()
        val panel = GqlensPanel(store) { row -> sendCapturedEvidenceToRepeater(api, row) }
        api.userInterface().registerSuiteTab("GQLens", panel)

        api.http().registerHttpHandler(object : HttpHandler {
            override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
                if (panel.captureEnabled && request.isInScope()) {
                    try {
                        if (request.body().length() <= 262_144) {
                            val observations = GraphqlAnalyzer.extract(
                                request.method(), request.url(),
                                request.headerValue("Content-Type"), request.bodyToString()
                            )
                            val context = ObservationContext(
                                request.toolSource().toolType().name,
                                request.messageId()
                            )
                            for (observation in observations) store.record(observation, context)
                            if (observations.isNotEmpty()) panel.scheduleRefresh()
                        }
                    } catch (_: Exception) {
                        // Malformed traffic must never interrupt Burp's HTTP pipeline.
                    }
                }
                return RequestToBeSentAction.continueWith(request)
            }

            override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
                try {
                    // Only the matching, previously accepted request can update the store.
                    // Do not persist raw response data, headers or GraphQL error messages.
                    val shapes = if (response.body().length() > 262_144) {
                        listOf(ResponseAnalyzer.Shape.OVERSIZED)
                    } else {
                        ResponseAnalyzer.classify(response.headerValue("Content-Type"), response.bodyToString())
                    }
                    val context = ObservationContext(
                        response.toolSource().toolType().name,
                        response.messageId()
                    )
                    if (store.recordResponse(context, response.statusCode().toInt(), shapes)) {
                        panel.scheduleRefresh()
                    }
                } catch (_: Exception) {
                    // Never interfere with an intercepted response.
                }
                return ResponseReceivedAction.continueWith(response)
            }
        })
        api.logging().logToOutput("GQLens: passive GraphQL evidence explorer loaded.")
    }

    /**
     * Explicit user handoff only. Resolve the original from Burp's own Proxy history,
     * verify its parsed identity and in-scope status before opening Repeater. Neither
     * the request nor its credentials are persisted by GQLens.
     */
    private fun sendCapturedEvidenceToRepeater(api: MontoyaApi, row: ObservationRow): String {
        if (row.lastTool != "PROXY" || row.lastMessageId == null) {
            return "Only Proxy observations with a message ID can be resolved from Proxy history."
        }
        return try {
            val candidates = api.proxy().history { item -> item.id() == row.lastMessageId }
            val matched = candidates.firstOrNull { item ->
                val request = item.finalRequest()
                request.isInScope() && request.body().length() <= 262_144 &&
                    GraphqlAnalyzer.extract(
                        request.method(), request.url(), request.headerValue("Content-Type"),
                        request.bodyToString()
                    ).any {
                        it.endpoint == row.endpoint && it.operation == row.operation &&
                            it.kind == row.kind && it.fingerprint == row.fingerprint
                    }
            } ?: return "Original matching Proxy message could not be resolved. No request was sent."
            api.repeater().sendToRepeater(matched.finalRequest(), "GQLens: ${row.operation}")
            "Opened matching captured request in Repeater. It has not been sent."
        } catch (_: Exception) {
            "Could not resolve the matching Proxy request. No request was sent."
        }
    }
}
