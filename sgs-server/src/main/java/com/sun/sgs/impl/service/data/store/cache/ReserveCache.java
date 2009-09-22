/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.data.store.cache;

/**
 * Keeps track of reserving and releasing space in the cache.  The constructors
 * reserve cache space, the {@code used} methods record that it has been used,
 * and the {@code done} method releases any space that is unused.  Callers
 * should make sure that they are not holding locks on the cache when calling
 * the constructors to avoid deadlocks with eviction.
 */
class ReserveCache {

    /** The cache. */
    private final Cache cache;

    /** The number of cache entries that have not been used. */
    private int unusedCacheEntries;

    /**
     * Creates an instance that reserves one cache entry.
     *
     * @param	cache the cache
     */
    ReserveCache(Cache cache) {
	this(cache, 1);
    }

    /**
     * Creates an instance that reserves the specified number of cache entries.
     *
     * @param	cache the cache
     * @param	numCacheEntries the number of cache entries
     * @throws	IllegalArgumentException if the argument is less than {@code 1}
     */
    ReserveCache(Cache cache, int numCacheEntries) {
	this.cache = cache;
	if (numCacheEntries < 1) {
	    throw new IllegalArgumentException();
	}
	unusedCacheEntries = numCacheEntries;
	cache.reserve(numCacheEntries);
    }

    /**
     * Notes that a cache entry has been used.
     *
     * @throws	IllegalStateException if there are no unused entries
     */
    void used() {
	used(1);
    }

    /**
     * Notes that a specified number of cache entries have been used.
     *
     * @param	numCacheEntries the number of entries used
     * @throws	IllegalStateException if there are not enough unused entries
     */
    void used(int numCacheEntries) {
	if (unusedCacheEntries < numCacheEntries) {
	    throw new IllegalStateException("Not enough unused entries");
	}
	unusedCacheEntries -= numCacheEntries;
    }

    /** Releases any unused cache entries. */
    void done() {
	if (unusedCacheEntries > 0) {
	    cache.release(unusedCacheEntries);
	}
    }
}