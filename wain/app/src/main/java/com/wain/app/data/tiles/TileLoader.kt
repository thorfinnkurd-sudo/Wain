package com.wain.app.data.tiles

import com.wain.app.core.geo.TileKey
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Small in-memory LRU of per-tile results, shared by POI and road-safety loading. A tile cached at a
 * lower detail [level] is refetched when more detail is needed; fetch failures fall back to cached data.
 * Cache is memory-only: it is not an offline map store.
 */
class TileLoader<T>(
    private val maxTiles: Int = 80,
    private val fetch: suspend (TileKey, Int) -> List<T>,
) {
    private class Entry<T>(val level: Int, val items: List<T>)

    data class Loaded<T>(val items: List<T>, val partial: Boolean)

    private val cache = LinkedHashMap<TileKey, Entry<T>>(32, 0.75f, true)

    suspend fun load(tiles: List<TileKey>, level: Int, allowNetwork: Boolean): Loaded<T> {
        val out = ArrayList<T>()
        var partial = false
        for (k in tiles) {
            val cached = synchronized(cache) { cache[k] }
            if (cached != null && cached.level >= level) {
                out.addAll(cached.items)
                continue
            }
            var fresh: List<T>? = null
            if (allowNetwork) {
                try {
                    fresh = fetch(k, level)
                    synchronized(cache) {
                        cache[k] = Entry(level, fresh)
                        while (cache.size > maxTiles) {
                            val eldest = cache.entries.iterator().next().key
                            cache.remove(eldest)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    fresh = null
                }
            }
            val use = fresh ?: cached?.items
            if (use != null) out.addAll(use)
            if (fresh == null) partial = true
        }
        return Loaded(out, partial)
    }
}
