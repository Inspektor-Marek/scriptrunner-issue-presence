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
 * ScriptRunner Scripted Field - "Current viewers"
 * ----------------------------------------------
 * Install: Jira admin -> ScriptRunner -> Fields -> Custom scripted field
 *   Template: Text Field (multi-line)
 *   Searcher: set NONE. With a searcher the field is evaluated on every
 *             reindex and stale values end up in the index.
 *
 * Then add it as a column: filter -> Columns -> "Current viewers". The
 * Filter Results gadget picks up the filter's column selection.
 *
 * Reads the same cache as the REST endpoints but never writes to it, so
 * displaying a list does not count as viewing those issues.
 *
 * NOTE: this column is not live. It is computed server side while the list
 * renders, so it only refreshes on page reload or on the gadget's auto
 * refresh. With a TTL of 25 seconds that means it is correct at load time
 * and increasingly stale afterwards. For a live display use the
 * issue-list-badges fragment instead.
 */

import com.atlassian.cache.Cache
import com.atlassian.cache.CacheLoader
import com.atlassian.cache.CacheManager
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue

import java.util.concurrent.TimeUnit

// ==========================================================
// ===  CONFIGURATION  ======================================
// ==========================================================

// These three MUST match the REST endpoints. The cache is created with
// these settings on first access; differing values lead either to an error
// or to a second, empty cache.
final String CACHE_NAME  = 'com.example.jira.issuepresence.v1'
final long   TTL_SECONDS = 25
final int    MAX_ENTRIES = 5000

// Include the current user. Normally false - you know you are looking.
final boolean SHOW_SELF = false

// Value when nobody has the issue open. null leaves the cell empty, which
// looks calmer in a list than a dash on every row.
final String EMPTY_TEXT = null

// Separator between multiple viewers.
// ', '  -> everything on one line, good for list columns
// '\n'  -> one name per line (the multi-line template renders the break)
final String SEPARATOR = ', '

// ==========================================================
// ===  NO CHANGES NEEDED BELOW  ============================
// ==========================================================

Issue currentIssue = binding.hasVariable('issue') ? (Issue) binding.getVariable('issue') : null
if (!currentIssue) {
    return EMPTY_TEXT
}

def cacheManager = ComponentAccessor.getOSGiComponentInstanceOfType(CacheManager)
if (!cacheManager) {
    return EMPTY_TEXT
}

def settings = new CacheSettingsBuilder()
        .remote()
        .replicateViaCopy()
        .replicateAsynchronously()
        .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
        .maxEntries(MAX_ENTRIES)
        .build()

Cache<String, Long> cache = cacheManager.getCache(CACHE_NAME, (CacheLoader) null, settings)

String prefix = currentIssue.id.toString() + '|'
long now = System.currentTimeMillis()
long ttlMillis = TTL_SECONDS * 1000L

def userManager = ComponentAccessor.userManager
def me = ComponentAccessor.jiraAuthenticationContext.loggedInUser

List<String> names = []
cache.getKeys().findAll { String k -> k.startsWith(prefix) }.each { String k ->
    Long ts = cache.get(k)
    if (ts == null) return
    if (now - ts > ttlMillis) return

    String viewerKey = k.substring(prefix.length())
    if (!SHOW_SELF && me && viewerKey == me.key) return

    def viewer = userManager.getUserByKey(viewerKey)
    if (viewer?.active) {
        names << (viewer.displayName as String)
    }
}

if (!names) {
    return EMPTY_TEXT
}

names.sort()
return names.join(SEPARATOR)
