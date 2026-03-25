package grails.plugins.mail.aws

import groovy.transform.CompileStatic
import groovy.transform.ToString

import java.time.Instant

/**
 * Represents an email address suppressed due to a hard bounce or complaint.
 *
 * This is a plain Groovy value object – not a GORM domain class – so the
 * plugin carries no persistence dependency.  Consuming applications decide
 * how and whether to persist these records (see SesBounceSuppressionService).
 */
@CompileStatic
@ToString(includeNames = true)
class SuppressedAddress implements Serializable {

    private static final long serialVersionUID = 1L

    /** The normalised (lowercased, trimmed) email address. */
    String emailAddress

    /** Suppression reason category: 'BOUNCE' or 'COMPLAINT'. */
    String suppressionType

    /** SES-reported subtype, e.g. 'General', 'NoEmail', 'Suppressed', 'abuse', 'fraud'. */
    String subtype

    /** Timestamp when the suppression event was received from SES/SNS. */
    Instant suppressedAt

    /** Human-readable summary, e.g. "BOUNCE/General". Useful for audit logs. */
    String reason
}
