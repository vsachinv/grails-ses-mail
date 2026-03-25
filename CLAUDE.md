# CLAUDE.md — grails-ses-mail

This file describes the project structure, conventions, skills, and working rules
for AI-assisted development on this codebase.

---

## Project overview

`grails-ses-mail` is a **Grails 6 plugin** that routes outbound mail through the
**AWS SES v2 API** (`SendRawEmail`).  It replaces the `mailSender` Spring bean
that both `grails-mail` and `asynchronous-mail` depend on, with zero code changes
required in the consuming application.

SMTP transport is intentionally out of scope — `grails-mail` handles that natively.
This plugin's sole responsibility is the native AWS API transport path.

---

## Tech stack

| Layer             | Technology                                      |
|-------------------|-------------------------------------------------|
| Language          | Groovy 4 (JDK 11)                               |
| Framework         | Grails 6.2.0 (Spring Boot 3 / Micronaut DI)     |
| Build             | Gradle 8 via `org.grails.grails-web` plugin     |
| AWS SDK           | AWS SDK for Java v2 — `sesv2` + `auth` modules  |
| Mail abstraction  | Spring `JavaMailSender` / `MimeMessageHelper`   |
| Test framework    | Spock Framework (JUnit 5 platform)              |
| Dependency scope  | `grails-mail:3.0.0` and `asynchronous-mail:3.1.2` as `compileOnly` |

---

## Repository layout

```
grails-ses-mail/
├── build.gradle                        Gradle build — deps, publish config
├── gradle.properties                   Versions: Grails 6.2.0, plugin 1.0.0
├── settings.gradle                     Root project name
├── README.md                           User-facing documentation
├── CLAUDE.md                           This file
│
├── grails-app/
│   ├── conf/
│   │   └── plugin.groovy               Default config values (grails.mail.ses.*)
│   ├── controllers/
│   │   ├── SesMailUrlMappings.groovy   Auto-registers POST /ses/sns
│   │   └── SesSnsHandlerController.groovy  SNS webhook receiver
│   └── services/
│       └── SesBounceSuppressionService.groovy  In-memory suppression list
│
└── src/
    ├── main/groovy/grails/plugins/mail/aws/
    │   ├── GrailsSesMailGrailsPlugin.groovy  Spring bean wiring (doWithSpring)
    │   ├── SesMailConfiguration.groovy       Typed config POGO
    │   ├── SesApiMailSender.groovy           JavaMailSender → SES SendRawEmail
    │   ├── SnsSignatureVerifier.groovy       RSA signature verification for SNS
    │   └── SuppressedAddress.groovy          Value object for suppressed addresses
    └── test/groovy/grails/plugins/mail/aws/
        ├── SesApiMailSenderSpec.groovy
        ├── SesBounceSuppressionServiceSpec.groovy
        └── SnsSignatureVerifierSpec.groovy
```

---

## Key classes and their responsibilities

### `GrailsSesMailGrailsPlugin`
- Entry point for the Grails plugin lifecycle.
- `loadAfter = ['mail', 'asynchronous-mail']` — ensures this plugin's
  `doWithSpring` runs last so its `mailSender` bean definition wins.
- Always registers `SnsSignatureVerifier` regardless of `ses.enabled`.
- Only overrides `mailSender` when `grails.mail.ses.enabled = true`.
- Config is read from `grails.mail.ses.*` via `application.config`.

### `SesMailConfiguration`
- Plain Groovy value object (`@CompileStatic`, `@ToString`).
- Fields: `region`, `accessKey`, `secretKey`, `defaultFrom`, `configurationSetName`.
- Populated in `doWithSpring` and injected into `SesApiMailSender`.
- `secretKey` is excluded from `@ToString` output.

### `SesApiMailSender`
- Implements Spring's `JavaMailSender` interface.
- `afterPropertiesSet()` delegates client construction to `buildSesClient()`.
- `buildSesClient()` is `protected` — overridable in tests without touching
  the real AWS credential chain.
- All six `send()` overloads funnel through `sendRaw(MimeMessage)`.
- `sendRaw()` serialises the `MimeMessage` to bytes and calls `SesV2Client.sendEmail()`.
- Error handling: `SesV2Exception` is caught; `errorCode()` is inspected to
  give a targeted message for `ConfigurationSetDoesNotExist`; all others
  produce a generic `MailSendException` that includes the error code.
- Implements `DisposableBean` — closes `SesV2Client` on shutdown.

