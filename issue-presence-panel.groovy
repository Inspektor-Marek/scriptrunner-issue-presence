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
 * ScriptRunner Fragment - "Show a web panel" - who is viewing this issue
 * ----------------------------------------------------------------------
 * Install: Jira admin -> ScriptRunner -> Fragments -> Show a web panel
 *   Location:  atl.jira.view.issue.right.context
 *   Weight:    a number, e.g. 100 (an empty weight can break registration)
 *   Condition: leave EMPTY, or enter `true`. An editor containing one blank
 *              line evaluates to null, which is falsy - the panel then never
 *              renders and reports no error.
 *   Menu text: the panel heading, e.g. "Who is online"
 *   Provider:  this script
 *
 * Requires the REST endpoint issuePresence.groovy.
 *
 * Why the detour via <script type="text/plain"> plus <img onerror>:
 * Jira loads issue panels via AJAX in some contexts, and <script> tags
 * inserted that way are not executed by the browser. The onerror handler of
 * an image with an invalid source does run - it takes the code out of the
 * text/plain block and appends it as a real script element. That way the
 * logic starts in both cases.
 *
 * Two traps when editing the JavaScript below: the surrounding Groovy string
 * processes escape sequences even with triple quotes, so the JS must contain
 * no backslash at all; and three consecutive apostrophes terminate the
 * string, even inside a JS comment.
 */

import com.atlassian.jira.issue.Issue

// ==========================================================
// ===  CONFIGURATION (server side)  ========================
// ==========================================================

// Shown until the first response arrives. Further user-visible strings
// live in the TEXT block inside the JavaScript below.
final String LOADING_TEXT = 'Loading ...'

// ==========================================================

// 'issue' is a binding variable of the fragment. Static type checking does
// not know it, so read it through the binding instead of directly. If it is
// missing from the context, the JavaScript falls back to other sources.
Issue currentIssue = binding.hasVariable('issue') ? (Issue) binding.getVariable('issue') : null
String issueKeyAttr = currentIssue?.key?.replace('"', '') ?: ''

