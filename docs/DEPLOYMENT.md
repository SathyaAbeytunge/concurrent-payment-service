# Deploy the demo: Railway + Supabase + Upstash

This is the current deployment path. Render required a payment card for this
account, so the application now uses Railway's trial instead.

| Component | Provider | Purpose |
| --- | --- | --- |
| Spring Boot application | Railway Trial | Runs the Java API |
| PostgreSQL | Existing Supabase Free project | Stores accounts, transfers, and ledger entries |
| Redis | Upstash Free | Caches completed transfer results |

Railway's trial is a limited credit allowance, not guaranteed ongoing free
hosting. Watch the Usage page and stop or delete the demo service when you no
longer need it. Use the limits shown in your own Railway dashboard; this image
has been tested with a 512 MB memory limit. Upstash's Free databases do not
require a card. Select Free when creating the cache and do not enable a paid plan.

This public learning API has no authentication. Use fictional users and demo
balances only.

## 1. Push and merge the Railway changes

The `cloud` profile reads hosted connection settings from environment variables.
Your local `application.properties` stays unchanged. The Dockerfile is already
suitable for Railway and is detected automatically.

```powershell
git add README.md docs/DEPLOYMENT.md render.yaml src/main/resources src/test/java/com/sathya/payment
git commit -m "Adapt deployment for Railway and Upstash"
git push -u origin feature/railway-deployment
```

Open a pull request into `main`, wait for CI to pass, review, and merge. Railway
will deploy `main`, so merge the configuration before creating the service.

## 2. Keep the Supabase project you already created

In Supabase, open **Connect > Session pooler**. Keep the host, database name,
username, and your database password ready. Session mode uses port `5432` and
supports IPv4 connections from this Java server.

Prepare:

| Variable | Value |
| --- | --- |
| `DB_JDBC_URL` | `jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=require` |
| `DB_USERNAME` | The exact session-pooler username, commonly `postgres.<project-ref>` |
| `DB_PASSWORD` | Your project's database password, not a Supabase API key |

Use the displayed database name if it differs from `postgres`. Keep the password
separate from the JDBC URL so it does not need URL encoding. Enter these values
only in Railway's Variables screen, not in source files, Git, or chat.

Flyway creates the application's tables in a dedicated `payments` schema on
startup. You do not need to create the tables manually. Keep `payments` out of
Supabase's Data API exposed schemas: the application uses JDBC directly.

