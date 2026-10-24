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
