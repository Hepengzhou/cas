package org.apereo.cas.ticket.registry;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.SerializableDocument;
import com.couchbase.client.java.view.DefaultView;
import com.couchbase.client.java.view.View;
import com.couchbase.client.java.view.ViewQuery;
import com.couchbase.client.java.view.ViewResult;
import com.couchbase.client.java.view.ViewRow;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.couchbase.core.CouchbaseClientFactory;
import org.apereo.cas.ticket.ServiceTicket;
import org.apereo.cas.ticket.Ticket;
import org.apereo.cas.ticket.TicketCatalog;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.util.CollectionUtils;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A Ticket Registry storage backend which uses the memcached protocol.
 * CouchBase is a multi host NoSQL database with a memcached interface
 * to persistent storage which also is quite usable as a replicated
 * ticket storage engine for multiple front end CAS servers.
 *
 * @author Fredrik Jönsson "fjo@kth.se"
 * @author Misagh Moayyed
 * @since 4.2.0
 */
@Slf4j
@AllArgsConstructor
public class CouchbaseTicketRegistry extends AbstractTicketRegistry {
    /**
     * The all tickets view name.
     */
    public static final String VIEW_NAME_ALL_TICKETS = "all_tickets";

    /**
     * All tickets view.
     */
    public static final View ALL_TICKETS_VIEW = DefaultView.create(
        VIEW_NAME_ALL_TICKETS,
        "function(d,m) {emit(m.id);}",
        "_count");

    /**
     * Views available.
     */
    public static final Collection<View> ALL_VIEWS = CollectionUtils.wrap(ALL_TICKETS_VIEW);

    /**
     * "statistics" document.
     */
    public static final String UTIL_DOCUMENT = "statistics";

    private static final long MAX_EXP_TIME_IN_DAYS = 30;
    private static final String END_TOKEN = "\u02ad";

    private final TicketCatalog ticketCatalog;
    private final CouchbaseClientFactory couchbase;

    @Override
    public Ticket updateTicket(final Ticket ticket) {
        addTicket(ticket);
        return ticket;
    }

    @Override
    public void addTicket(final Ticket ticketToAdd) {
        LOGGER.debug("Adding ticket [{}]", ticketToAdd);
        try {
            final Ticket ticket = encodeTicket(ticketToAdd);
            final SerializableDocument document = SerializableDocument.create(ticket.getId(), getTimeToLive(ticketToAdd), ticket);
            final Bucket bucket = this.couchbase.getBucket();
            LOGGER.debug("Created document for ticket [{}]. Upserting into bucket [{}]", ticketToAdd, bucket.name());
            bucket.upsert(document);
        } catch (final Exception e) {
            LOGGER.error("Failed adding [{}]: [{}]", ticketToAdd, e);
        }
    }

    @Override
    public Ticket getTicket(final String ticketId) {
        try {
            LOGGER.debug("Locating ticket id [{}]", ticketId);
            final String encTicketId = encodeTicketId(ticketId);
            if (encTicketId == null) {
                LOGGER.debug("Ticket id [{}] could not be found", ticketId);
                return null;
            }

            final SerializableDocument document = this.couchbase.getBucket().get(encTicketId, SerializableDocument.class);
            if (document != null) {
                final Ticket t = (Ticket) document.content();
                LOGGER.debug("Got ticket [{}] from the registry.", t);

                final Ticket decoded = decodeTicket(t);
                if (decoded == null || decoded.isExpired()) {
                    LOGGER.warn("The expiration policy for ticket id [{}] has expired the ticket", ticketId);
                    return null;
                }
                return decoded;
            }
            LOGGER.debug("Ticket [{}] not found in the registry.", encTicketId);
            return null;
        } catch (final Exception e) {
            LOGGER.error("Failed fetching [{}]: [{}]", ticketId, e);
            return null;
        }
    }

    /**
     * Stops the couchbase client.
     */
    @PreDestroy
    @SneakyThrows
    public void destroy() {
        LOGGER.debug("Shutting down Couchbase");
        this.couchbase.shutdown();
    }

