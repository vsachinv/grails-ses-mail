# grails-ses-mail

A Grails plugin that routes outbound mail through the **AWS SES v2 API**
(`SendRawEmail`), acting as a drop-in replacement for the `mailSender` Spring
bean that both [grails-mail](https://grails.org/plugins.html#plugin/mail) and
[asynchronous-mail](https://github.com/gpc/grails-asynchronous-mail) depend on.

SMTP transport is **not** handled by this plugin – grails-mail covers that out
of the box.  This plugin's only concern is native AWS API delivery.

---

## How it works

Both grails-mail and asynchronous-mail look up a Spring bean named **`mailSender`**
at runtime.  This plugin loads *after* both (`loadAfter = ['mail', 'asynchronous-mail']`)
and, when `grails.mail.ses.enabled = true`, its `doWithSpring` closure registers
`SesApiMailSender` under that same bean name, overriding the default sender.

```
Your service / controller
        │  sendMail { ... }
        ▼
grails-mail MailService          (or AsynchronousMailService if async-mail is present)
        │
        ▼  resolves 'mailSender' bean at send time
        │
  ┌─────┴──────────────────────────────────────┐
  │  ses.enabled = false  →  default mailSender │  (grails-mail SMTP, unchanged)
  │  ses.enabled = true   →  SesApiMailSender   │  (AWS SES v2 API)
  └────────────────────────────────────────────┘
```

`SesApiMailSender` serialises the fully-assembled `MimeMessage` (as built by
grails-mail's DSL) to raw bytes and submits it via `SesV2Client.sendEmail()`.
This preserves every MIME feature: HTML/text multipart, attachments, inline
images, custom headers, CC/BCC.

No changes are required in any service, controller, or configuration beyond
enabling the plugin and supplying AWS credentials.

---

## Requirements

| Dependency | Version |
|------------|---------|
| Grails     | 6.2.0+  |
| JDK        | 11+     |
| grails-mail | 3.0.0+ |
| asynchronous-mail | 3.1.2+ *(optional)* |
| AWS SDK v2 BOM | 2.25.28+ |

---

## Installation

### 1. Add to `build.gradle`

```groovy
dependencies {
    // existing entries ...
    implementation "org.grails.plugins:grails-ses-mail:1.0.0"
}
```

The plugin's `build.gradle` already pulls in the AWS SDK v2 modules
(`sesv2`, `auth`) transitively.  No additional SDK dependencies are needed
in the consuming application.

### 2. Configure in `plugin.groovy` (or `application.yml`)

```groovy
grails {
    mail {
        ses {
            enabled     = true
            region      = "us-east-1"          // region where identities are verified
            defaultFrom = "noreply@example.com" // must be a verified SES identity
        }
    }
}
```

When `enabled = false` (the default), the plugin is completely dormant.

---

## Configuration reference

All keys are nested under `grails.mail.ses`.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | Boolean | `false` | Master switch. Set `true` to activate SES API delivery. |
| `region` | String | `"us-east-1"` | AWS region of your verified SES identities. |
| `accessKey` | String | `""` | IAM Access Key ID. Leave blank to use the credential chain (recommended). |
| `secretKey` | String | `""` | IAM Secret Access Key. Leave blank when using the credential chain. |
| `defaultFrom` | String | `""` | Fallback FROM address when a message carries none. Falls back to `grails.mail.default.from` when also blank. |
| `configurationSetName` | String | `""` | SES Configuration Set to attach to every send. Enables tracking and event routing. Leave blank to send without one. |

### AWS credential resolution order

When `accessKey` and `secretKey` are both blank the plugin uses
`DefaultCredentialsProvider`, which checks in this order:

1. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
2. `~/.aws/credentials` file
3. EC2 instance profile / ECS task role / EKS IRSA

Using instance roles or environment variables is strongly preferred over
embedding credentials in configuration files.

---

## IAM policy

The IAM user or role used by the plugin needs only one permission:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ses:SendRawEmail",
      "Resource": "*"
    }
  ]
}
```

---

## Bounce and complaint handling (optional)

The plugin ships `SesSnsHandlerController`, `SnsSignatureVerifier`, and
`SesBounceSuppressionService` to process SES bounce and complaint notifications
delivered via Amazon SNS.

### Endpoint

The plugin registers the endpoint automatically via its own `SesMailUrlMappings`.
No manual `UrlMappings` entry is needed in the consuming application.

```
POST /ses/sns
```

Subscribe this URL in the AWS Console as the SNS HTTP/HTTPS endpoint.

### AWS setup

1. SES Console → Configuration Sets → your set → **Event Destinations**
   → Add an SNS destination for **Bounce** and **Complaint** event types.
2. Create an SNS topic. Subscribe your application's endpoint:
   ```
   https://your-app/ses/sns
   ```
3. AWS sends a `SubscriptionConfirmation` POST first. The controller validates
   the URL is a genuine AWS domain and confirms it automatically.

### SNS endpoint hardening

The plugin provides two additional security checks on top of signature
verification.  Both are configured under `grails.mail.ses.sns`.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `topicArn` | String | `""` | When set, only messages from this SNS Topic ARN are accepted. All others are rejected with HTTP 403. Recommended in production to prevent other AWS accounts' topics from reaching your endpoint (their signatures are still valid). |
| `maxMessageAgeMinutes` | Long | `5` | Maximum age of an SNS message in minutes. Messages older than this are rejected to guard against replay attacks. |

```groovy
grails {
    mail {
        ses {
            sns {
                topicArn              = "arn:aws:sns:us-east-1:123456789012:ses-events"
                maxMessageAgeMinutes  = 5
            }
        }
    }
}
```

### SNS signature verification

Every inbound SNS POST is verified against the AWS RSA signature before
processing.  This protects the endpoint against two attack vectors:

- **Fake SubscriptionConfirmation** – an attacker tricks the plugin into
  confirming a rogue SNS subscription, redirecting your SES event data.
- **Fake Bounce/Complaint** – an attacker injects fraudulent suppression
  events, silently blocking delivery to legitimate addresses.

Verification is **on by default** and uses only the Java standard library
(no extra dependency).  Disable only in non-production environments:

```groovy
grails.mail.ses.sns.verifySignature = false   // dev / test only
```

**The plugin does NOT sign outbound mail.** AWS SES handles outbound DKIM
signing internally when you configure sending identities in the SES console.
The signing managed here is purely for inbound SNS messages: proving that
the POST originated from AWS, not a spoofed source.

### URL mapping

Registered automatically by `SesMailUrlMappings` — no action required.
If the default path `/ses/sns` conflicts with your application's routes,
you can declare your own mapping pointing to the same controller/action:

```groovy
// In your application's UrlMappings.groovy (only if /ses/sns conflicts)
"/custom/path/sns"(controller: 'sesSnsHandler', action: 'handleSnsEvent', method: 'POST')
```

### Checking suppression before sending

`SesBounceSuppressionService` is auto-wired as a Spring bean.  Inject it
wherever you need to guard a send:

```groovy
class MyMailService {

