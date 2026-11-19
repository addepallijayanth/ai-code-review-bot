# Install guide

1. [Create the GitHub App](#1-create-the-github-app)
2. [Configure the service](#2-configure-the-service)
3. [Run it locally](#3-run-it-locally)
4. [Deploy to Azure Container Apps](#4-deploy-to-azure-container-apps)
5. [MCP server](#mcp-server)
6. [Troubleshooting](#troubleshooting)

## 1. Create the GitHub App

On GitHub, open **Settings → Developer settings → GitHub Apps → New GitHub App** (for an organization: the organization's settings page).

| Setting | Value |
|---|---|
| GitHub App name | Anything unique, e.g. `acme-reviewbot`. Its URL slug is what you put in `GITHUB_APP_SLUG`; the bot comments as `<slug>[bot]`. |
| Homepage URL | Your repository or deployment URL |
| Webhook | Active |
| Webhook URL | `https://<your-host>/api/github/webhook` (for local development, a smee.io or ngrok URL, see below) |
| Webhook secret | A random string, e.g. `openssl rand -hex 32`. The same value goes into `WEBHOOK_SECRET`. |

**Repository permissions** (least privilege):

| Permission | Access | Why |
|---|---|---|
| Pull requests | Read and write | Read the diff, post reviews and replies |
| Contents | Read-only | Read files, the tree and `.reviewbot.yml` |
| Issues | Read-only | Receive `issue_comment` webhooks for `@reviewbot explain 3` on the conversation tab |
| Metadata | Read-only | Mandatory for every App |

**Subscribe to events:** Pull request, Pull request review comment, Issue comment. Installation events are always sent.

Choose whether the App can be installed on **any account** (so others can install it) or only yours, then **Create GitHub App**. On the App's page:

1. Note the **App ID**.
2. Under **Private keys**, click **Generate a private key**. A `.pem` file downloads; keep it out of the repository.
3. Click **Install App** and pick the repositories to review.

## 2. Configure the service

Copy `.env.example` to `.env`:

| Variable | Value |
|---|---|
| `GITHUB_APP_ID` | The App ID |
| `GITHUB_APP_SLUG` | The App's slug |
| `GITHUB_PRIVATE_KEY` | The PEM file on one line, with `\n` between lines (see below), **or** leave it empty and set `GITHUB_PRIVATE_KEY_PATH` to the path of a mounted `.pem` file |
| `WEBHOOK_SECRET` | The webhook secret |
| `ANTHROPIC_API_KEY` | Your Claude API key |
| `DASHBOARD_USERNAME` / `DASHBOARD_PASSWORD` | Login for `/dashboard`. Without a password, Spring Security generates one and logs it at startup. |

Turning the PEM into one line:

```bash
awk 'NF {sub(/\r/, ""); printf "%s\\n", $0}' acme-reviewbot.private-key.pem
```

```powershell
(Get-Content .\acme-reviewbot.private-key.pem) -join '\n'
```

Both PKCS#1 (`BEGIN RSA PRIVATE KEY`, what GitHub generates) and PKCS#8 (`BEGIN PRIVATE KEY`) keys work.

Everything else (models, effort, budgets, limits, queue settings) has defaults in [`application.yml`](../reviewer-app/src/main/resources/application.yml). Override any of them with an environment variable, for example `REVIEWBOT_REVIEW_BUDGET_MAX_COST_PER_INSTALLATION_PER_DAY_USD=10`.

## 3. Run it locally

```bash
docker compose up --build
```

starts the app on port 8080 with PostgreSQL and Redis. GitHub needs to reach the webhook endpoint, so forward deliveries to your machine:

```bash
# smee.io: create a channel at https://smee.io and use its URL as the App's webhook URL
npx smee-client --url https://smee.io/<channel> --target http://localhost:8080/api/github/webhook

# or ngrok: use https://<id>.ngrok-free.app/api/github/webhook as the webhook URL
ngrok http 8080
```

Open a pull request in a repository where the App is installed. In the App settings, **Advanced → Recent Deliveries** shows each delivery and the app's answer (`queued`, or `ignored` with the reason). The review appears on the pull request within about a minute, and `http://localhost:8080/dashboard` shows it.

To run the app from source instead (with PostgreSQL and Redis from `docker compose up -d postgres redis`, after adding `ports` for them):

```bash
./mvnw -pl reviewer-app -am spring-boot:run
```

## 4. Deploy to Azure Container Apps

The **Deploy** workflow publishes the image to GitHub Container Registry on every `v*` tag and, when configured, rolls it out to Azure Container Apps.

### One-time Azure setup

```bash
az group create --name reviewbot-rg --location westeurope

az postgres flexible-server create --resource-group reviewbot-rg --name reviewbot-db \
  --tier Burstable --sku-name Standard_B1ms --database-name reviewbot \
  --admin-user reviewbot --admin-password '<db-password>'

az containerapp env create --resource-group reviewbot-rg --name reviewbot-env --location westeurope
```

You also need a Redis 6.2+ the app can reach (Azure Managed Redis, or a Redis container in the same environment). Use TLS where the service offers it: `rediss://:<password>@<host>:<port>`.

Keep the secrets in **Azure Key Vault** and reference them from the container app, or pass them as Container Apps secrets:

```bash
az containerapp create --resource-group reviewbot-rg --name reviewbot --environment reviewbot-env \
  --image ghcr.io/<owner>/ai-code-review-bot:1.0.0 --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 3 \
  --secrets github-private-key='<pem-on-one-line>' webhook-secret='<secret>' \
            anthropic-api-key='<key>' db-password='<db-password>' redis-url='rediss://:<password>@<host>:<port>' \
            dashboard-password='<password>' \
  --env-vars GITHUB_APP_ID=<app-id> GITHUB_APP_SLUG=<slug> \
             GITHUB_PRIVATE_KEY=secretref:github-private-key WEBHOOK_SECRET=secretref:webhook-secret \
             ANTHROPIC_API_KEY=secretref:anthropic-api-key \
             SPRING_DATASOURCE_URL='jdbc:postgresql://reviewbot-db.postgres.database.azure.com:5432/reviewbot?sslmode=require' \
             SPRING_DATASOURCE_USERNAME=reviewbot SPRING_DATASOURCE_PASSWORD=secretref:db-password \
             SPRING_DATA_REDIS_URL=secretref:redis-url DASHBOARD_PASSWORD=secretref:dashboard-password \
             LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
```

- If the GHCR package is private, add registry credentials (`az containerapp registry set`) or make the package public.
- Point the liveness probe at `/actuator/health/liveness` and the readiness probe at `/actuator/health/readiness`.
- Keep at least one replica: the review workers run inside the app and need to be up to drain the queue.
- Set the GitHub App's webhook URL to `https://<app-fqdn>/api/github/webhook`.

### Let GitHub Actions deploy

1. Create a Microsoft Entra app registration with a **federated credential** for this repository's `production` environment, and give it the *Contributor* role on `reviewbot-rg`.
2. In the repository settings add the secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` and the variables `AZURE_CONTAINER_APP=reviewbot` and `AZURE_RESOURCE_GROUP=reviewbot-rg`.
3. Tag a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Without the `AZURE_CONTAINER_APP` variable, the workflow only publishes the image.

## MCP server

`mcp-server` exposes the bot's GitHub tools (`read_file`, `search_code`, `list_prs`) over MCP on stdio.

```bash
./mvnw -pl mcp-server -am package -DskipTests
```

It authenticates with a **fine-grained personal access token** (`GITHUB_TOKEN`) with read-only *Contents* and *Pull requests* access to the repositories you want to explore. Public repositories work without one, at a lower rate limit.

**Claude Code:**

```bash
claude mcp add reviewbot-github --env GITHUB_TOKEN=<token> -- java -jar /path/to/mcp-server/target/mcp-server-1.0.0.jar
```

**Claude Desktop** (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "reviewbot-github": {
      "command": "java",
      "args": ["-jar", "/path/to/mcp-server/target/mcp-server-1.0.0.jar"],
      "env": { "GITHUB_TOKEN": "<token>" }
    }
  }
}
```

The server logs to `reviewbot-mcp-server.log` in the system temp directory, because stdout carries the protocol.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Deliveries show `401 invalid signature` | `WEBHOOK_SECRET` differs from the secret in the App settings |
| Delivery says `ignored: draft pull request` | Drafts are reviewed once marked *Ready for review* |
| No review and `401 Bad credentials` in the log | Wrong `GITHUB_APP_ID`, or the private key belongs to another App |
| `Resource not accessible by integration` | A permission from the table above is missing, or the App was not re-approved after you changed permissions |
| Review summary says files were skipped | Lockfiles, generated code, binaries, oversized diffs and `ignore:` paths are skipped on purpose |
| "daily review budget ... reached" comment | Raise `REVIEWBOT_REVIEW_BUDGET_MAX_COST_PER_INSTALLATION_PER_DAY_USD` |
| Jobs stuck | Check the dead-letter stream: `redis-cli XRANGE reviewbot:jobs:dead - +` |
