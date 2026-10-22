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
 * ScriptRunner Fragment - "Show a web panel" - presence badges in issue lists
 * ---------------------------------------------------------------------------
 * Appends a small marker to every issue link in a list when somebody
 * currently has that issue open. Requires issuePresenceBatch.groovy.
 *
 * Install: Jira admin -> ScriptRunner -> Fragments -> Show a web panel
 *   Location:  any location rendered on the issue search page. Which one
 *              exists in your Jira version is shown by the Fragment Locator
 *              in ScriptRunner. The script itself is location agnostic: if it
 *              finds no issue links it does nothing and gets in nobody's way.
 *   Weight:    a number, e.g. 100
 *   Condition: leave EMPTY or enter `true` (a blank line evaluates to null,
 *              which is falsy, and the fragment silently never renders)
 *   Provider:  this script
 *
 * The panel itself renders nothing visible - it only carries the JavaScript.
 * Pick an unobtrusive location.
 *
 * Read only. Looking at a list does not count as viewing those issues.
 *
 * Two traps when editing the JavaScript: the surrounding Groovy string
 * processes escape sequences even with triple quotes, so the JS must contain
 * no backslash; and three consecutive apostrophes terminate the string.
 */

String js = '''
(function () {
    // ==========================================================
    // ===  CONFIGURATION  ======================================
    // ==========================================================

    // Poll interval for lists, in ms. A list matters less than the open
    // issue itself, so this is slower than the 3000 ms used there.
    // One open tab produces 60000/INTERVAL requests per minute, regardless
    // of how many issues the list holds - it is a single batch request.
    var INTERVAL = 5000;

    // The endpoint will not process more keys than this anyway
    // (MAX_KEYS_PER_REQUEST there). Change both together.
    var MAX_KEYS = 100;

    // All user-visible strings. {0} and {1} are replaced at runtime.
    var TEXT = {
        viewing: "{0} currently viewing {1}"
    };

    // ==========================================================
    // ===  NO CHANGES NEEDED BELOW  ============================
    // ==========================================================

    if (window.__issuePresenceListTimer) {
        window.clearTimeout(window.__issuePresenceListTimer);
        window.__issuePresenceListTimer = null;
    }

    var ctx = (window.AJS && AJS.contextPath) ? AJS.contextPath() : "";
    var BADGE_CLASS = "issue-presence-badge";
    var STYLE_ID = "issue-presence-badge-style";

    if (!document.getElementById(STYLE_ID)) {
        var st = document.createElement("style");
        st.id = STYLE_ID;
        st.textContent =
            "." + BADGE_CLASS + "{display:inline-flex;align-items:center;gap:3px;" +
            "margin-left:6px;padding:0 5px;border-radius:8px;background:#E3FCEF;" +
            "color:#006644;font-size:11px;line-height:16px;vertical-align:middle;" +
            "white-space:nowrap;cursor:default;}" +
            "." + BADGE_CLASS + "::before{content:'';width:6px;height:6px;" +
            "border-radius:50%;background:#36B37E;display:inline-block;}";
        document.head.appendChild(st);
    }

    // Collects every issue link on the page. Jira uses different attributes
    // depending on the view, hence several selectors.
    function collectLinks() {
        var found = {};
        var sels = ["a.issue-link[data-issue-key]", "a[data-issue-key]", "a[data-issuekey]"];
        sels.forEach(function (sel) {
            var nodes = document.querySelectorAll(sel);
            for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                var key = el.getAttribute("data-issue-key") || el.getAttribute("data-issuekey");
                if (!key) { continue; }
                if (!found[key]) { found[key] = []; }
                found[key].push(el);
            }
        });
        return found;
    }

    function badgeFor(el) {
        var next = el.nextElementSibling;
        if (next && next.classList && next.classList.contains(BADGE_CLASS)) {
            return next;
        }
        var b = document.createElement("span");
        b.className = BADGE_CLASS;
        if (el.parentNode) {
            el.parentNode.insertBefore(b, el.nextSibling);
        }
        return b;
    }

    function apply(links, data) {
        Object.keys(links).forEach(function (key) {
            var viewers = data[key];
            links[key].forEach(function (el) {
                var existing = el.nextElementSibling;
                var hasBadge = existing && existing.classList
                        && existing.classList.contains(BADGE_CLASS);

                if (!viewers || viewers.length === 0) {
                    if (hasBadge) { existing.parentNode.removeChild(existing); }
                    return;
                }
                var b = badgeFor(el);
                b.textContent = String(viewers.length);
                var names = viewers.map(function (v) { return v.name; }).join(", ");
                b.setAttribute("title",
                        TEXT.viewing.replace("{0}", names).replace("{1}", key));
            });
        });
    }

    function schedule(delay) {
        window.clearTimeout(window.__issuePresenceListTimer);
        window.__issuePresenceListTimer = window.setTimeout(poll, delay);
    }

    function poll() {
        if (document.visibilityState === "hidden") {
            schedule(INTERVAL);
            return;
        }
        var links = collectLinks();
        var keys = Object.keys(links);
        if (keys.length === 0) {
            schedule(INTERVAL);   // a list may still be loading
            return;
        }
        if (keys.length > MAX_KEYS) { keys = keys.slice(0, MAX_KEYS); }

        fetch(ctx + "/rest/scriptrunner/latest/custom/issuePresenceBatch?issueKeys="
                  + encodeURIComponent(keys.join(",")), {
            credentials: "same-origin",
            headers: { "X-Atlassian-Token": "no-check", "Accept": "application/json" }
        }).then(function (r) {
            if (!r.ok) {
                console.warn("[issue-presence-list] HTTP " + r.status);
                return null;
            }
            return r.json();
        }).then(function (d) {
            if (d) { apply(links, d); }
            schedule(INTERVAL);
        }).catch(function (e) {
            console.warn("[issue-presence-list] request failed", e);
            schedule(INTERVAL);
        });
    }

    console.log("[issue-presence-list] active");
    poll();

    // The navigator loads pages and rows via AJAX. After a DOM change wait
    // briefly, then reconcile again.
    var debounce = null;
    var observer = new MutationObserver(function () {
        window.clearTimeout(debounce);
        debounce = window.setTimeout(function () { schedule(0); }, 400);
    });
    observer.observe(document.body, { childList: true, subtree: true });

    document.addEventListener("visibilitychange", function () {
        if (document.visibilityState === "visible") { schedule(0); }
    });
})();
'''

String bootstrap = "var c=document.getElementById('issue-presence-badge-code');" +
                   "if(c){var s=document.createElement('script');" +
                   "s.text=c.textContent;document.head.appendChild(s);" +
                   "s.parentNode.removeChild(s);}"

String html = '<script type="text/plain" id="issue-presence-badge-code">' + js + '</script>' +
              '<img src="data:," alt="" style="display:none" onerror="' + bootstrap + '"/>'

if (binding.hasVariable('writer')) {
    ((Writer) binding.getVariable('writer')).write(html)
} else {
    return html
}