    SesBounceSuppressionService sesBounceSuppressionService

    void send(List<String> recipients, String subject, String body) {
        List<String> safe = sesBounceSuppressionService.filterSuppressed(recipients)
        if (!safe) return
        // call grails-mail / async-mail sendMail with 'safe' recipients
    }
}
```

### Persisting suppressions

The default in-memory store is suitable for single-node use.  Register a
listener in `BootStrap.groovy` to add persistence without subclassing:

```groovy
class BootStrap {

    SesBounceSuppressionService sesBounceSuppressionService

    def init = { servletContext ->
        sesBounceSuppressionService.addSuppressListener { SuppressedAddress addr ->
            // write to your own domain/repository
            new MyEmailSuppression(
                email : addr.emailAddress,
                type  : addr.suppressionType,
                reason: addr.reason
            ).save(flush: true)
        }
    }
}
```

### SesBounceSuppressionService API

| Method | Description |
|--------|-------------|
| `isSuppressed(String email)` | Returns `true` if the address is suppressed. |
| `filterSuppressed(List<String>)` | Returns only the non-suppressed addresses from a list. |
| `getSuppressionRecord(String email)` | Returns the full `SuppressedAddress` record, or `null`. |
| `getAllSuppressed()` | Returns an unmodifiable snapshot of the entire suppression map. |
| `unsuppress(String email)` | Removes an address from the suppression list. |
| `addSuppressListener(Closure)` | Registers a callback invoked on every new suppression. |
| `clearAll()` | Clears all suppressions (useful in tests). |

---

## Running tests

```bash
./gradlew test
```

Tests use Spock and mock `SesV2Client` directly.  No AWS credentials or live
SES endpoint are required.

---

## Building and publishing

```bash
# Publish to Nexus
./gradlew clean build publishMavenJarPublicationToNexusRepoRepository \
    -PnexusUsername=<user> -PnexusPassword=<pass> -PnexusUrl=<url>

# Or via environment variables
export NEXUS_USERNAME=<user> NEXUS_PASSWORD=<pass> NEXUS_URL=<url>
./gradlew clean build publish

# Install to local Maven cache for development
./gradlew publishToMavenLocal
```