### `SnsSignatureVerifier`
- Verifies the RSA signature AWS SNS attaches to every inbound HTTP POST.
- Uses only the Java standard library (`java.security`) — no extra dependency.
- `validateCertUrl()` guards against cert-substitution attacks by enforcing
  the `https://sns.*.amazonaws.com/*.pem` URL pattern before fetching.
- Caches fetched `PublicKey` objects in a `ConcurrentHashMap` for the
  application lifetime.
- `buildSigningString()` is `protected static` (not `private`) so specs
  can call it directly without reflection.
- `verifySignature = false` disables all checks — dev/test only.
- Throws `SignatureVerificationException` (an inner `RuntimeException` subclass)
  for every failure path.

### `SesSnsHandlerController`
- Single action: `handleSnsEvent` (POST only via `allowedMethods`).
- Action name is `handleSnsEvent` — **not** `notify`, which is a reserved
  method on `java.lang.Object`.
- Pipeline: parse JSON → verify signature → route by `Type` field.
- Handles `SubscriptionConfirmation`, `Notification` (Bounce / Complaint /
  Delivery), `UnsubscribeConfirmation`.
- `SubscriptionConfirmation`: validates `SubscribeURL` is an AWS domain before
  following it.
- Bounce suppression: only `Permanent` bounces are suppressed; `Transient`
  bounces are logged but not suppressed.
- Complaint suppression: all complaints suppress regardless of feedback type.

### `SesMailUrlMappings`
- Ships with the plugin; Grails merges it automatically.
- Registers exactly one route: `POST /ses/sns → sesSnsHandler#handleSnsEvent`.
- Fixed action binding — no `$action` wildcard, so no unintended action exposure.

### `SesBounceSuppressionService`
- `static transactional = false`.
- In-memory `ConcurrentHashMap` store.
- `addSuppressListener(Closure)` — hook for persistence without subclassing.
- `filterSuppressed(List<String>)` — returns only non-suppressed addresses.
- All address comparisons are case-insensitive (normalised to lowercase).

### `SuppressedAddress`
- `@CompileStatic` value object.
- Fields: `emailAddress`, `suppressionType`, `subtype`, `suppressedAt`, `reason`.
- Not a GORM domain class — no persistence dependency in this plugin.

---

## Configuration keys

All under `grails.mail.ses.*`:

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `enabled` | Boolean | `false` | Master switch |
| `region` | String | `"us-east-1"` | SES region |
| `accessKey` | String | `""` | Leave blank for credential chain |
| `secretKey` | String | `""` | Leave blank for credential chain |
| `defaultFrom` | String | `""` | Falls back to `grails.mail.default.from` |
| `configurationSetName` | String | `""` | Attach a SES Configuration Set |
| `sns.verifySignature` | Boolean | `true` | Disable only in dev/test |

---

## Dependency scoping rules

| Dependency | Scope | Reason |
|------------|-------|--------|
| `grails-mail` | `compileOnly` | Consuming app provides it; we must not ship a second copy |
| `asynchronous-mail` | `compileOnly` | Optional; detected at runtime via `loadAfter` |
| `spring-boot-starter-mail` | `implementation` | Provides `JavaMailSender` types we implement |
| `sesv2` + `auth` (AWS SDK v2) | `implementation` | Core transport — always required |
| `aws-core` (transitive via `sesv2`) | — | Provides `AwsErrorDetails` used in tests |

---

## Test conventions

- Test framework: **Spock** on JUnit 5.
- No real AWS credentials or network access in any test.
- `SesV2Client` is always a Spock `Mock`.
- `SesApiMailSender` is subclassed anonymously in the spec to override
  `buildSesClient()` returning the mock — preventing `DefaultCredentialsProvider`
  from running during `afterPropertiesSet()`.
- `SnsSignatureVerifier` tests that require network (cert fetch) are written
  to assert the error is a cert-fetch failure, not a domain-validation failure,
  proving URL validation passed.
- `SesBounceSuppressionService` uses `ServiceUnitTest<SesBounceSuppressionService>`
  mixin from `grails-gorm-testing-support`.
- Test files live under `src/test/groovy/grails/plugins/mail/aws/` and use the
  `*Spec.groovy` naming convention.

### Running tests

```bash
./gradlew test
# HTML report: build/reports/tests/test/index.html
```

---

## Build and publish

