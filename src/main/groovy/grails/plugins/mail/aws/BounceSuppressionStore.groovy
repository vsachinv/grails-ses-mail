package grails.plugins.mail.aws

import java.time.Instant

/**
 * Contract for bounce/complaint suppression storage.
 *
 * The plugin ships an in-memory default ({@link InMemoryBounceSuppressionStore}).
 * Consuming applications can provide their own implementation backed by Redis,
 * Hazelcast, a database, or any other store by declaring a Spring bean named
 * {@code bounceSuppressionStore}.
 *
 * Example (resources.groovy):
 * <pre>
 *   beans = {
 *       bounceSuppressionStore(RedisBounceSuppressionStore) {
 *           redisTemplate = ref('redisTemplate')
 *       }
 *   }
 * </pre>
 */
interface BounceSuppressionStore {

    /**
     * Suppresses the given addresses.
     *
     * @param addresses   email addresses to suppress
     * @param type        'BOUNCE' or 'COMPLAINT'
     * @param subtype     SES-reported subtype (e.g. 'General', 'abuse')
     * @param when        timestamp of the originating SES event
     */
    void suppressAddresses(List<String> addresses, String type, String subtype, Instant when)

    /**
     * Removes an address from the suppression list.
     */
    void unsuppress(String emailAddress)

    /**
     * Returns {@code true} if the address is suppressed.
     */
    boolean isSuppressed(String emailAddress)

    /**
     * Returns the full suppression record, or {@code null} if not suppressed.
     */
    SuppressedAddress getSuppressionRecord(String emailAddress)

    /**
     * Returns only the non-suppressed addresses from the given list.
     */
    List<String> filterSuppressed(List<String> addresses)

    /**
     * Returns an unmodifiable snapshot of all currently suppressed addresses.
     */
    Map<String, SuppressedAddress> getAllSuppressed()

    /**
     * Clears all suppressions. Primarily useful in tests.
     */
    void clearAll()

    /**
     * Registers a listener called synchronously each time an address is suppressed.
     */
    void addSuppressListener(Closure listener)
}