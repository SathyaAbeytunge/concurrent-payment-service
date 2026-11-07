# Deploy the demo: Render + Supabase

This setup uses three services:

| Service | Host | What it does |
| --- | --- | --- |
| Spring Boot application | Render Free Web Service | Runs the Java API |
| PostgreSQL | Supabase Free | Stores accounts, transfers, and ledger entries |
| Redis-compatible cache | Render Free Key Value | Caches transfer results |

Choose the Free plan for each service. Free plans have usage limits: Render's
web service sleeps after inactivity, its free cache can lose data on restart,
and inactive Supabase free projects can pause. PostgreSQL remains the source of
truth when cached results are lost. Check the providers' plan screens before
creating services; this repository does not create a paid service.

This is a public learning demo with no authentication. Use fictional users and
demo balances only. Anyone who can reach the API can call its endpoints.

## 1. Understand the new files

- `Dockerfile` builds the application and packages it with Java 21. The final
  container runs as a non-root user, with memory settings for a small instance.
- `.dockerignore` sends only the build inputs to Docker, excluding Git history,
  local settings, build output, and credential files.
- `compose.app.yml` adds the application to the local PostgreSQL and Redis setup.
- `application-render.properties` reads hosted connection details from environment
  variables and uses the port assigned by Render.
- `render.yaml` is a Blueprint: instructions for creating the two Render services.

## 2. Run the container locally

Stop any application already using port 8080. With Docker running, execute:

```powershell
docker compose -f docker-compose.yml -f compose.app.yml up --build -d
docker compose -f docker-compose.yml -f compose.app.yml logs -f app
```

Once Spring Boot has started, open:

```text
http://localhost:8080/actuator/health
```

It should return `{"status":"UP"}`. You can also use the account and transfer
examples in the README against this container. This local command uses your
local database, not Supabase.

To stop the containers without deleting data:

```powershell
docker compose -f docker-compose.yml -f compose.app.yml stop
```

## 3. Push, review, and merge this branch

Push the deployment changes and open a pull request into `main`. CI now runs the
full test suite and checks that the Docker image builds. Merge only after CI
passes. If your account-ledger feature has a separate open pull request, merge
that first: the deployment branch was created from it.

The Blueprint deploys `main`, so merge these files before connecting Render.

## 4. Create the Supabase database

1. Sign up at <https://supabase.com/> and create a **Free** project dedicated to
   this demo. Choose a region close to Singapore, where the Render services run.
2. Choose a database password and keep it in your password manager.
3. Once the project is ready, select **Connect** and choose **Session pooler**.
   Use session mode on port `5432`, not transaction mode on port `6543`.
4. Copy the host, database name, and username shown in that panel. The username
   commonly looks like `postgres.<project-reference>`; use the exact value shown.

The session pooler supports IPv4 connections from a persistent application.
The direct connection may require IPv6, depending on your plan and settings.
See [Supabase connection options](https://supabase.com/docs/guides/database/connecting-to-postgres).

Prepare these values for Render:

| Variable | Value |
| --- | --- |
| `DB_JDBC_URL` | `jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=require` |
| `DB_USERNAME` | The session-pooler username from Supabase |
| `DB_PASSWORD` | Your database password |

Use the actual database name if it differs from `postgres`. The URL must start
with `jdbc:postgresql://`, not just `postgresql://`. Keep the password separate
from the URL. These are database credentials, not Supabase's public API key.

Do not paste credentials into Git, pull requests, CI logs, or chat. Render will
prompt for them in the next step.

### Where the application tables go

On first startup, Flyway creates a dedicated `payments` schema and runs the
existing migration there. The Render profile uses that schema for database
queries. No SQL needs to be pasted into Supabase's SQL editor.

Keep `payments` out of Supabase's Data API exposed schemas. The Spring Boot API
connects directly through JDBC and does not need the Supabase Data API. In the
SQL editor or table browser, select `payments` to inspect the application tables.

## 5. Create the Render services

1. Sign up at <https://render.com/> and connect your GitHub account.
2. In the dashboard, choose **New > Blueprint** and select this repository.
3. Use branch `main` and the root `render.yaml` file.
4. Review the resources: `concurrent-payment-service` and `payment-cache` must
   both show the **Free** plan and the same region.
5. Enter `DB_JDBC_URL`, `DB_USERNAME`, and `DB_PASSWORD` when prompted.
6. Create the Blueprint. Render creates the cache, builds the Docker image,
   and starts the application.

The Blueprint automatically sets `SPRING_PROFILES_ACTIVE=render` and connects
`REDIS_URL` to the cache's private connection URL. You do not need a Redis secret
in GitHub. The empty cache IP allowlist blocks external connections; the web
service connects over Render's private network.

If these service names already belong to another app in your Render workspace,
rename both services in the Blueprint and update the cache reference before
creating it.

## 6. Verify the first deployment

Open the web service in Render and read its deployment logs. After it becomes
healthy, open the service's public URL with `/actuator/health` appended:

```text
https://<your-service>.onrender.com/actuator/health
```

Expect `{"status":"UP"}`. Set `$baseUrl` in the README's PowerShell examples to
your Render URL, then create two demo accounts, fund one, transfer money, and
retry with the same key. The retry should return the original transaction ID.

The health endpoint requires PostgreSQL to be reachable. Redis is excluded from
health status in the Render profile because the application supports falling
back to PostgreSQL when its cache is unavailable.

## 7. How CD works after setup

```text
Push a feature branch -> CI runs -> Review and merge the pull request
    -> CI runs on main -> All checks pass -> Render deploys main
    -> Render checks /actuator/health before sending traffic to the new instance
```

`autoDeployTrigger: checksPass` tells Render to deploy after the linked branch's
CI checks pass. Confirm the service's Auto-Deploy setting says **After CI Checks
Pass**. The initial Blueprint creation starts the first deployment; later
automatic deployments follow this setting.

Render builds its own image from the checked commit. GitHub's Docker build
checks the Dockerfile, but does not publish a container image. No deploy hook
or Render API key is needed in the GitHub workflow for this arrangement.
See [Render's deploy trigger settings](https://render.com/docs/blueprint-spec#autodeploytrigger).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| JDBC URL error | URL starts with `jdbc:postgresql://` and contains `sslmode=require` |
| Database connection fails | Supabase project is active; use the session-pooler host, port 5432, and its exact username |
| Password authentication fails | Use the database password, not an API key |
| App cannot reach Redis | Both Render services are in the same region and `REDIS_URL` is set |
| Health check fails | Read startup logs and check the database connection and migrations |
| First request is slow | A free Render service may be waking from sleep |
| No deploy after a merge | Check CI for the main-branch commit and Render's Auto-Deploy setting |
