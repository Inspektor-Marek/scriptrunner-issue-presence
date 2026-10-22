/*
 * Copyright 2026 Tarik Uzerli
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

/*
 * ScriptRunner Custom REST Endpoint - presence for many issues at once
 * --------------------------------------------------------------------
 * Install: Jira admin -> ScriptRunner -> REST Endpoints -> Custom endpoint
 *          (a separate entry, in addition to issuePresence.groovy)
 *
 * URL:   <baseurl>/rest/scriptrunner/latest/custom/issuePresenceBatch
 * Usage: GET ?issueKeys=ABC-1,ABC-2,ABC-3
 *
 * Read only - it never writes to the cache. Looking at a list does not
 * count as viewing those issues.
 *
 * Response:
 *   { "ABC-1": [ {name, username, profileUrl, avatarUrl, idleSeconds} ], ... }
 * Issues without viewers are omitted.
 *
 * Imports below use jakarta.ws.rs.core.* (ScriptRunner 10.x / Jira 10+).
 */

import com.atlassian.cache.Cache
import com.atlassian.cache.CacheLoader
import com.atlassian.cache.CacheManager
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.permission.ProjectPermissions
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.json.JsonBuilder
import groovy.transform.BaseScript

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
// ScriptRunner 8.x / 9.x:
// import javax.ws.rs.core.MediaType
// import javax.ws.rs.core.MultivaluedMap
// import javax.ws.rs.core.Response

import java.util.concurrent.TimeUnit

@BaseScript CustomEndpointDelegate delegate

issuePresenceBatch(httpMethod: "GET") { MultivaluedMap queryParams, String body ->

    // ==========================================================
    // ===  CONFIGURATION  ======================================
    // ==========================================================

    // These three MUST match issuePresence.groovy, otherwise this endpoint
    // reads a different (empty) cache.
    final String CACHE_NAME  = 'com.example.jira.issuepresence.v1'
    final long   TTL_SECONDS = 25
    final int    MAX_ENTRIES = 5000

    // Include the requesting user in the returned lists.
    final boolean SHOW_SELF = false

    // Upper bound per request. Keeps a very long list from triggering
    // hundreds of permission checks on every poll.
    final int MAX_KEYS_PER_REQUEST = 100

    // ==========================================================
    // ===  NO CHANGES NEEDED BELOW  ============================
    // ==========================================================

    def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
    if (!user) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new JsonBuilder([error: 'not authenticated']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    String raw = queryParams.getFirst('issueKeys') as String
    if (!raw) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new JsonBuilder([error: 'issueKeys missing']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    List<String> requestedKeys = raw.split(',')
            .collect { String it -> it.trim() }
            .findAll { String it -> it } as List<String>
    if (requestedKeys.size() > MAX_KEYS_PER_REQUEST) {
        requestedKeys = requestedKeys.subList(0, MAX_KEYS_PER_REQUEST)
    }

    // Resolve issues and check browse permission while doing so.
    // issueId -> issueKey, so cache keys can be mapped back later.
    def issueManager = ComponentAccessor.issueManager
    def permissionManager = ComponentAccessor.permissionManager
    Map<String, String> idToKey = [:]
    requestedKeys.each { String k ->
        Issue i = issueManager.getIssueByCurrentKey(k)
        if (i && permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, i, user)) {
            idToKey.put(i.id.toString(), i.key)
        }
    }
    if (idToKey.isEmpty()) {
        return Response.ok(new JsonBuilder([:]).toString())
                .type(MediaType.APPLICATION_JSON)
                .header('Cache-Control', 'no-store').build()
    }

    def cacheManager = ComponentAccessor.getOSGiComponentInstanceOfType(CacheManager)
    if (!cacheManager) {
        return Response.serverError()
                .entity(new JsonBuilder([error: 'CacheManager unavailable']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    def settings = new CacheSettingsBuilder()
            .remote()
            .replicateViaCopy()
            .replicateAsynchronously()
            .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
            .maxEntries(MAX_ENTRIES)
            .build()

    Cache<String, Long> cache = cacheManager.getCache(CACHE_NAME, (CacheLoader) null, settings)

    long now = System.currentTimeMillis()
    long ttlMillis = TTL_SECONDS * 1000L
    def userManager = ComponentAccessor.userManager

    // Walk the cache once and group by issue, instead of scanning all
    // entries again for every requested issue.
    Map<String, List<Map<String, Object>>> result = [:]

    cache.getKeys().each { String k ->
        int sep = k.indexOf('|')
        if (sep < 1) return

        String issueId = k.substring(0, sep)
        String targetKey = idToKey.get(issueId)
        if (!targetKey) return

        Long ts = cache.get(k)
        if (ts == null) return
        long ageMs = now - ts
        if (ageMs > ttlMillis) return

        String viewerKey = k.substring(sep + 1)
        boolean isSelf = (viewerKey == user.key)
        if (isSelf && !SHOW_SELF) return

        def viewer = userManager.getUserByKey(viewerKey)
        if (!viewer || !viewer.active) return

        Map<String, Object> entry = [
                key         : viewerKey,
                name        : viewer.displayName,
                username    : viewer.name,
                avatarUrl   : '/secure/useravatar?size=small&ownerId=' +
                              URLEncoder.encode(viewerKey, 'UTF-8'),
                profileUrl  : '/secure/ViewProfile.jspa?name=' +
                              URLEncoder.encode(viewer.name as String, 'UTF-8'),
                idleSeconds : (long) (ageMs / 1000),
                self        : isSelf
        ] as Map<String, Object>

        if (!result.containsKey(targetKey)) {
            result.put(targetKey, [])
        }
        result.get(targetKey).add(entry)
    }

    result.each { String k, List<Map<String, Object>> v ->
        v.sort { Map<String, Object> m -> ((String) m.get('name'))?.toLowerCase() }
    }

    return Response.ok(new JsonBuilder(result).toString())
            .type(MediaType.APPLICATION_JSON)
            .header('Cache-Control', 'no-store')
            .build()
}
