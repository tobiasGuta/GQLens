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

## v0.2.0-beta.3: controlled schema recon (new)

This prerelease builds upon the beta.2 evidence explorer and remains **passive by default**.

- **Explore by host > endpoint path > observed operation**, instead of one long URL in the left tree.
- **Compact operation overview** summarizes request count, matched responses, and last HTTP/GraphQL result.
- **Response comparison** shows a distribution of HTTP statuses and GraphQL structural outcomes across the most recent retained evidence records.
- **Recon (opt-in)** adds an explicit, single-request-per-confirmation GraphQL introspection utility for an endpoint already observed and currently within Burp Target scope.
- Recon modes: **Root operations** lists declared query/mutation/subscription root field names; **Schema types and fields** reads GraphQL introspection type and field metadata. Both use standard schema reflection, not guessed application field names or a hardcoded list of application operations.
- The extension constructs a **fresh unauthenticated POST** request with GraphQL-over-HTTP content negotiation. It does not replay captured cookies, authorization headers, variables, or session data.
- Recon enforces the Burp scope check immediately before sending, **does not follow redirects**, waits up to 10 seconds for a response, does not retry automatically, and rejects responses larger than 512 KiB. It does not perform mutation execution, brute forcing, validation-error harvesting, or automatic crawling.
- Recon parses only structural type and field names into in-memory UI state and limits types/fields retained. No raw body or error messages are saved.
- Schema introspection may be disabled or restricted; **absence of schema data does not imply a vulnerability or a lack of GraphQL support**.

**Testing status:** Automated Maven and headless Swing tests cover the new introspection parsing and workspace components. Manual testing inside Burp Community is still required before treating this as a stable release. Only send recon requests where the test program explicitly permits them.

## v0.2.0-beta.4: Unified API Surface Explorer

The `feat/unified-api-surface` feature branch builds on beta.3 with a visually organized, Burp-native **API Surface** tab.

- **Endpoint-aware reconciliation:** server-declared GraphQL root fields are compared against root selections in real, captured GraphQL request documents. Root type names are taken from the server's introspection results rather than assumed to be `Query` or `Mutation`.
- **Evidence terminology:** `Declared + requested`, `Declared only`, `Requested only`, or `GraphQL meta-field`. None of these proves execution, successful access or authorization.
- **Burp-native workspace:** prominent Declared/Requested/Matched/Schema Types metric cards; searchable root and type explorer; status filters; field detail panel; resizable panes; structural JSON export.
- **Schema history:** keep the latest and immediately preceding successful introspection snapshot for each mode/endpoint; compare additions and removals; suppress removal claims if a bounded response was truncated. Up to 64 endpoint histories, in memory.
- **Cleaner traffic:** the extension recognizes its own explicitly initiated introspection using a short-lived expected-query fingerprint and Burp's `EXTENSIONS` source. Those observations are hidden in the normal operation tree by default, with a `Show GQLens recon` checkbox to view them. Matching manually sent Repeater requests are not relabeled.
- **Query preview:** see the full GraphQL introspection document before you confirm the single, unauthenticated in-scope request.
- **Privacy:** retain structural schema metadata only. No response objects, error messages, cookies, credentials or captured raw HTTP messages are saved. Clearing GQLens also clears in-memory schema snapshots.

**Matching limitation:** Observed root selections can be linked to their declared root type; nested field paths are **not** matched to arbitrary type names without schema-aware traversal, because name-only matching would create false evidence.

**Testing:** Maven CI exercises schema/traffic correlation, unconventional root type names, implicit meta-fields, extension attribution, snapshot differences and Swing composition. Interactive Burp Community verification of the new layout, filters and clear behavior is still required before a stable merge.

## v0.2.0-beta.5: typed schema explorer and query draft builder

This prerelease builds on beta.4 and improves the schema view for manual GraphQL investigations.

