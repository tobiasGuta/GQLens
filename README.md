# GQLens

A **passive-first GraphQL operation explorer for Burp Suite Community Edition**, built for manual, authorized bug bounty work.

> **Status:** early development prototype. No active schema enumeration or vulnerability scanner is implemented.

## What this first milestone does

- Watches outgoing HTTP requests via the official Montoya API **without modifying or originating traffic**.
- Captures **only requests inside Burp's Target scope**, when capture is enabled.
- Recognizes GraphQL GET `query` parameters, JSON POST bodies (including batched queries), and `application/graphql` POST bodies.
- Parses GraphQL syntax into operations and nested field paths, including fragment spreads and inline fragments.
- Deduplicates operations by endpoint and SHA-256 of their source documents, counts sightings, and exposes a searchable Swing table.
- Exports metadata-only CSV. Does **not** retain headers, cookies, variables, response bodies, or raw GraphQL documents.
- Enforces request size, batch, operation, field-depth, and inventory limits.

## Build

Requires **JDK 21** and **Gradle 8.10+**. From the repository root:

```bash
gradle test shadowJar
```

Load `build/libs/GQLens-0.1.0.jar` in **Burp Suite Community > Extensions > Installed > Add > Java**.
The produced JAR includes Kotlin, Jackson, and GraphQL Java dependencies; Montoya is supplied by Burp.

## Using it safely

1. Add authorized target URLs to **Burp's Target scope** before browsing. Without scope, GQLens deliberately collects nothing.
2. Add the extension JAR and open the **GQLens** suite tab.
3. Browse the target through Burp Proxy or manually send requests using Burp tools.
4. Use the table to inspect observed operation names, types, and schema field paths. All entries are marked **Observed**; this does not establish that a field is authorized for all users.
5. Turn off capture at any time. Use **Export CSV** only when the data is appropriate to save locally.

This prototype **never** sends probes, runs mutation tests, calls Burp Scanner, accesses or saves Burp credentials, or silently saves data to disk. Capturing is **in-memory only** and clearing results destroys the local inventory.

## Scope and known limitations

- Not a schema reconstruction engine: fields are inferred only from **seen query documents**, not confirmed against an introspected schema.
- Persisted-query requests containing only hashes have no query text to analyze and are ignored.
- Multipart uploads, form-encoded GraphQL, WebSocket subscriptions, and response-only discovery are not supported yet.
- Field paths are **syntactic selections**, not type-aware schema paths; aliases are normalized to their underlying field names.
- Captured data is lost when Burp or the extension is closed unless explicitly exported.
- Burp Community compatibility is an explicit design goal, but hands-on load testing in Burp Community is still required.

## Development

```bash
gradle test
gradle shadowJar
```

GitHub Actions compiles and tests on JDK 21. New features should remain optional, scope-aware, fail-closed, and evidence-driven. No automatic mutations or generalized scanning in the passive foundation.

References: [PortSwigger Montoya API](https://github.com/PortSwigger/burp-extensions-montoya-api), [GraphQL Java](https://github.com/graphql-java/graphql-java).
