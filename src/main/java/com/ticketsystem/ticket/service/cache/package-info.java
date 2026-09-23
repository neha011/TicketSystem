/**
 * The prompt cache's file-backed persistence layer: JSON (de)serialization of {@code CacheEntry}
 * records and atomic Cache_File I/O. Plays the role a Spring Data repository plays elsewhere; the
 * requirements mandate a JSON file on the filesystem rather than a relational table, so no JPA is
 * used here.
 */
package com.ticketsystem.ticket.service.cache;
