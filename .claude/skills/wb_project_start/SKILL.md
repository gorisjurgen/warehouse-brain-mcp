---
name: wb_project_start
description: starts a warehouse project from a Jira ticket - verifies the WHS ticket is assigned to me via Rovo/Atlassian, then creates the Notion project from the Warehouse Project Template with the Jira description copied in. Use when the user runs /wb_project_start with a ticket number.
---

# Warehouse Project Start from Jira

Input: {ticket} — a WHS ticket number (`5761`) or full key (`WHS-5761`).
Uses the Atlassian/Rovo MCP tools for Jira and the `warehouse-brain` MCP tools
for Notion.

## Instructions

### Step 1: Normalize the ticket key
A bare number like `5761` becomes `WHS-5761`. Any other project prefix is
kept as given but the ticket must live in the WHS project (Step 2 enforces
this).

### Step 2: Verify the ticket is assigned to me
Call `searchJiraIssuesUsingJql` with (using WHS-5761 as example):

```
key = "WHS-5761" AND project = WHS AND assignee = currentUser()
```

requesting fields `summary`, `description`, `status`.

- **No result:** STOP. Tell the user there is no such WHS ticket assigned to
  them. (Optionally call `getJiraIssue` on the key: if the ticket exists but
  belongs to someone else, say so; if it doesn't exist at all, say that.)
- **Result found:** continue with the summary and description.

### Step 3: Create the project
Call `createProject` with:

- `jiraTicket`: the normalized key
- `title`: the Jira ticket summary
- `ticketUrl`: the browse URL, e.g. `https://brutex.atlassian.net/browse/WHS-5761`
  (take the site from the Jira search result or `getAccessibleAtlassianResources`)

The tool refuses duplicates, creates the page from the Warehouse Project
Template, and sets started = today, the Jira property, the ticket URL and
State = "Not started".

### Step 4: Copy the Jira description (only if the ticket has one)
The template body is applied asynchronously by Notion, so first wait for it:
poll `getProjectContent` for the ticket every few seconds (up to ~30 s) until
the content contains the heading `Project Description`.

Then convert the Jira description to Markdown — supported subset: headings
1-3, paragraphs, bulleted/numbered lists, to-dos, fenced code, quotes,
dividers (tables and images are not supported; summarize those as text) — and
call `updateProjectContent` with:

- `mode`: `append`
- `afterHeading`: `Project Description`
- `content`: the converted description

If the template never populates, append the description at the page end
(no `afterHeading`) and mention that in the report.

### Step 5: Report
Report the new page URL, the title, State ("Not started") and whether the
Jira description was copied into the Project Description section.
