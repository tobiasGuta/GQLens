# GQLens

**GQLens** is a lightweight, passive GraphQL operation explorer for **Burp Suite Community Edition**. It is designed for manual, authorized bug bounty testing, not autonomous vulnerability scanning.

## v0.1.0 features

- Captures **new in-scope requests** observed by Burp (including Proxy and Repeater traffic).
- Recognizes GraphQL POST JSON, GraphQL JSON batches, POST application/graphql, URL-encoded POST, and GET query parameters.
- Parses operations and fragment spreads with graphql-java; shows actual observed field paths instead of guessing a complete schema.
- Recognizes an Apollo-style persisted-query SHA-256 hash without claiming to recover its original query.
- Search/filter by endpoint, operation, kind and field path; count repeated operations.
- Exports structural metadata as JSON. Holds data in memory, not in project storage.
- No active enumeration, scanner integration, background connections, request modification, or Pro-only APIs.

## v0.2.0-beta.2: GraphQL investigation workspace

The `feat/passive-evidence-explorer` branch contains the **v0.2.0-beta.2 prerelease** for manual testing. It includes:

- Selected-operation awareness for multi-operation GraphQL documents, including GET, JSON POST, and URL-encoded POST. Unselected operations are not recorded as if executed; ambiguous multi-operation requests are omitted.
- Passive response classification (`DATA`, `ERRORS`, `PARTIAL_DATA`, `NULL_DATA`, etc.) and HTTP status, without saving returned field values or GraphQL error messages.
- Metadata-only provenance: first/last observation time, Burp tool name, session-local message ID, and correlated response count.
- Bounded response correlation by matching Burp tool and message ID. JSON batch responses are matched to their corresponding request index where present.
- An evidence-aware explorer: searchable HTTP outcome and provenance columns, detailed metadata, and an explicit **Send selected Proxy request to Repeater** action.
- Before opening Repeater, GQLens attempts to resolve the original request from Burp's existing Proxy history and confirms its GraphQL identity and current target scope. **This never automatically sends the request.** If Burp's HTTP handler message ID cannot be resolved to a matching Proxy history item, it reports that limitation rather than guessing.

These are evidence **observations**, not security findings or proof that requested fields executed successfully. GraphQL field paths may include selections excluded at runtime by directives or type conditions. HTTP 200 is not equivalent to GraphQL operation success.

### New in beta.2

- Endpoint-first GraphQL explorer tree, nested field browser, and separate **Fields / Evidence history / Response** tabs.
- Metadata-only per-request evidence timeline with HTTP outcomes retained individually (up to 3,000 recent captures, independent of the 2,000-operation inventory bound). Older records are evicted and counted.
- Resizable split panels, column visibility controls, a reset-layout action, dynamic type/tool/result filters, and Burp's native theme application.
- Select any evidence event, including Repeater observations, to inspect/copy its session-local reference. For **Proxy** captures only, a manual button resolves the original matching history request and opens it in a new Repeater tab without sending it.
- Export format v2 adds bounded evidence metadata to operation metadata. Exports may include sensitive endpoint paths; review before sharing.
- Coalesced Swing refreshes to reduce UI churn during bursts of captured traffic.

**Important API limitation:** Montoya does not provide a supported way to jump to an existing Repeater tab by captured message ID. Repeater evidence can be inspected and its reference copied, but cannot be reopened automatically from that ID. No raw messages are retained by GQLens to work around this.

**Beta verification:** Automated tests cover operation selection, response classification/correlation, evidence retention and UI component creation. Live desktop behavior and Proxy-history message-ID resolution still need hands-on validation before a stable release.

## Download and install

1. Open [GQLens Releases](https://github.com/tobiasGuta/GQLens/releases).
2. For the original release, use **v0.1.0**. To test the latest workspace, select the **v0.2.0-beta.2 prerelease** and download **GQLens-0.2.0-beta.2.jar** from **Assets** (not the source archives).
3. In Burp Suite Community Edition, open **Extensions > Installed > Add > Java**, select the downloaded JAR, and click Next.
4. Find the **GQLens** tab, set Burp Target scope for your authorized program, and browse or manually test as normal.

GitHub Releases are the persistent download location for stable and beta artifacts. GitHub Actions build artifacts expire. Check the published SHA-256 checksum when verifying a downloaded JAR.

## Build locally

Requirements: JDK 21 and Maven 3.9+. Run:

    mvn clean verify

The installable dependency-bundled file for this branch is at **target/GQLens-0.2.0-beta.2.jar**. The Montoya API itself is *not* bundled because Burp supplies it at runtime. Other dependencies are bundled by the Maven Shade plugin.

## Safety & privacy

**Passive collection only**: GQLens does not initiate HTTP requests. Captures are limited to requests that Burp marks in scope, and can be switched off. It ignores oversized HTTP bodies, limits GraphQL parse depth and retained field count, and holds at most 2,000 distinct operation fingerprints in memory. Every entry represents observation, not proof that any field is vulnerable or even part of a complete schema.

The extension does **not** save raw request/response bodies, headers, cookies, variable values or query documents. It keeps endpoint origins/paths, operation names/kinds, observed field paths, query hash prefixes and counters. Endpoint paths and GraphQL names might still be sensitive; inspect exported data before sharing bug bounty evidence. Clear resets the in-memory inventory. Burp may separately retain HTTP history independently of GQLens.

**Limitations:** captures traffic observed after the extension was loaded, not existing history; recognizes common HTTP transport formats, not multipart uploads, GraphQL-over-WebSocket or custom protocols; does not perform introspection or brute-force fields. Repeater handoff uses previously captured Proxy traffic only, never generated queries; it does not automatically send requests.

## Why this design?

For authorized bug bounty work, evidence quality and minimal side effects matter more than large numbers of guesses. GQLens starts with reproducible, parser-backed **observations** and will add scoped, opt-in enumeration only after this foundation is validated.

## Development

- Kotlin 2.2, Java 21 bytecode
- Montoya API 2026.7 (provided)
- graphql-java 25.0, Jackson (bundled)
- Maven + JUnit 5 + GitHub Actions
- Entrypoint: burp.BurpExtender

Contributions and bug reports welcome. Use only on systems where you have permission to test.
