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
 * ScriptRunner Custom REST Endpoint - per-issue presence
 * -----------------------------------------------------
 * Install: Jira admin -> ScriptRunner -> REST Endpoints -> Custom endpoint
 *
 * URL:   <baseurl>/rest/scriptrunner/latest/custom/issuePresence
 * Usage: GET ?issueKey=ABC-123           heartbeat + list of viewers
 *        GET ?issueKey=ABC-123&leave=1   unregister (page is being left)
 *
 * Imports below use jakarta.ws.rs.core.* (ScriptRunner 10.x / Jira 10+).
 * For ScriptRunner 8.x/9.x switch to the commented javax variant.
 *
 * Deliberately declared without a `groups` restriction: the name of the
 * license group differs between instances (jira-users, jira-software-users,
 * an LDAP group ...) and a wrong value yields a silent HTTP 403. Access is
 * enforced in code instead - not logged in gives 401, missing browse
 * permission on the issue gives 403.
 */

import com.atlassian.cache.Cache
import com.atlassian.cache.CacheLoader
import com.atlassian.cache.CacheManager
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.jira.component.ComponentAccessor
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

issuePresence(httpMethod: "GET") { MultivaluedMap queryParams, String body ->

    // ==========================================================
    // ===  CONFIGURATION  ======================================
    // ==========================================================

    // How long a viewer counts as present without a new heartbeat.
    // Controls how quickly somebody disappears from the list.
    // COUPLING: must be at least 2.5x INTERVAL_ALONE in the panel script
    // (10000 ms there), otherwise a viewer who is briefly alone drops out
    // of the cache before the next one can see them.
    final long TTL_SECONDS = 25

    // Include the requesting user in the returned list. Useful for testing.
    final boolean SHOW_SELF = false

    // Upper bound of concurrently tracked (issue, user) pairs.
    final int MAX_ENTRIES = 5000

    // Cache name. Must be identical in every script of this project.
    // Change the trailing version only to deliberately discard all entries.
    final String CACHE_NAME = 'com.example.jira.issuepresence.v1'

    // ==========================================================
    // ===  NO CHANGES NEEDED BELOW  ============================
    // ==========================================================

    def user = ComponentAccessor.jiraAuthenticationContext.loggedInUser
    if (!user) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new JsonBuilder([error: 'not authenticated']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    String issueKey = queryParams.getFirst('issueKey') as String
    if (!issueKey) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new JsonBuilder([error: 'issueKey missing']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    def issue = ComponentAccessor.issueManager.getIssueByCurrentKey(issueKey)
    if (!issue) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new JsonBuilder([error: 'issue not found']).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    // Only someone allowed to see the issue may see who is looking at it.
    if (!ComponentAccessor.permissionManager.hasPermission(
            ProjectPermissions.BROWSE_PROJECTS, issue, user)) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new JsonBuilder([error: 'no browse permission on ' + issueKey]).toString())
                .type(MediaType.APPLICATION_JSON).build()
    }

    // remote() + replicateViaCopy(): put/remove are replicated to the other
    // cluster nodes. The cache key contains the user, so in practice each key
    // is written by exactly the node the user's session is pinned to (sticky
    // sessions assumed) - which satisfies Atlassian's "single writer per key"
    // rule for copy-replicated caches.
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

    String prefix   = issue.id.toString() + '|'
    String myKey    = prefix + user.key
    long   now      = System.currentTimeMillis()
    boolean leaving = queryParams.getFirst('leave') in ['1', 'true']

    if (leaving) {
        cache.remove(myKey)
    } else {
        cache.put(myKey, now)   // heartbeat, resets the TTL
    }

    def userManager = ComponentAccessor.userManager
    long ttlMillis = TTL_SECONDS * 1000L
    List<Map<String, Object>> viewers = []

    cache.getKeys().findAll { String k -> k.startsWith(prefix) }.each { String k ->
        Long ts = cache.get(k)
        if (ts == null) return

        long ageMs = now - ts
        if (ageMs > ttlMillis) {
            cache.remove(k)     // expired, but the TTL has not swept it yet
            return
        }

        String viewerKey = k.substring(prefix.length())
        boolean isSelf = (viewerKey == user.key)
        if (isSelf && !SHOW_SELF) return

        def viewer = userManager.getUserByKey(viewerKey)
        if (!viewer || !viewer.active) return

        viewers << ([
                key         : viewerKey,
                name        : viewer.displayName,
                username    : viewer.name,
                avatarUrl   : '/secure/useravatar?size=small&ownerId=' +
                              URLEncoder.encode(viewerKey, 'UTF-8'),
                profileUrl  : '/secure/ViewProfile.jspa?name=' +
                              URLEncoder.encode(viewer.name as String, 'UTF-8'),
                idleSeconds : (long) (ageMs / 1000),
                self        : isSelf
        ] as Map<String, Object>)
    }

    viewers.sort { Map<String, Object> v -> ((String) v.get('name'))?.toLowerCase() }

    Map<String, Object> result = [
            issueKey  : issue.key,
            ttlSeconds: TTL_SECONDS,
            viewers   : viewers
    ]

    return Response.ok(new JsonBuilder(result).toString())
            .type(MediaType.APPLICATION_JSON)
            .header('Cache-Control', 'no-store')
            .build()
}
