package grails.plugins.mail.aws

import groovy.util.logging.Slf4j

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * SesBounceSuppressionService
 * ============================
 * Receives bounce and complaint events from SesSnsHandlerController and
 * maintains a suppression list of addresses that should not receive further mail.
 *
 * Default storage
 * ---------------
 * The default implementation stores suppressions in-memory using a
 * ConcurrentHashMap.  This is suitable for single-node deployments or as a
 * starting point.  For clustered or persistent storage, use addSuppressListener()
 * to hook in your own persistence logic without subclassing, or extend this
 * service and override suppressAddresses() in your application.
 *
 * Extension points
 * ----------------
 * addSuppressListener(Closure)
 *   Register a callback invoked synchronously each time an address is suppressed.
 *   Use this to write records to a database, call an external API, or publish
 *   to a message queue.
 *
 * Subclassing
 * -----------
 * Declare a subclass as a Spring bean named 'sesBounceSuppressionService' in
 * your application to replace this default implementation entirely.
 */
@Slf4j
class SesBounceSuppressionService {

    static transactional = false

    /** In-memory suppression store. Key = lowercased email address. */
    private final Map<String, SuppressedAddress> suppressionList = new ConcurrentHashMap<>()

    /**
     * Listener closures invoked after each suppression.
     * Signature: { SuppressedAddress addr -> ... }
     */
    private final List<Closure> suppressListeners = [].asSynchronized() as List<Closure>

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Adds the given addresses to the suppression list.
     * Called automatically by SesSnsHandlerController when SES reports a
     * bounce or complaint via SNS.
     *
     * @param addresses   email addresses to suppress
     * @param type        'BOUNCE' or 'COMPLAINT'
     * @param subtype     SES-reported subtype (e.g. 'General', 'abuse')
     * @param when        timestamp of the originating SES event
     */
    void suppressAddresses(List<String> addresses, String type, String subtype, Instant when) {
        addresses.each { String addr ->
            SuppressedAddress record = new SuppressedAddress(
                    emailAddress   : addr.trim().toLowerCase(),
                    suppressionType: type,
                    subtype        : subtype,
                    suppressedAt   : when,
                    reason         : "${type}/${subtype}"
            )
            suppressionList[record.emailAddress] = record
            log.warn "Address suppressed [{}]: {}", type, addr
            suppressListeners.each { it.call(record) }
        }
    }

    /**
     * Removes an address from the suppression list.
     * Call this when a previously-suppressed user re-opts in.
     */
    void unsuppress(String emailAddress) {
        if (suppressionList.remove(emailAddress?.trim()?.toLowerCase())) {
            log.info "Address unsuppressed: {}", emailAddress
        }
    }

    /**
     * Clears the entire in-memory suppression list.
     * Primarily useful in integration tests.
     */
    void clearAll() {
        suppressionList.clear()
    }

    // -------------------------------------------------------------------------
    // Read / query operations
    // -------------------------------------------------------------------------

    /**
     * Returns true if the address is suppressed and should not receive mail.
     * Comparison is case-insensitive.
     */
    boolean isSuppressed(String emailAddress) {
        suppressionList.containsKey(emailAddress?.trim()?.toLowerCase())
    }

    /**
     * Returns the full suppression record for an address, or null if not suppressed.
     */
    SuppressedAddress getSuppressionRecord(String emailAddress) {
        suppressionList[emailAddress?.trim()?.toLowerCase()]
    }

    /**
     * Filters a list of addresses, returning only those that are not suppressed.
     *
     * Example usage in any Grails service:
     * <pre>
     *   List<String> safe = sesBounceSuppressionService.filterSuppressed(recipients)
     *   if (safe) {
     *       mailSender.send(buildMessage(safe, subject, body))
     *   }
     * </pre>
     */
    List<String> filterSuppressed(List<String> addresses) {
        addresses.findAll { !isSuppressed(it) }
    }

    /**
     * Returns an unmodifiable snapshot of all currently suppressed addresses.
     */
    Map<String, SuppressedAddress> getAllSuppressed() {
        Collections.unmodifiableMap(suppressionList)
    }

    // -------------------------------------------------------------------------
    // Listener registration
    // -------------------------------------------------------------------------

    /**
     * Registers a listener closure called synchronously each time an address
     * is suppressed.  Use this to add persistence without subclassing.
     *
     * Example (in BootStrap.groovy):
     * <pre>
     *   sesBounceSuppressionService.addSuppressListener { SuppressedAddress addr ->
     *       new MySuppressionDomain(
     *           email : addr.emailAddress,
     *           reason: addr.reason
     *       ).save(flush: true)
     *   }
     * </pre>
     */
    void addSuppressListener(Closure listener) {
        suppressListeners << listener
    }
}
