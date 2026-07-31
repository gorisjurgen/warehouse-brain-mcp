# warehouse-brain-mcp

Spring Boot MCP server that exposes the **Warehouse Brain** — a Notion PARA
workspace (Projects and Archives databases, projects linked to Jira tickets) —
as MCP tools for Claude, plus the Claude skills that use them.

## Tools

| Tool | Description |
|---|---|
| `findProject` | Find a project by Jira ticket key; searches Projects **and** Archives |
| `createProject` | Create a project page from the Warehouse Project Template with Jira + Started + title set (duplicate-guarded) |
| `archiveProject` | Move a project page from Projects to Archives (PARA archive, not trash) |
| `listActiveProjects` | List active projects, most recently started first |

Skills in [.claude/skills/](.claude/skills/) (`warehouse-project-start`,
`warehouse-project-archive`) drive these tools; the server is registered for
Claude Code via [.mcp.json](.mcp.json) (Streamable HTTP on `http://localhost:8817/mcp`).

## Prerequisites

- Java 21, Maven 3.9+
- A Notion **internal integration** ([notion.so/profile/integrations](https://www.notion.so/profile/integrations))
  that has been given access to the Warehouse Brain Projects and Archives
  databases (and the Warehouse Project Template): in Notion, open each
  database → `...` → *Connections* → add your integration.

## Configuration

Only the API key is required from the environment; workspace IDs have defaults
in [application.yml](src/main/resources/application.yml) and can be overridden.

| Env var | Required | Meaning |
|---|---|---|
| `NOTION_API_KEY` | yes | Internal integration secret (`ntn_...`) |
| `NOTION_PROJECTS_DS_ID` | no | Projects **data-source** id (default committed in yml) |
| `NOTION_ARCHIVES_DS_ID` | for `archiveProject` | Archives data-source id |
| `NOTION_TEMPLATE_ID` | for template content | Warehouse Project Template id |
| `MCP_API_KEY` | for remote deployment | API key clients must send; auth is **disabled** when unset |

> **Data-source ids, not database ids.** Since Notion API `2025-09-03` a
> database contains one or more *data sources*, and queries target the data
> source. To find a data-source id:
>
> ```bash
> curl -s https://api.notion.com/v1/databases/<database-id> \
>   -H "Authorization: Bearer $NOTION_API_KEY" \
>   -H "Notion-Version: 2026-03-11" | jq '.data_sources'
> ```
>
> Template ids can be listed via `GET /v1/data_sources/<id>/templates`.

If the `Jira` property in your Projects database is a URL property rather than
text, set `notion.jira-property-type: url` (it drives the query filter shape
and the create payload).

## Run

```bash
mvn spring-boot:run
```

The MCP endpoint is `http://localhost:8817/mcp` (Streamable HTTP). Set
`NOTION_API_KEY` in the environment before starting.

## Configure the server in Claude

The server must be running (`mvn spring-boot:run`) before Claude connects; it
speaks Streamable HTTP on `http://localhost:8817/mcp`.

### Claude Code — this repo

Nothing to do: opening Claude Code in this repo picks up
[.mcp.json](.mcp.json) automatically. On first use Claude Code asks you to
approve the project-scoped server. Verify the connection with:

```bash
claude mcp list
```

### Claude Code — available in every project

To use the Warehouse Brain from any directory, register it in *user* scope
instead:

```bash
claude mcp add --scope user --transport http warehouse-brain http://localhost:8817/mcp
```

(Remove again with `claude mcp remove --scope user warehouse-brain`.)

### Claude Desktop

Settings → Connectors → *Add custom connector*, name it `warehouse-brain` and
enter `http://localhost:8817/mcp` as the URL. The desktop app runs locally, so
it can reach localhost; the claude.ai website cannot.

### Skills

The skills in [.claude/skills/](.claude/skills/) are project-scoped — Claude
Code loads them when working in this repo:

- `/warehouse-project-start` — start a warehouse project for a Jira ticket
- `/warehouse-project-archive` — archive a finished project

To have them available everywhere (matching the user-scoped server
registration), copy the skill folders to `~/.claude/skills/`.

## Deployment (Hetzner / Docker)

The repo ships a multi-stage [Dockerfile](Dockerfile) and
[docker-compose.yml](docker-compose.yml). On the server:

```bash
git clone <repo> && cd warehouse-brain-mcp
cp .env.example .env   # fill in NOTION_API_KEY and MCP_API_KEY
docker compose up -d --build
```

Generate a strong API key with:

```bash
openssl rand -hex 32
```

When `MCP_API_KEY` is set, every request must carry it as
`Authorization: Bearer <key>` (or `X-API-Key: <key>`); everything else gets
`401`. When it is unset the server logs a warning and accepts all requests —
fine locally, never for an internet-facing deployment.

Client registration then looks like:

```json
{
  "mcpServers": {
    "warehouse-brain": {
      "type": "http",
      "url": "http://<hetzner-host>:8817/mcp",
      "headers": { "Authorization": "Bearer <MCP_API_KEY>" }
    }
  }
}
```

> **The key travels in cleartext.** This setup is plain HTTP, so anyone on the
> network path can read the key and the Notion payloads. Mitigate by
> restricting port 8817 to known IPs with a Hetzner Cloud Firewall, or put a
> TLS-terminating reverse proxy (Caddy/nginx) in front.

## Verify

```bash
mvn clean verify
```

Manual smoke test against the running server:

```bash
npx @modelcontextprotocol/inspector
```

Connect with transport *Streamable HTTP* to `http://localhost:8817/mcp`, list
tools, and invoke `listActiveProjects`.

## Layout

```
src/main/java/com/brutex/warehousebrain/
├── WarehouseBrainApplication.java   Spring Boot entry point
├── config/NotionProperties.java     notion.* configuration (validated at startup)
├── config/NotionClientConfig.java   RestClient with auth/version headers + error mapping
├── notion/NotionClient.java         thin Notion REST wrapper (query/create/move/retrieve)
├── notion/NotionException.java      Notion error code + message
└── service/WarehouseBrainService.java  the four @McpTool methods
```
