/*
 * Copyright @ 2021 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.conference.source

import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jxmpp.jid.Jid
import java.lang.UnsupportedOperationException

/**
 * A container for sources from multiple endpoints, mapped by the ID of the endpoint. This could contain sources for
 * an entire conference, or a subset.
 * This map is not thread safe.
 */
open class ConferenceSourceMap(
    /**
     * The sources mapped by endpoint ID.
     */
    private val endpointSourceSets: MutableMap<Jid?, EndpointSourceSet> = mutableMapOf()
) : Map<Jid?, EndpointSourceSet> by endpointSourceSets {

    constructor(vararg entries: Pair<Jid?, EndpointSourceSet>) : this(
        mutableMapOf<Jid?, EndpointSourceSet>().apply {
            entries.forEach { (k, v) ->
                this[k] = v
            }
        }
    )

    constructor(owner: Jid?, endpointSourceSet: EndpointSourceSet) : this(owner to endpointSourceSet)
    constructor(
        owner: Jid?,
        contents: List<ContentPacketExtension>
    ) : this(owner, EndpointSourceSet.fromJingle(contents))
    constructor(owner: Jid?, source: Source) : this(owner, EndpointSourceSet(setOf(source), emptySet()))
    constructor(
        owner: Jid?,
        sources: Set<Source>,
        groups: Set<SsrcGroup>
    ) : this(owner, EndpointSourceSet(sources, groups))

    open fun remove(owner: Jid?) = endpointSourceSets.remove(owner)

    /**
     * An unmodifiable view of this [ConferenceSourceMap].
     */
    val unmodifiable by lazy { UnmodifiableConferenceSourceMap(endpointSourceSets) }
    fun unmodifiable() = unmodifiable

    /** Adds the sources of another [ConferenceSourceMap] to this. */
    open fun add(other: ConferenceSourceMap) {
        other.endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            endpointSourceSets[owner] += endpointSourceSet
        }
    }

    /** Removes the sources of another [ConferenceSourceMap] from this one. */
    open fun remove(other: ConferenceSourceMap) {
        other.endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            val existing = endpointSourceSets[owner]
            if (existing != null) {
                val result = existing - endpointSourceSet
                // TODO: do we want to allow lingering SsrcGroups? Should we actually remove SsrcGroups when their
                // sources are removed?
                if (result.isEmpty()) {
                    endpointSourceSets.remove(owner)
                } else {
                    endpointSourceSets[owner] = result
                }
            }
        }
    }

    /**
     * Creates a list of [ContentPacketExtension]s that describe the sources in this [ConferenceSourceMap].
     */
    fun toJingle(): List<ContentPacketExtension> {
        val contents = mutableMapOf<MediaType, ContentPacketExtension>()
        forEach { (owner, sourceSet) -> sourceSet.toJingle(contents, owner) }
        return contents.values.toList()
    }

    fun createSourcePacketExtensions(mediaType: MediaType): List<SourcePacketExtension> {
        val extensions = mutableListOf<SourcePacketExtension>()
        forEach { (owner, endpointSourceSet) ->
            endpointSourceSet.sources.filter { it.mediaType == mediaType }.forEach { source ->
                extensions.add(source.toPacketExtension(owner))
            }
        }
        return extensions
    }

    fun createSourceGroupPacketExtensions(mediaType: MediaType): List<SourceGroupPacketExtension> {
        val extensions = mutableListOf<SourceGroupPacketExtension>()
        forEach { (_, endpointSourceSet) ->
            endpointSourceSet.ssrcGroups.filter { it.mediaType == mediaType }.forEach { ssrcGroup ->
                extensions.add(ssrcGroup.toPacketExtension())
            }
        }
        return extensions
    }

    fun copy(): ConferenceSourceMap = ConferenceSourceMap(endpointSourceSets.toMutableMap())

    fun removeInjected() = this.apply {
        endpointSourceSets.forEach { (owner, endpointSourceSet) ->
            val withoutInjected = endpointSourceSet.withoutInjected()
            if (withoutInjected.isEmpty()) {
                endpointSourceSets.remove(owner)
            } else {
                endpointSourceSets[owner] = withoutInjected
            }
        }
    }
}

/**
 * A read-only version of [ConferenceSourceMap]. Attempts to modify the map will via [add], [remove] or any of the
 * standard [java.lang.Map] mutating methods will result in an exception.
 */
class UnmodifiableConferenceSourceMap(
    endpointSourceSets: MutableMap<Jid?, EndpointSourceSet>
) : ConferenceSourceMap(endpointSourceSets) {
    override fun add(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("add() not supported in unmodifiable view")
    override fun remove(other: ConferenceSourceMap) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")
    override fun remove(owner: Jid?) =
        throw UnsupportedOperationException("remove() not supported in unmodifiable view")
}

fun EndpointSourceSet.withoutInjected() = EndpointSourceSet(
    sources.filter { !it.injected }.toSet(),
    // Just maintain the groups. We never use groups with injected SSRCs, and "injected" should go away at some point.
    ssrcGroups
)