String js = '''
(function () {
    // ==========================================================
    // ===  CONFIGURATION (client side)  ========================
    // ==========================================================

    // Adaptive polling. While nobody else is in the issue a slow interval is
    // enough - that is the normal case and it determines the baseline load on
    // the instance. As soon as at least one other viewer shows up, polling
    // speeds up.
    //
    // Smaller values mean a fresher display but more requests: one open issue
    // produces 60000/INTERVAL requests per minute. At 10000 ms that is 6
    // requests per minute per issue view.
    //
    // COUPLING: TTL_SECONDS in the REST endpoint must be at least 2.5x
    // INTERVAL_ALONE. If you change INTERVAL_ALONE, adjust it there as well
    // (10000 ms <-> 25 s).
    var INTERVAL_ALONE  = 10000;   // ms, nobody else in the issue
    var INTERVAL_ACTIVE = 3000;    // ms, at least one other viewer

    // All user-visible strings. {0} is replaced with a value at runtime.
    var TEXT = {
        nobodyElse:     "Nobody else is viewing this issue.",
        noIssueKey:     "Could not determine the issue key.",
        unavailable:    "Presence unavailable (HTTP {0})",
        openProfile:    "Open profile of {0}",
        activeAgo:      "active {0} ago",
        idleFewSeconds: "a few seconds",
        idleSeconds:    "{0} seconds",
        idleOneMinute:  "1 minute",
        idleMinutes:    "{0} minutes"
    };

    var ROOT_ID = "issue-presence-root";

    // ==========================================================
    // ===  NO CHANGES NEEDED BELOW  ============================
    // ==========================================================

    function fmt(template, value) {
        return template.replace("{0}", String(value));
    }

    var root = document.getElementById(ROOT_ID);
    if (!root) { return; }

    // The panel is re-rendered when the issue changes in the navigator,
    // so always clear a previous timer first.
    if (window.__issuePresenceTimer) {
        window.clearTimeout(window.__issuePresenceTimer);
        window.__issuePresenceTimer = null;
    }

    var ctx = (window.AJS && AJS.contextPath) ? AJS.contextPath() : "";
    var ENDPOINT = ctx + "/rest/scriptrunner/latest/custom/issuePresence";

    // The issue key is resolved on EVERY poll, not once at startup. Two
    // reasons: the script sometimes runs before Jira has set its metadata,
    // and in the navigator the issue can change without a page reload.
    function resolveIssueKey() {
        var k = root.getAttribute("data-issue-key");
        if (k) { return k; }

        if (window.AJS && AJS.Meta && AJS.Meta.get) {
            k = AJS.Meta.get("issue-key");
            if (k) { return k; }
        }
        var meta = document.querySelector('meta[name="ajs-issue-key"]');
        if (meta && meta.content) { return meta.content; }

        var kv = document.getElementById("key-val");
        if (kv) {
            k = kv.getAttribute("data-issue-key") || (kv.textContent || "").trim();
            if (k) { return k; }
        }
        // new RegExp rather than a regex literal on purpose: a literal would
        // need backslashes, which collide with Groovy escape processing in
        // the surrounding string.
        var m = new RegExp("/browse/([A-Za-z][A-Za-z0-9_]*-[0-9]+)")
                .exec(window.location.pathname);
        if (m) { return m[1]; }

        return null;
    }

    var currentKey = null;
    var keyAttempts = 0;
    var MAX_KEY_ATTEMPTS = 15;   // roughly 4.5 s of short retries

    function sendLeave(key) {
        if (!key) { return; }
        try {
            fetch(ENDPOINT + "?issueKey=" + encodeURIComponent(key) + "&leave=1", {
                credentials: "same-origin",
                keepalive: true,
                headers: { "X-Atlassian-Token": "no-check" }
            });
        } catch (e) { /* never mind, the TTL cleans up anyway */ }
    }

    console.log("[issue-presence] start");

    function message(text) {
        root.innerHTML = "";
        var d = document.createElement("div");
        d.className = "issue-presence-empty";
        d.textContent = text;
        root.appendChild(d);
    }

    function formatIdle(seconds) {
        var s = Math.max(0, Math.round(seconds || 0));
        if (s < 10) { return TEXT.idleFewSeconds; }
        if (s < 60) { return fmt(TEXT.idleSeconds, s); }
        var m = Math.round(s / 60);
        return m === 1 ? TEXT.idleOneMinute : fmt(TEXT.idleMinutes, m);
    }

    function render(data) {
        var viewers = (data && data.viewers) || [];
        if (viewers.length === 0) {
            message(TEXT.nobodyElse);
            return;
        }
        root.innerHTML = "";
        var list = document.createElement("div");
        list.className = "issue-presence-list";
        viewers.forEach(function (v) {
            var item = document.createElement("div");
            item.className = "issue-presence-item";

            // Avatar and name form the link to the user profile; the
            // timestamp sits outside the link on purpose.
            var link = document.createElement(v.profileUrl ? "a" : "span");
            link.className = "issue-presence-link";
            if (v.profileUrl) {
                link.setAttribute("href", ctx + v.profileUrl);
                link.setAttribute("title", fmt(TEXT.openProfile, v.name));
            }

            var img = document.createElement("img");
            img.className = "issue-presence-avatar";
            img.setAttribute("src", ctx + v.avatarUrl);
            img.setAttribute("alt", "");
            link.appendChild(img);

            var name = document.createElement("span");
            name.className = "issue-presence-name";
            name.textContent = v.name;
            link.appendChild(name);

            item.appendChild(link);

            var idle = document.createElement("span");
            idle.className = "issue-presence-idle";
            idle.textContent = fmt(TEXT.activeAgo, formatIdle(v.idleSeconds));
            item.appendChild(idle);

            list.appendChild(item);
        });
        root.appendChild(list);
    }

    var currentInterval = INTERVAL_ALONE;

    function schedule(delay) {
        window.clearTimeout(window.__issuePresenceTimer);
        window.__issuePresenceTimer = window.setTimeout(poll, delay);
    }

    function poll() {
        // Panel gone (issue changed, page rebuilt): stop the chain.
        // The element is looked up on every run - after a re-render the old
        // reference points at a node no longer attached to the document.
        var el = document.getElementById(ROOT_ID);
        if (!el) {
            window.clearTimeout(window.__issuePresenceTimer);
            window.__issuePresenceTimer = null;
            return;
        }
        root = el;

        // Tab in the background: issue no request, but keep the chain alive
        // so it resumes immediately when the user comes back.
        if (document.visibilityState === "hidden") {
            schedule(currentInterval);
            return;
        }

        var issueKey = resolveIssueKey();
        if (!issueKey) {
            // Not resolvable yet: wait briefly and retry rather than leaving
            // an error message on screen straight away.
            keyAttempts++;
            if (keyAttempts < MAX_KEY_ATTEMPTS) {
                schedule(300);
                return;
            }
            message(TEXT.noIssueKey);
            schedule(currentInterval);
            return;
        }
        keyAttempts = 0;

        // Issue changed without a page reload: unregister from the old one.
        if (currentKey && currentKey !== issueKey) {
            sendLeave(currentKey);
        }
        currentKey = issueKey;

        fetch(ENDPOINT + "?issueKey=" + encodeURIComponent(issueKey), {
            credentials: "same-origin",
            headers: { "X-Atlassian-Token": "no-check", "Accept": "application/json" }
        }).then(function (r) {
            if (!r.ok) {
                console.warn("[issue-presence] HTTP " + r.status);
                message(fmt(TEXT.unavailable, r.status));
                return null;
            }
            return r.json();
        }).then(function (d) {
            if (d) {
                render(d);
                var others = (d.viewers || []).filter(function (v) { return !v.self; }).length;
                currentInterval = others > 0 ? INTERVAL_ACTIVE : INTERVAL_ALONE;
            }
            schedule(currentInterval);
        }).catch(function (e) {
            console.warn("[issue-presence] request failed", e);
            schedule(currentInterval);
        });
    }

    poll();

    // Returning to the tab: refresh at once instead of waiting out the interval.
    document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "visible"
                && document.getElementById(ROOT_ID)) {
            schedule(0);
        }
    });

    // Unregister when the page is left.
    // fetch(keepalive) rather than navigator.sendBeacon on purpose:
    // sendBeacon always sends POST, but the endpoint is GET only.
    window.addEventListener("pagehide", function () {
        sendLeave(currentKey);
    });
})();
'''

