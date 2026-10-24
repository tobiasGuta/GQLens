package dev.tobiasguta.gqlens

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import java.util.concurrent.atomic.AtomicBoolean

class GQLensExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("GQLens")
        val inventory = OperationInventory()
        val captureEnabled = AtomicBoolean(true)
        val panel = GQLensPanel(inventory, captureEnabled)
        api.userInterface().registerSuiteTab("GQLens", panel)

        api.http().registerHttpHandler(object : HttpHandler {
            override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
                // Always return the unmodified request, even when parsing fails.
                try {
                    if (captureEnabled.get() && request.isInScope()
                        && (request.method() == "GET" || request.method() == "POST")) {
                        // Do not copy unusually large bodies into a String.
                        if (request.body().length <= 256 * 1024) {
                            val endpoint = OperationInventory.safeEndpoint(request.url())
                            if (endpoint != null) {
                                val documents = GraphQlTransport.extract(
                                    request.method(), request.path(),
                                    request.headerValue("Content-Type") ?: "",
                                    request.bodyToString()
                                )
                                for (document in documents) {
                                    GraphQlAnalyzer.analyze(document).forEach { inventory.add(endpoint, it) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Malformed or unsupported traffic must not interfere with Burp.
                }
                return RequestToBeSentAction.continueWith(request)
            }

            override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction =
                ResponseReceivedAction.continueWith(response)
        })

        api.extension().registerUnloadingHandler {
            captureEnabled.set(false)
            panel.shutdown()
            inventory.clear()
        }
        api.logging().logToOutput("GQLens loaded: passive capture only, restricted to Burp Target scope.")
    }
}
