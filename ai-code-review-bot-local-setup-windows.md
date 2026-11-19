# Running AI Code Review Bot on a Windows laptop (step by step)

This guide takes you from "I just got the code" to "the bot reviews my pull request".
Every command is for **PowerShell**. Lines starting with `>` in the boxes are what you type.

```
Time needed : about 45 minutes the first time
Cost        : free, except real AI reviews (Claude API, a few cents per PR, needs about $5 credit)
```

---

## Part 0 - Install the tools (one time)

Open **PowerShell** (Start menu -> type "PowerShell") and install what is missing:

```powershell
winget install --id Git.Git -e
winget install EclipseAdoptium.Temurin.21.JDK
winget install Docker.DockerDesktop
winget install OpenJS.NodeJS.LTS
```

Optional, for editing the code: IntelliJ IDEA Community (`winget install JetBrains.IntelliJIDEA.Community`).

**Close PowerShell and open a new window** (new installs are only found by new windows). Check:

```powershell
git --version        # git version 2.x
java -version        # must say 21
docker --version     # Docker version ...
node --version       # v20 or newer
```

Then start **Docker Desktop** from the Start menu and wait until it says "Engine running" (bottom-left).

> "git is not recognized" right after installing -> you are in an old PowerShell window. Close it and open a new one.

---

## Part 1 - Get the code

```powershell
mkdir C:\Projects -Force
cd C:\Projects
git clone https://github.com/addepallijayanth/ai-code-review-bot.git
cd C:\Projects\ai-code-review-bot
```

Check you are in the right folder: `ls` must show `pom.xml`, `README.md`, `mvnw`, `docker-compose.yml`, `reviewer-app`.

> If you downloaded a ZIP instead, it may extract into a folder inside a folder
> (`ai-code-review-bot\ai-code-review-bot`). `cd` into the inner one - the one that has `pom.xml`.

---

## Part 2 - Build and run the tests (no accounts needed)

```powershell
.\mvnw.cmd verify
```

First run downloads dependencies (a few minutes). Wait for:

```
[INFO] AI Code Review Bot ......... SUCCESS
[INFO] Reviewer App ............... SUCCESS
[INFO] ReviewBot MCP Server ....... SUCCESS
[INFO] ReviewBot Evaluation ....... SUCCESS
[INFO] BUILD SUCCESS
```

This already proves the whole review flow works (the tests simulate GitHub and Claude).
Docker Desktop must be running, because the integration tests start PostgreSQL and Redis in Docker.

---

## Part 3 - Create a webhook channel on smee.io

GitHub has to send events to your laptop. smee.io forwards them.

1. Open **https://smee.io** -> click **Start a new channel**.
2. Copy the **Webhook Proxy URL** at the top, e.g. `https://smee.io/AbC123xyz`. Keep this tab open.

Ignore the example commands on that page (they use `localhost:3000/events`); this project uses a different address (Part 7).

---

## Part 4 - Create the GitHub App

GitHub -> profile picture -> **Settings** -> **Developer settings** (bottom of left menu) -> **GitHub Apps** -> **New GitHub App**.
Direct link: https://github.com/settings/apps/new

Fill in:

| Field | What to enter |
|---|---|
| GitHub App name | Something unique, lowercase, e.g. `yourname-reviewbot` |
| Homepage URL | Your repo URL, e.g. `https://github.com/<you>/ai-code-review-bot` |
| Webhook -> Active | Ticked |
| Webhook URL | The smee URL from Part 3, **exactly as it is** (do not add anything) |
| Secret | A random value you generate (see below) - **do not leave empty** |
| SSL verification | Enable (default) |

**Generate the secret** in PowerShell and copy the output:

```powershell
[guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
```

Paste it into **Secret** and also save it in Notepad - you need it again in Part 6.
GitHub never shows a saved secret again; if you lose it, generate a new one and put it in both places.

**Permissions -> Repository permissions** (click the arrows to expand):

| Permission | Access |
|---|---|
| Contents | Read-only |
| Issues | Read-only |
| Pull requests | **Read and write** (the bot cannot post comments with read-only) |
| Metadata | Read-only (already set) |

Leave Organization / Account / Enterprise permissions untouched.

**Subscribe to events** (these appear only after setting the permissions above) - tick exactly these three:

- [x] Pull request
- [x] Pull request review comment
- [x] Issue comment

**Where can this GitHub App be installed?** -> **Only on this account** -> **Create GitHub App**.

---

## Part 5 - Collect the App ID, slug and private key

You land on the App's settings page (later: https://github.com/settings/apps -> click your App).