String css = '''
<style type="text/css">
#issue-presence-root .issue-presence-list { display: flex; flex-direction: column; gap: 4px; }
#issue-presence-root .issue-presence-item { display: flex; align-items: center;
    flex-wrap: wrap; gap: 4px 6px; }
#issue-presence-root .issue-presence-link { display: flex; align-items: center; gap: 6px;
    text-decoration: none; }
#issue-presence-root a.issue-presence-link:hover .issue-presence-name { text-decoration: underline; }
#issue-presence-root .issue-presence-avatar { width: 20px; height: 20px; border-radius: 50%; }
#issue-presence-root .issue-presence-name { font-size: 12px; }
#issue-presence-root .issue-presence-idle { font-size: 11px; color: #6B778C; }
#issue-presence-root .issue-presence-empty { font-size: 12px; color: #6B778C; }
</style>
'''

// Starts the code from the text/plain block even where inline scripts are
// not executed. Running twice is harmless - the code clears an existing
// timer itself.
String bootstrap = "var c=document.getElementById('issue-presence-code');" +
                   "if(c){var s=document.createElement('script');" +
                   "s.text=c.textContent;document.head.appendChild(s);" +
                   "s.parentNode.removeChild(s);}"

String html = css +
        '<div id="issue-presence-root" data-issue-key="' + issueKeyAttr + '">' +
        '<div class="issue-presence-empty">' + LOADING_TEXT + '</div></div>' +
        '<script type="text/plain" id="issue-presence-code">' + js + '</script>' +
        '<img src="data:," alt="" style="display:none" onerror="' + bootstrap + '"/>'

// This ScriptRunner version renders through the 'writer' from the binding.
// The else branch is the fallback for versions that evaluate the return value.
if (binding.hasVariable('writer')) {
    ((Writer) binding.getVariable('writer')).write(html)
} else {
    return html
}