- **Readable type signatures:** Introspection now collects declared field return types and argument types, including GraphQL lists, non-null wrappers, required inputs and whether an argument has a default value. Names and types come from the server, never from wordlists.
- **Better native UI:** The API Surface tree has more readable font sizing, greater row spacing, differentiated matched/declared rows and structured argument/return-type details. Burp's look-and-feel remains the source of interface colors.
- **Query builder (read-only):** Select a declared QUERY root field with a complete introspected return type. GQLens creates a syntactically parsed GraphQL query using only mandatory argument variables and an appropriate leaf selection (scalar/enum) or `__typename` selection (object/interface/union).
- **Variables are never guessed:** Required variable names and types are listed for manual completion. No argument values, credentials or response values are produced.
- **Copy-only workflow:** You can inspect and copy the generated document to the clipboard. **The builder never sends HTTP requests, opens a Repeater tab, or executes a mutation.** Active schema discovery remains a separate, explicitly confirmed one-request action.
- **Metric correction:** Implicit GraphQL meta-fields such as `__typename` no longer inflate the count of application root fields requested.
- **Regression testing:** GraphQL syntax/variable behavior, scalar-versus-object selection, nested type wrappers, defaulted inputs and Swing tab composition are covered by automated tests.

**Compatibility:** The schema type signature data is only available for new introspection captured with beta.5. Reloading the extension clears the previous in-memory schema, so run the permitted introspection request again to populate it. If an endpoint does not return type/argument metadata, the builder stays disabled rather than inventing a valid query.

**Limits:** A generated document is a manual review aid, not proof of access, authorization or even an executable operation without actual variable values. Deep or restricted introspection responses may be incomplete. Interactive Burp Community visual testing is still needed before merging.

## Download and install

1. Open [GQLens Releases](https://github.com/tobiasGuta/GQLens/releases).
2. For the original release, use **v0.1.0**. To test the latest workspace, select the **v0.2.0-beta.5 prerelease** and download **GQLens-0.2.0-beta.5.jar** from **Assets** (not the source archives).
3. In Burp Suite Community Edition, open **Extensions > Installed > Add > Java**, select the downloaded JAR, and click Next.
4. Find the **GQLens** tab, set Burp Target scope for your authorized program, and browse or manually test as normal.

GitHub Releases are the persistent download location for stable and beta artifacts. GitHub Actions build artifacts expire. Check the published SHA-256 checksum when verifying a downloaded JAR.

## Build locally

Requirements: JDK 21 and Maven 3.9+. Run:

    mvn clean verify

The installable dependency-bundled file for this branch is at **target/GQLens-0.2.0-beta.5.jar**. The Montoya API itself is *not* bundled because Burp supplies it at runtime. Other dependencies are bundled by the Maven Shade plugin.

## Safety & privacy

**Passive collection with optional explicit recon**: GQLens never initiates requests in the background. Its separate Recon tab can send one standards-based introspection request only after confirmation. Captures are limited to requests that Burp marks in scope, and can be switched off. It ignores oversized HTTP bodies, limits GraphQL parse depth and retained field count, and holds at most 2,000 distinct operation fingerprints in memory. Every entry represents observation, not proof that any field is vulnerable or even part of a complete schema.

The extension does **not** save raw request/response bodies, headers, cookies, variable values or query documents. It keeps endpoint origins/paths, operation names/kinds, observed field paths, query hash prefixes and counters. Endpoint paths and GraphQL names might still be sensitive; inspect exported data before sharing bug bounty evidence. Clear resets the in-memory inventory. Burp may separately retain HTTP history independently of GQLens.

**Limitations:** captures traffic observed after the extension was loaded, not existing history; recognizes common HTTP transport formats, not multipart uploads, GraphQL-over-WebSocket or custom protocols; does not automatically perform introspection or brute-force fields. Repeater handoff uses previously captured Proxy traffic only, never generated queries; it does not automatically send requests.

## Why this design?

For authorized bug bounty work, evidence quality and minimal side effects matter more than large numbers of guesses. GQLens starts with reproducible, parser-backed **observations** and will add scoped, opt-in enumeration only after this foundation is validated.

## Development

- Kotlin 2.2, Java 21 bytecode
- Montoya API 2026.7 (provided)
- graphql-java 25.0, Jackson (bundled)
- Maven + JUnit 5 + GitHub Actions
- Entrypoint: burp.BurpExtender

Contributions and bug reports welcome. Use only on systems where you have permission to test.