| Value | Where to find it |
|---|---|
| **App ID** | Under "About", e.g. `1234567`. (Not the Client ID below it.) |
| **Slug** | Last part of the breadcrumb / address bar: `github.com/settings/apps/<slug>`. Usually the App name. |
| **Private key** | Scroll to **Private keys** -> **Generate a private key**. A file `<slug>.<date>.private-key.pem` downloads to **Downloads**. |

- The `SHA256:...` text GitHub shows next to the key is only a **fingerprint**. It is *not* the key; do not use it.
- The `.pem` file is the key. You do not "run" it. Keep it **outside** the project folder and never commit, upload or photograph it.
- `github.com/<you>/apps/<slug>` gives a 404 - App pages live under `github.com/settings/apps/...` (settings) or `github.com/apps/<slug>` (public page).

**Install the App on a repository:** left menu -> **Install App** -> **Install** -> **Only select repositories** -> pick a test repo -> **Install**.
(Check it any time at https://github.com/settings/installations -> **Configure**.)

---

## Part 6 - Create the `.env` file

In `C:\Projects\ai-code-review-bot`:

```powershell
copy .env.example .env
```

**1. Put the private key in** (PowerShell writes it as one line, no copy-paste needed). Run both lines; change the file name to yours:

```powershell
$key = (Get-Content "$HOME\Downloads\yourname-reviewbot.2026-01-01.private-key.pem") -join '\n'
(Get-Content .env) -replace '^GITHUB_PRIVATE_KEY=.*', "GITHUB_PRIVATE_KEY=$key" | Set-Content .env
```

Check:

```powershell
Select-String GITHUB_PRIVATE_KEY= .env
```

It must show one long line starting `GITHUB_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----\n`.

> If PowerShell suddenly asks `Path[0]:` - a word was left out of the command. Press **Ctrl+C**
> (do not type anything) and copy the command again in full. The first line must start with `$key =`.

**2. Fill in the rest:**

```powershell
notepad .env
```

```
GITHUB_APP_ID=<App ID from Part 5>
GITHUB_APP_SLUG=<slug from Part 5>
GITHUB_PRIVATE_KEY=-----BEGIN RSA ...   <- already filled, do not touch (Notepad wraps it, it is one line)
GITHUB_PRIVATE_KEY_PATH=                <- leave EMPTY
WEBHOOK_SECRET=<the secret from Part 4, exactly the same>
ANTHROPIC_API_KEY=<your Claude API key, or dummy-no-reviews>
DASHBOARD_USERNAME=admin
DASHBOARD_PASSWORD=<any password - not one you use elsewhere>
```

No spaces around `=`, no quotes. **Ctrl+S** to save.

**About `ANTHROPIC_API_KEY`:**

- The app does not start if it is empty.
- **Free / just looking:** put `dummy-no-reviews`. Everything runs (dashboard, webhooks), but reviews fail and no comments are posted.
- **Real reviews:** https://console.anthropic.com -> Billing (add about $5 credit) -> API Keys -> Create Key. It starts with `sk-ant-` and is shown only once.
  This is separate from a Claude.ai subscription.

`.env` is in `.gitignore`, so it is never committed.

---

## Part 7 - Start everything (two PowerShell windows)

**Window 1 - the app, PostgreSQL and Redis** (Docker Desktop must be running):

```powershell
cd C:\Projects\ai-code-review-bot
docker compose up --build
```

First time takes a few minutes. Ready when you see:

```
Tomcat started on port 8080 (http)
Started ReviewBotApplication in ... seconds
```

Keep this window open. The letters at the bottom (`v` `o` `w` `d`) are optional shortcuts - you do not need them.

**Window 2 - forward GitHub webhooks to the app** (use your smee URL):

```powershell
npx smee-client --url https://smee.io/AbC123xyz --target http://localhost:8080/api/github/webhook
```

- First time it asks `Ok to proceed? (y)` -> type `y`.
- Ready when it says `Forwarding https://smee.io/... to http://localhost:8080/api/github/webhook`.
- **The target is `http://`, not `https://`.** Only the smee URL is https.
- Keep this window open. `http://localhost:8080/api/github/webhook` is not a page to open in the browser.

**Check the app is healthy** (any third window):

```powershell
curl.exe http://127.0.0.1:8080/actuator/health
```

Expected: `{"status":"UP","groups":["liveness","readiness"]}`

---

## Part 8 - Open the dashboard

Browser -> type in full: **`http://127.0.0.1:8080/dashboard`** -> log in with `admin` and your `DASHBOARD_PASSWORD`.

**"This site can't provide a secure connection" / `ERR_SSL_PROTOCOL_ERROR`:**
the browser turned `http` into `https`. The app is fine (the Docker log shows
`Invalid character found in method name [0x16 0x03 0x01...]` when this happens). Fix:

1. Chrome -> `chrome://settings/security` -> turn **off** "Always use secure connections".
2. `chrome://net-internals/#hsts` -> "Delete domain security policies" -> `localhost` -> Delete.
3. Close Chrome completely, reopen, type `http://127.0.0.1:8080/dashboard` (do not pick an address-bar suggestion).
4. Or use Edge InPrivate (Ctrl+Shift+N).

Check without a browser: `curl.exe -u admin:<password> http://127.0.0.1:8080/dashboard` prints HTML.

---

## Part 9 - Test with a pull request

In the repo the App is installed on (on GitHub):

1. Click **README.md** -> **pencil icon** (Edit this file).
2. Add any line, e.g. `Testing the review bot`.
3. **Commit changes...** -> select **"Create a new branch for this commit and start a pull request"** -> **Propose changes**.
4. **Create pull request**. Do not add reviewers - the bot is triggered automatically by the webhook.

What you should see:

| Where | What |
|---|---|
| smee window | `POST http://localhost:8080/api/github/webhook - 202` (202 = accepted) |
| smee.io tab | the event listed |
| Docker window | the worker picking up the review job |
| The PR (real API key only) | within about a minute: inline comments + a summary from `<slug>[bot]` |
| Dashboard | the PR, findings, cost and latency |

**Chat with the bot** (real API key only) - comment on the PR:

```
@<slug> explain 1       explain finding #1
@<slug> fix 1           propose a fix for finding #1
```

When done testing: open the PR -> **Close pull request** (do not merge).

---

## Part 10 - Stop and start again

| What | How |
|---|---|
| Stop the app | Window 1: **Ctrl+C**, or `docker compose down`, or the Stop button in Docker Desktop |
| Stop smee | Window 2: **Ctrl+C** |
| Start again | `docker compose up` (add `--build` only after code changes) + the smee command |
| Change `.env` | edit, save, then `docker compose down` and `docker compose up` |

Data (reviews, findings) is kept in Docker volumes between restarts.

---

## Part 11 - Working with git (PowerShell or IntelliJ)

First time on a new machine:

```powershell
git config --global user.name "Your Name"
git config --global user.email "your-personal@email.com"
```

| What | PowerShell | IntelliJ |
|---|---|---|
| Get latest | `git pull` | Ctrl+T |
| New branch | `git checkout -b my-change` | branch name (bottom-right) -> New Branch |
| Commit | `git add .` then `git commit -m "message"` | Ctrl+K |
| Push | `git push` (first time on a branch: `git push -u origin my-change`) | Ctrl+Shift+K |
| Pull request | on github.com: "Compare & pull request" | Git -> GitHub -> Create Pull Request |

IntelliJ: File -> Open -> `C:\Projects\ai-code-review-bot`; set Project SDK to Java 21; log in to GitHub when it asks.

Never commit: `.env`, the `.pem` file, `target/` folders (`.env` and `target/` are already ignored).

**Push says "Repository not found":** the repo does not exist on GitHub yet (create it empty, no README),
or you signed in as another account: `cmdkey /delete:git:https://github.com`, then push again and sign in as the right account.

---

## Quick reference - what goes where

| Value | Comes from | Goes into |
|---|---|---|
| smee URL | smee.io | GitHub App "Webhook URL" + smee command |
| Webhook secret | you generate it (PowerShell) | GitHub App "Secret" **and** `.env` `WEBHOOK_SECRET` (must match) |
| App ID | GitHub App page, "About" | `.env` `GITHUB_APP_ID` |
| Slug | GitHub App URL / breadcrumb | `.env` `GITHUB_APP_SLUG`, and `@<slug>` commands |
| Private key | `.pem` in Downloads | `.env` `GITHUB_PRIVATE_KEY` (via the PowerShell command) |
| Claude API key | console.anthropic.com | `.env` `ANTHROPIC_API_KEY` |
| Dashboard password | you choose | `.env` `DASHBOARD_PASSWORD` |

## Security checklist

- Never share, commit or photograph `.env`, the `.pem` file, the webhook secret or the API key.
- If the private key was exposed: GitHub App -> Private keys -> generate a new one, delete the old one, redo Part 6 step 1.
- Not using the bot any more: GitHub App settings -> Advanced -> **Delete GitHub App**, and delete the `.pem` file.
