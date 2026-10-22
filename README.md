# Issue Presence for Jira Data Center

Shows who currently has a Jira issue open — a "who is viewing this" indicator,
built entirely with ScriptRunner. No plugin build, no Atlassian SDK.

Tested on Jira Data Center 11.3 (two-node cluster) with ScriptRunner 10.x.

## Files

| File | Install as |
|------|------------|
| `issuePresence.groovy` | REST endpoint |
| `issuePresenceBatch.groovy` | REST endpoint |
| `issue-presence-panel.groovy` | Fragment — Show a web panel |
| `issue-list-badges.groovy` | Fragment — Show a web panel |
| `issue-presence-field.groovy` | Custom scripted field |

## What it does

- **Issue panel** — a list of the people currently viewing the issue, with
  avatar, profile link and how long ago they were last active. Updates every
  3 seconds while somebody else is present, every 10 seconds otherwise.
- **List badges** — a small marker next to every issue link in the issue
  navigator when somebody has that issue open. One batch request per poll,
  regardless of list length.
- **Scripted field** — the same information as a column for filters and the
  Filter Results gadget. Server-side rendered, therefore not live.

## What it is not

There is no push. Server-Sent Events and WebSockets would each hold a Tomcat
thread per listener, and a ScriptRunner REST endpoint is not the place for
that — a hundred open tabs would exhaust a default thread pool. Everything
here is short-interval polling.

Presence is tracked only for issues where the panel is installed and was
actually loaded. It is not a general "who is logged in" display.

## How it works

The issue panel sends a heartbeat to a REST endpoint on every poll. The
endpoint writes `issueId|userKey -> timestamp` into an Atlassian cache with a
TTL and returns every non-expired entry for that issue. When the TTL passes
without a new heartbeat, the viewer disappears. Leaving the page unregisters
explicitly, so in practice it is usually immediate.

On a Data Center cluster the cache is `remote()` + `replicateViaCopy()`.
Atlassian's rule for copy-replicated caches is that each key must be written
by exactly one node; including the user key in the cache key satisfies that,
because a user's session is pinned to one node under sticky sessions.

## Installation

### 1. REST endpoints

Jira admin → ScriptRunner → REST Endpoints → Custom endpoint, one entry each:

- `issuePresence.groovy` — needed by the issue panel
- `issuePresenceBatch.groovy` — needed by the list badges

The imports are `jakarta.ws.rs.core.*` for ScriptRunner 10.x / Jira 10+.
For ScriptRunner 8.x/9.x switch to the `javax` variant commented out in the
same file. A wrong choice shows up as `unable to resolve class`.

### 2. Issue panel

ScriptRunner → Fragments → **Show a web panel**

| Field     | Value                                       |
|-----------|---------------------------------------------|
| Location  | `atl.jira.view.issue.right.context`         |
| Weight    | a number, e.g. `100`                        |
| Condition | leave empty, or `true`                      |
| Menu text | the panel heading, e.g. `Who is online`     |
| Provider  | `issue-presence-panel.groovy`               |

Two things that cost time if you get them wrong:

- An editor containing **one blank line** is not an empty condition. It
  evaluates to `null`, which is falsy, and the panel silently never renders.
  Either clear the field completely or enter `true`.
- An empty **weight** can break module registration.

### 3. List badges (optional)

Another **Show a web panel** fragment, provider
`issue-list-badges.groovy`, at any location that renders on the
issue search page. Which locations exist in your Jira version is shown by the
Fragment Locator in ScriptRunner. The script renders nothing visible and does
nothing when it finds no issue links, so an unobtrusive location is fine.

### 4. Scripted field (optional)

ScriptRunner → Fields → Custom scripted field, template **Text Field
(multi-line)**, provider `issue-presence-field.groovy`.

Set **no searcher**. With one, the field is evaluated on every reindex and
stale values land in the index.

## Configuration

Every script carries a block marked `=== CONFIGURATION ===` at the top.

### Values that must match across files

`CACHE_NAME`, `TTL_SECONDS` and `MAX_ENTRIES` appear in both REST endpoints
and in the scripted field. If they differ, the scripts address different
caches and the display stays empty without an error.

### Timing

| Setting           | File                       | Default   |
|-------------------|----------------------------|-----------|
| `INTERVAL_ALONE`  | issue-presence-panel       | 10000 ms  |
| `INTERVAL_ACTIVE` | issue-presence-panel       | 3000 ms   |
| `INTERVAL`        | issue-list-badges          | 5000 ms   |
| `TTL_SECONDS`     | both REST endpoints, field | 25 s      |

`TTL_SECONDS` must be **at least 2.5x `INTERVAL_ALONE`**. Below that, a viewer
who is briefly alone drops out of the cache before the next person can see
them — which produces an intermittently wrong display that is unpleasant to
debug.

Load scales with open tabs, not with the number of issues: one open issue view
produces `60000 / INTERVAL` requests per minute, and each request executes a
Groovy script. Measure on a test system before lowering the intervals on a
busy instance.

### Translation

All user-visible strings sit in a `TEXT` object at the top of the JavaScript
in each fragment; `{0}` placeholders are substituted at runtime. The panel's
initial "Loading ..." string is a Groovy constant in the same file, because it
is rendered server side. The scripted field has `EMPTY_TEXT` and `SEPARATOR`.

## Known limitations

- **Not live in lists rendered server side.** The scripted field column is
  correct when the page loads and decays from there. Use the badges fragment
  for a live display, or lower the gadget's auto refresh.
- **Dashboard gadgets are not covered.** Jira renders gadgets in iframes; a
  script on the dashboard page would have to reach into each iframe, wait for
  it to load and re-apply after every gadget refresh. That couples the code
  tightly to Jira's internal gadget DOM and is likely to break on upgrades.
- **Cache creation is lazy.** Atlassian recommends creating replicated caches
  in a singleton at startup. A Groovy script creates it on first use, so
  `java.rmi.NotBoundException` warnings can appear in the log until the script
  has run once on every node. It heals itself; watch the log after rollout.
- **Clock sync.** Age values are computed from node timestamps, so the nodes
  must run NTP. Data Center requires that anyway.
- **An unattended open tab still counts** as viewing. Hidden tabs are excluded
  via `visibilityState`, but a visible window on a second monitor is not.

## Editing the JavaScript

The JS lives inside a Groovy triple-quoted string, which still processes
escape sequences. Two consequences:

- The JavaScript must contain **no backslash at all**. That is why the issue
  key pattern uses `new RegExp("...")` instead of a regex literal.
- **Three consecutive apostrophes terminate the string**, including inside a
  JS comment.

Each fragment delivers its JavaScript in a `<script type="text/plain">` block
that an `<img src="data:," onerror="...">` promotes to a real script element.
Jira loads some panels via AJAX, and `<script>` tags inserted that way are
never executed by the browser, while `onerror` handlers are.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