    @Override
    public Collection<Ticket> getTickets() {
        final List<Ticket> tickets = new ArrayList<>();
        this.ticketCatalog.findAll().forEach(t -> {
            final Iterator<ViewRow> it = getViewResultIteratorForPrefixedTickets(t.getPrefix() + '-').iterator();
            while (it.hasNext()) {
                final ViewRow row = it.next();
                if (StringUtils.isNotBlank(row.id())) {
                    final JsonDocument document = row.document();
                    final Ticket ticket = (Ticket) document.content();
                    LOGGER.debug("Got ticket [{}] from the registry.", ticket);

                    final Ticket decoded = decodeTicket(ticket);
                    if (decoded == null || decoded.isExpired()) {
                        LOGGER.warn("Ticket has expired or cannot be decoded");
                    } else {
                        tickets.add(decoded);
                    }
                }
            }
        });
        return tickets;
    }

    @Override
    public long sessionCount() {
        return runQuery(TicketGrantingTicket.PREFIX + '-');
    }

    @Override
    public long serviceTicketCount() {
        return runQuery(ServiceTicket.PREFIX + '-');
    }

    @Override
    public boolean deleteSingleTicket(final String ticketIdToDelete) {
        final String ticketId = encodeTicketId(ticketIdToDelete);
        LOGGER.debug("Deleting ticket [{}]", ticketId);
        try {
            return this.couchbase.getBucket().remove(ticketId) != null;
        } catch (final Exception e) {
            LOGGER.error("Failed deleting [{}]: [{}]", ticketId, e);
            return false;
        }
    }

    @Override
    public long deleteAll() {
        final Consumer<? super ViewRow> remove = t -> this.couchbase.getBucket().remove(t.document());
        return this.ticketCatalog.findAll()
            .stream()
            .mapToLong(t -> {
                final Iterator<ViewRow> it = getViewResultIteratorForPrefixedTickets(t.getPrefix() + '-').iterator();
                final int count = getViewRowCountFromViewResultIterator(it);
                it.forEachRemaining(remove);
                return count;
            })
            .sum();
    }

    private int runQuery(final String prefix) {
        final Iterator<ViewRow> iterator = getViewResultIteratorForPrefixedTickets(prefix).iterator();
        return getViewRowCountFromViewResultIterator(iterator);
    }

    private static int getViewRowCountFromViewResultIterator(final Iterator<ViewRow> iterator) {
        if (iterator.hasNext()) {
            final ViewRow res = iterator.next();
            final Integer count = (Integer) res.value();
            LOGGER.debug("Found [{}] rows", count);
            return count;
        }
        LOGGER.debug("No rows could be found by the query iterator.");
        return 0;
    }

    private ViewResult getViewResultIteratorForPrefixedTickets(final String prefix) {
        LOGGER.debug("Running query on document [{}] and view [{}] with prefix [{}]",
            UTIL_DOCUMENT, VIEW_NAME_ALL_TICKETS, prefix);
        return this.couchbase.getBucket().query(
            ViewQuery.from(UTIL_DOCUMENT, VIEW_NAME_ALL_TICKETS)
                .startKey(prefix)
                .endKey(prefix + END_TOKEN)
                .reduce());
    }

    /**
     * Get the expiration policy value of the ticket in seconds.
     *
     * @param ticket the ticket
     * @return the exp value
     * @see <a href="http://docs.couchbase.com/developer/java-2.0/documents-basics.html">Couchbase Docs</a>
     */
    private static int getTimeToLive(final Ticket ticket) {
        final int expTime = ticket.getExpirationPolicy().getTimeToLive().intValue();
        if (TimeUnit.SECONDS.toDays(expTime) >= MAX_EXP_TIME_IN_DAYS) {
            LOGGER.warn("Any expiration time larger than [{}] days in seconds is considered absolute (as in a Unix time stamp) "
                + "anything smaller is considered relative in seconds.", MAX_EXP_TIME_IN_DAYS);

        }
        return expTime;
    }
}

