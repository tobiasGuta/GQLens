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

## Development branch: evidence-linked passive explorer

The `feat/passive-evidence-explorer` branch is **unreleased**. It adds:

- Selected-operation awareness for multi-operation GraphQL documents, including GET, JSON POST, and URL-encoded POST. Unselected operations are not recorded as if executed; ambiguous multi-operation requests are omitted.
- Passive response classification (`DATA`, `ERRORS`, `PARTIAL_DATA`, `NULL_DATA`, etc.) and HTTP status, without saving returned field values or GraphQL error messages.
- Metadata-only provenance: first/last observation time, Burp tool name, session-local message ID, and correlated response count.
- Bounded response correlation by matching Burp tool and message ID. JSON batch responses are matched to their corresponding request index where present.
- An evidence-aware explorer: searchable HTTP outcome and provenance columns, detailed metadata, and an explicit **Send selected Proxy request to Repeater** action.
- Before opening Repeater, GQLens attempts to resolve the original request from Burp's existing Proxy history and confirms its GraphQL identity and current target scope. **This never automatically sends the request.** If Burp's HTTP handler message ID cannot be resolved to a matching Proxy history item, it reports that limitation rather than guessing.

These are evidence **observations**, not security findings or proof that requested fields executed successfully. GraphQL field paths may include selections excluded at runtime by directives or type conditions. HTTP 200 is not equivalent to GraphQL operation success.

**Development verification:** Unit tests cover operation selection, response shapes, matched batch responses, mismatched message IDs, and capacity bounds. Burp Community desktop loading, live correlation, and Repeater handoff still require manual validation before release.

## Download and install

1. Open [GQLens Releases](https://github.com/tobiasGuta/GQLens/releases).
2. Under **v0.1.0**, download the attached **GQLens-0.1.0.jar** directly from **Assets** (not the automatically generated source archives).
3. In Burp Suite Community Edition, open **Extensions > Installed > Add > Java**, select the downloaded JAR, and click Next.
4. Find the **GQLens** tab, set Burp Target scope for your authorized program, and browse or manually test as normal.

Releases are intended as the stable, persistent download location. For development builds, GitHub Actions also provides short-lived build artifacts.

## Build locally

Requirements: JDK 21 and Maven 3.9+. Run:

    mvn clean verify

The installable dependency-bundled file is at **target/GQLens-0.1.0.jar**. The Montoya API itself is *not* bundled because Burp supplies it at runtime. Other dependencies are bundled by the Maven Shade plugin.

## Safety & privacy

**Passive mode only** in v0.1.0: GQLens does not initiate HTTP requests. Captures are limited to requests that Burp marks in scope, and can be switched off. It ignores oversized HTTP bodies, limits GraphQL parse depth and retained field count, and holds at most 2,000 distinct operation fingerprints in memory. Every entry represents observation, not proof that any field is vulnerable or even part of a complete schema.

The extension does **not** save raw request/response bodies, headers, cookies, variable values or query documents. It keeps endpoint origins/paths, operation names/kinds, observed field paths, query hash prefixes and counters. Endpoint paths and GraphQL names might still be sensitive; inspect exported data before sharing bug bounty evidence. Clear resets the in-memory inventory. Burp may separately retain HTTP history independently of GQLens.

**Limitations:** captures traffic observed after the extension was loaded, not existing history; recognizes common HTTP transport formats, not multipart uploads, GraphQL-over-WebSocket or custom protocols; does not perform introspection, brute-force fields, or build queries for Repeater yet.

## Why this design?

For authorized bug bounty work, evidence quality and minimal side effects matter more than large numbers of guesses. GQLens starts with reproducible, parser-backed **observations** and will add scoped, opt-in enumeration only after this foundation is validated.

## Development

- Kotlin 2.2, Java 21 bytecode
- Montoya API 2026.7 (provided)
- graphql-java 25.0, Jackson (bundled)
- Maven + JUnit 5 + GitHub Actions
- Entrypoint: burp.BurpExtender

Contributions and bug reports welcome. Use only on systems where you have permission to test.
