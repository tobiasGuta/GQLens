package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import dev.gqlens.core.GraphqlAnalyzer
import dev.gqlens.core.ObservationStore
import dev.gqlens.ui.GqlensPanel

/**
 * Burp Suite Community-compatible entry point. No scanner or Pro-only API calls.
 * The HTTP handler observes in-scope requests and never modifies or issues them.
 */
class BurpExtender : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("GQLens")
        val store = ObservationStore()
        val panel = GqlensPanel(store)
        api.userInterface().registerSuiteTab("GQLens", panel)
        api.http().registerHttpHandler(object : HttpHandler {
            override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
                if (panel.captureEnabled && request.isInScope()) {
                    try {
                        // Enforce a byte limit before materializing an HTTP request body as text.
                        if (request.body().length() <= 262_144) {
                            val observations = GraphqlAnalyzer.extract(
                                request.method(),
                                request.url(),
                                request.headerValue("Content-Type"),
                                request.bodyToString()
                            )
                            var changed = false
                            for (observation in observations) {
                                changed = store.record(observation) || changed
                            }
                            if (changed) panel.scheduleRefresh()
                        }
                    } catch (_: Exception) {
                        // Parsing must never interfere with Burp's own HTTP handling.
                    }
                }
                return RequestToBeSentAction.continueWith(request)
            }

            override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction =
                ResponseReceivedAction.continueWith(response)
        })
        api.logging().logToOutput("GQLens 0.1.0 loaded: passive, in-scope GraphQL request inventory enabled.")
    }
}
