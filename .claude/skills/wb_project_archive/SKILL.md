---
name: wb_project_archive
description: archives a warehouse project from Jira in my warehouse PARA setup in Notion, moving it from Projects to Archives. Use when user asks to "archive warehouse project".
---

# Warehouse Project Archive

Use the `warehouse-brain` MCP tools. Input: {jira-ticket}.

## Instructions

### Step 1: Find the project
Call `findProject` with {jira-ticket}.
- If nothing is found, report that and stop.
- If the project is already in Archives, report that it is already archived and stop.

### Step 2: Confirm
Show the user the project title and URL and ask for confirmation before archiving.

### Step 3: Archive
Call `archiveProject` with {jira-ticket}. This moves the page from the Projects
database to the Archives database (the page is not deleted). Report the result.