```bash
# Run tests
./gradlew test

# Build JAR + sources + groovydoc
./gradlew clean build

# Install to local Maven cache (for testing in a consuming app)
./gradlew publishToMavenLocal

# Publish to Nexus
./gradlew publish \
    -PnexusUsername=<user> \
    -PnexusPassword=<pass> \
    -PnexusUrl=<url>

# Or via environment variables
export NEXUS_USERNAME=<user> NEXUS_PASSWORD=<pass> NEXUS_URL=<url>
./gradlew clean build publish
```

Published coordinates: `org.grails.plugins:grails-ses-mail:1.0.0`

---

## Coding conventions

- **Groovy style**: `@Slf4j` for logging; `log.info/debug/warn/error` with
  SLF4J-style `{}` placeholders — never string interpolation in log calls.
- **Imports**: no wildcard imports; every import is explicit.
- **Null safety**: use `?.` and `?:` operators; validate required fields in
  `afterPropertiesSet()` / `InitializingBean`.
- **Exception wrapping**: always wrap unexpected exceptions in Spring's
  `MailSendException` (which extends `MailException` → `RuntimeException`),
  preserving the original cause.
- **Access modifiers**: `private` for implementation details; `protected` when
  a method must be overridable in tests; `public` (default) only for the
  documented API surface.
- **No GORM dependency**: this plugin must not introduce any GORM/Hibernate
  domain class. Persistence is the consuming application's concern.
- **No SMTP code**: SMTP is handled by `grails-mail`. Any SMTP-related code
  in this plugin is wrong and should be removed.

---

## Common mistakes to avoid

| Mistake | Correct approach |
|---------|-----------------|
| Catching `ConfigurationSetDoesNotExistException` | Does not exist in SDK v2. Catch `SesV2Exception` and check `errorCode()` |
| Multi-catch with an unresolvable type | Resolve all types before using `\|` in catch |
| Using `notify` as a controller action name | Reserved by `java.lang.Object`. Use `handleSnsEvent` |
| `$action` wildcard in SNS URL mapping | Exposes all actions. Bind to a fixed action name |
| Calling `buildSesClient()` directly in tests | Override `buildSesClient()` in an anonymous subclass |
| `private static` method called from a test class | Use `protected static` for methods tested directly |
| `includeIfNull` logic for optional SNS fields | Absent fields are simply skipped by the null-check in `buildString()` |
| Adding SMTP config keys to `SesMailConfiguration` | This plugin is API-only. SMTP config belongs in `grails-mail` |
| `sender.@sesClient = mockSesClient` private field access | Override `buildSesClient()` instead |

---

## AWS SNS signature verification — how it works

1. Every SNS HTTP POST carries `SigningCertURL`, `Signature`, and `Type` fields.
2. `SnsSignatureVerifier.verify()` is called before any payload processing.
3. `validateCertUrl()` ensures `SigningCertURL` is `https://sns.*.amazonaws.com/*.pem`.
4. The X.509 certificate is fetched (once, then cached).
5. A canonical string is built from message fields in the AWS-specified order
   via `buildSigningString()`.
6. The Base64-decoded `Signature` is verified against the canonical string
   using `SHA1withRSA` and the certificate's public key.
7. On any failure a `SignatureVerificationException` is thrown →
   controller returns HTTP 403.

The plugin does **not** sign outbound mail. AWS SES handles DKIM signing
internally for outbound messages when sending identities are configured.

---

## Extending the plugin

### Persistent bounce suppression

```groovy
// In BootStrap.groovy of the consuming application
class BootStrap {
    SesBounceSuppressionService sesBounceSuppressionService

    def init = { servletContext ->
        sesBounceSuppressionService.addSuppressListener { SuppressedAddress addr ->
            new MySuppressionRecord(
                email : addr.emailAddress,
                type  : addr.suppressionType,
                reason: addr.reason
            ).save(flush: true)
        }
    }
}
```

### Custom suppression service

Declare a subclass as a Spring bean named `sesBounceSuppressionService` in the
consuming application's `resources.groovy` or `doWithSpring` to replace the
default in-memory implementation entirely.

### Overriding the SNS endpoint path

If `/ses/sns` conflicts with existing routes, declare your own mapping:

```groovy
// In your application's UrlMappings.groovy
"/hooks/aws/ses"(controller: 'sesSnsHandler', action: 'handleSnsEvent', method: 'POST')
```

Then update the SNS subscription URL in the AWS Console to match.