See [Supabase connection options](https://supabase.com/docs/guides/database/connecting-to-postgres).

## 3. Create the free Upstash Redis database

1. Open <https://console.upstash.com/> and sign in.
2. Create a Redis database named `payment-cache`.
3. Choose the **Free** plan and a region close to your Railway service and
   Supabase project, such as Singapore where available.
4. Open the database's connection panel and find the Redis TCP connection
   details: endpoint, port, username, and password.
5. Copy its TLS Redis URL if provided. Its format is:

```text
rediss://default:<redis-password>@<redis-endpoint>:<redis-port>
```

Use the actual username and port shown in Upstash. The two `s` characters in
`rediss://` enable TLS. If the panel gives a `redis://` URL with a separate
`--tls` option, use the same credentials and endpoint with `rediss://` for this
application. If you assemble a URL and its username or password contains special
characters, percent-encode those credential components.

This complete Redis URL becomes the `REDIS_URL` variable in Railway. It contains
a password, so treat it as a secret. Do not use an `https://` REST URL or a REST
token: this application uses Spring's Redis TCP client, not Upstash's REST SDK.
No extra Java library or npm package is needed.

See [connecting Redis clients to Upstash](https://upstash.com/docs/redis/howto/connect-client).

## 4. Create the Railway application service

1. In Railway, choose **New Project > Deploy from GitHub repo** (or the matching
   GitHub repository deployment option).
2. Connect GitHub and select `concurrent-payment-service`.
3. Select `main` as the source branch.
4. Railway detects the root `Dockerfile`. Keep the build using that Dockerfile.
5. Open the application's **Variables** tab and add the values below.

| Name | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `cloud` |
| `PORT` | `8080` |
| `DB_JDBC_URL` | Your Supabase JDBC URL from step 2 |
| `DB_USERNAME` | Your Supabase session-pooler username |
| `DB_PASSWORD` | Your Supabase database password |
| `REDIS_URL` | Your Upstash TLS Redis URL from step 3 |

An initial deployment might start before you have entered the variables and
fail. Add all six variables, apply the staged changes, and redeploy. The
Dockerfile already defines how to build and start Java, so no custom start
command or database service is needed in Railway.

## 5. Configure health checks and automatic deployment

In the application's service settings:

1. Set the healthcheck path to `/actuator/health`.
2. If a healthcheck timeout is offered, use `300` seconds to allow cold startup.
3. Enable **Wait for CI** in the GitHub source/autodeploy settings. Accept updated
   GitHub permissions if Railway requests them for this integration.
4. Confirm the linked branch is `main` and automatic deployments are enabled.
5. Apply the settings and deploy the latest `main` commit.

The CI workflow already runs on pushes to `main`. With Wait for CI enabled,
Railway waits for GitHub Actions to finish successfully before deploying the
commit. A failed check skips that deployment.

See [Railway Dockerfiles](https://docs.railway.com/builds/dockerfiles) and
[Wait for CI](https://docs.railway.com/deployments/github-autodeploys).

## 6. Generate a public URL and verify

1. Read the service's deployment logs until the application starts successfully.
2. Open **Settings > Networking > Public Networking** and choose **Generate Domain**.
3. If asked for a target port, use `8080`, matching the `PORT` variable.
4. Open the generated HTTPS URL with `/actuator/health` appended:

```text
https://<your-service>.up.railway.app/actuator/health
```

Expect `{"status":"UP"}`. A 404 at the plain `/` path is normal: this project is
an API and has no homepage.

Set `$baseUrl` in the README's PowerShell examples to your Railway URL. Create
two accounts, fund one, transfer money, replay the same request with the same
idempotency key, and read `/accounts/{accountId}/ledger`. A replay should return
the original transaction ID and leave the balances unchanged.

Health requires PostgreSQL. Redis is excluded from health status because a
cache outage should fall back to the database rather than make the whole API
unavailable. Check the logs for Redis connection warnings even when health is UP;
an incorrect Redis URL can otherwise be masked by the database fallback.

## 7. The CD cycle after setup

```text
Create a branch -> Push -> CI runs -> Review and merge
    -> CI runs on main -> Wait for CI succeeds -> Railway deploys
    -> Health check passes -> The new version receives traffic
```

No Railway token is needed in GitHub when using the GitHub integration and
Wait for CI. Railway rebuilds the checked commit using the Dockerfile; GitHub's
Docker step validates the image build but does not publish an image.

## Run locally

With Docker running:

```powershell
docker compose -f docker-compose.yml -f compose.app.yml up --build -d
```

This uses the local PostgreSQL and Redis services and does not spend Railway
credits. To stop them:

```powershell
docker compose -f docker-compose.yml -f compose.app.yml stop
```

The optional `render.yaml` and the `render` profile are retained for Render
accounts that can create services. They are not used by the Railway setup.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Missing configuration on startup | All six Railway variables are set and the active profile is `cloud` |
| JDBC connection fails | Supabase is active; use its session pooler on port 5432 and exact username |
| Redis connection fails | Use a `rediss://` TCP URL, the correct password, and Upstash's displayed port |
| Health is UP but Redis warnings appear | The API is using database fallback; fix the Redis connection separately |
| Service is WAITING | Open GitHub Actions for the same main-branch commit and check its status |
| API cannot be reached publicly | Generate a domain and use target port 8080 |
| Trial credit is running low | Check Railway Usage and stop or delete the demo service when finished |
