package burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.RedirectionMode
import burp.api.montoya.http.RequestOptions
import burp.api.montoya.http.message.requests.HttpRequest
import dev.gqlens.core.SchemaRecon
import dev.gqlens.core.ApiSurfaceStore
import dev.gqlens.core.ReconTrafficRegistry
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import dev.gqlens.core.GraphqlAnalyzer
import dev.gqlens.core.ObservationContext
import dev.gqlens.core.EvidenceEvent
import dev.gqlens.core.ObservationStore
import dev.gqlens.core.ResponseAnalyzer
import dev.gqlens.core.ResponseDiagnostics
import dev.gqlens.ui.GqlensPanel

/** Passive capture plus explicitly confirmed, single-request introspection in Community Edition. */
class BurpExtender : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("GQLens")
        val store = ObservationStore()
        val surface = ApiSurfaceStore()
        val ownRecon = ReconTrafficRegistry()
        val panel = GqlensPanel(
            store,
            surface,
            { event -> sendCapturedEvidenceToRepeater(api, event) },
            { endpoint, mode -> runSchemaReconOnce(api, endpoint, mode, ownRecon) }
        )
        api.userInterface().applyThemeToComponent(panel)
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
                            val tool = request.toolSource().toolType().name
                            val source = if (ownRecon.labelRequest(
                                tool, observations.firstOrNull()?.endpoint.orEmpty(),
                                observations.map { it.fingerprint }, request.messageId()
                            )) "GQLENS_RECON" else tool
                            val context = ObservationContext(source, request.messageId())
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
                    val tool = response.toolSource().toolType().name
                    val source = if (ownRecon.labelResponse(tool, response.messageId()))
                        "GQLENS_RECON" else tool
                    val context = ObservationContext(source, response.messageId())
                    if (!store.hasPending(context)) return ResponseReceivedAction.continueWith(response)
                    val shapes = if (response.body().length() > 262_144) {
                        listOf(ResponseAnalyzer.Shape.OVERSIZED)
                    } else {
                        ResponseAnalyzer.classify(response.headerValue("Content-Type"), response.bodyToString())
                    }
                    val diagnostics = if (response.body().length() > 262_144) {
                        listOf(ResponseDiagnostics.Summary(truncated = true))
                    } else {
                        ResponseDiagnostics.inspect(response.headerValue("Content-Type"), response.bodyToString())
                    }
                    if (store.recordResponse(context, response.statusCode().toInt(), shapes, diagnostics)) {
                        panel.scheduleRefresh()
                    }
                } catch (_: Exception) {
                    // Never interfere with an intercepted response.
                }
                return ResponseReceivedAction.continueWith(response)
            }
        })
        api.logging().logToOutput("GQLens: passive evidence and opt-in GraphQL API Surface explorer loaded.")
    }

    /**
     * Explicit user handoff only. Resolve the original from Burp's own Proxy history,
     * verify its parsed identity and in-scope status before opening Repeater. Neither
     * the request nor its credentials are persisted by GQLens.
     */
    private fun sendCapturedEvidenceToRepeater(api: MontoyaApi, event: EvidenceEvent): String {
        if (event.tool != "PROXY") {
            return "Only Proxy observations with a message ID can be resolved from Proxy history."
        }
        return try {
            val candidates = api.proxy().history { item -> item.id() == event.messageId }
            val matched = candidates.firstOrNull { item ->
                val request = item.finalRequest()
                request.isInScope() && request.body().length() <= 262_144 &&
                    GraphqlAnalyzer.extract(
                        request.method(), request.url(), request.headerValue("Content-Type"),
                        request.bodyToString()
                    ).any {
                        it.endpoint == event.endpoint && it.operation == event.operation &&
                            it.kind == event.kind && it.fingerprint == event.fingerprint &&
                            it.batchIndex == event.batchIndex
                    }
            } ?: return "Original matching Proxy message could not be resolved. No request was sent."
            api.repeater().sendToRepeater(matched.finalRequest(), "GQLens: ${event.operation}")
            "Opened matching captured request in Repeater. It has not been sent."
        } catch (_: Exception) {
            "Could not resolve the matching Proxy request. No request was sent."
        }
    }
    /**
     * No background discovery. This method is called only after the user explicitly
     * confirms a single introspection query in the UI. No captured auth is replayed.
     * Request is freshly built, scope-checked, redirect-disabled and time-bounded.
     */
    private fun runSchemaReconOnce(
        api: MontoyaApi, endpoint: String, mode: SchemaRecon.Mode,
        ownRecon: ReconTrafficRegistry
    ): SchemaRecon.Result {
        return try {
            val uri = java.net.URI(endpoint)
            if ((uri.scheme != "http" && uri.scheme != "https") ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null) {
                return SchemaRecon.Result(mode, null, "INVALID_ENDPOINT")
            }
            val request = HttpRequest.httpRequestFromUrl(endpoint)
                .withMethod("POST")
                .withHeader("Content-Type", "application/json")
                .withHeader("Accept", "application/graphql-response+json, application/json;q=0.9")
                .withBody(SchemaRecon.requestBody(mode))
            if (!request.isInScope()) {
                return SchemaRecon.Result(mode, null, "OUT_OF_SCOPE")
            }
            val options = RequestOptions.requestOptions()
                .withRedirectionMode(RedirectionMode.NEVER)
                .withResponseTimeout(10_000)
                .withUpstreamTLSVerification()
            ownRecon.arm(endpoint, mode)
            val exchange = try {
                api.http().sendRequest(request, options)
            } finally {
                ownRecon.disarm(endpoint, mode)
            }
            val response = if (exchange.hasResponse()) exchange.response() else null
            if (response == null) {
                SchemaRecon.Result(mode, null, "NO_RESPONSE")
            } else if (response.body().length() > SchemaRecon.MAX_RESPONSE_BYTES) {
                SchemaRecon.Result(mode, response.statusCode().toInt(), "RESPONSE_TOO_LARGE")
            } else {
                SchemaRecon.parse(mode, response.statusCode().toInt(), response.bodyToString())
            }
        } catch (_: Exception) {
            SchemaRecon.Result(mode, null, "REQUEST_FAILED")
        }
    }

}
