---
name: warehouse-project-start
description: creates a new warehouse project in my warehouse PARA setup in Notion. Use when user asks to "start warehouse project".
---

# Warehouse Project Start

Use the `warehouse-brain` MCP tools. Inputs: {jira-ticket} and {project description}.

## Instructions

### Step 1: Check the project doesn't exist yet
Call `findProject` with {jira-ticket}. It searches both the Projects and the
Archives databases. If a project already exists, report its URL and location
(Projects or Archives) and stop.

### Step 2: Create the project
Call `createProject` with {jira-ticket} and {project description} as the title.
The page is created in the Projects database from the Warehouse Project
Template, with the Jira property and the Started date (today) filled in.

### Step 3: Report
Report the new page URL. Mention that the template content is applied
asynchronously by Notion and may take a moment to appear.
