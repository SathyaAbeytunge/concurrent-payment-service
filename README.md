# Concurrent Payment Service

A backend learning project that simulates money transfers between accounts.
It explores how to keep balances correct when requests arrive at the same time,
how to handle retries without transferring money twice, and how to recover from
failed transfers.

Built with **Java , Spring Boot, PostgreSQL, Redis, and Maven**.

## What the project does

- Creates accounts and lets you check their balances.
- Adds demo funds so you can try transfers.
- Transfers money between accounts that use the same currency.
- Uses database locks to prevent concurrent transfers from overspending.
- Locks accounts in a consistent order to avoid circular lock waits.
- Saves transfer results so a retry can return the original result.
- Records a debit and a credit entry for every successful transfer.
- Rolls back balance changes and records together if a transfer fails.

PostgreSQL stores accounts, transfers, ledger entries, and idempotency records.
Redis caches completed responses and marks requests that are being processed.
If Redis is unavailable, the service falls back to PostgreSQL for idempotency.

## Run locally

You need Java 21 and Docker with Docker Compose. Make sure Docker is running.
The Maven wrapper is included, so you do not need to install Maven separately.

Run these commands from the project folder in PowerShell:

```powershell
# Start PostgreSQL and Redis.
docker compose up -d

# Start the application after the containers are ready.
.\mvnw.cmd spring-boot:run
```

On macOS or Linux, use `./mvnw` instead of `.\mvnw.cmd`.

| Service | Local address |
| --- | --- |
| Application | `http://localhost:8080` |
| Health check | `http://localhost:8080/actuator/health` |
| PostgreSQL | `localhost:5433` |
| Redis | `localhost:6379` |

Flyway creates the database tables automatically when the application starts.
The default database settings in `application.properties` match `docker-compose.yml`.

## Try a transfer

Open another PowerShell terminal while the application is running.

### 1. Create two accounts

Both accounts start with a zero balance. The responses include their account IDs.

```powershell
$baseUrl = 'http://localhost:8080'

$alice = Invoke-RestMethod -Method Post -Uri "$baseUrl/accounts" `
    -ContentType 'application/json' `
    -Body '{"userId":"alice","currency":"USD"}'

$bob = Invoke-RestMethod -Method Post -Uri "$baseUrl/accounts" `
    -ContentType 'application/json' `
    -Body '{"userId":"bob","currency":"USD"}'
```

### 2. Give Alice demo funds

```powershell
Invoke-RestMethod -Method Post -Uri "$baseUrl/accounts/$($alice.id)/deposit" `
    -ContentType 'application/json' -Body '{"amount":100.0000}'
```

Alice now has `100.0000`. Deposits are a demo funding tool; see the ledger scope below.

### 3. Transfer 25 from Alice to Bob

```powershell
$transferBody = @{
    sourceAccountId = $alice.id
    targetAccountId = $bob.id
    amount = 25
} | ConvertTo-Json

$headers = @{ 'Idempotency-Key' = 'alice-to-bob-001' }

Invoke-RestMethod -Method Post -Uri "$baseUrl/transfers" `
    -ContentType 'application/json' -Headers $headers -Body $transferBody
```

Alice's balance becomes `75.0000` and Bob's becomes `25.0000`.

### 4. Retry and check the balances

Repeat the transfer with the same body and key:

```powershell
Invoke-RestMethod -Method Post -Uri "$baseUrl/transfers" `
    -ContentType 'application/json' -Headers $headers -Body $transferBody

Invoke-RestMethod -Uri "$baseUrl/accounts/$($alice.id)"
Invoke-RestMethod -Uri "$baseUrl/accounts/$($bob.id)"
```

The retry returns the original transfer result. Alice still has `75.0000` and
Bob still has `25.0000`.

## How retries work

An **idempotency key** identifies one intended transfer. Send it in the
`Idempotency-Key` header and reuse it when retrying that transfer.

| Request | Result while the key is valid |
| --- | --- |
| Same key and same transfer details, after success | Returns the saved result without moving money again |
| Same key with different transfer details | Returns HTTP `409 Conflict` |
| Duplicate request sees a Redis processing marker | Returns HTTP `409 Conflict`; retry later |
| Transfer rolls back | Database changes are undone and Redis marker cleanup is attempted, allowing a retry once the marker is removed or expires |

Keys must contain 1-128 characters and cannot be blank. The database gives each
new key a 24-hour validity window. Redis responses use that same expiry time;
loading a saved result into Redis does not extend the window.

After expiry, reusing the key can create a new transfer. Use a new key for each
new intended transfer.

## API reference

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/accounts` | Create an account |
| GET | `/accounts?userId=alice` | List a user's accounts, newest first |
| GET | `/accounts/{accountId}` | Get an account and its current balance |
| GET | `/accounts/{accountId}/ledger` | Get the account's transfer history, newest first |
| POST | `/accounts/{accountId}/deposit` | Add demo funds |
| POST | `/transfers` | Transfer funds; requires `Idempotency-Key` |
| GET | `/transfers/{transactionId}` | Retrieve a saved transfer receipt; no idempotency key required |

To find all accounts belonging to a user:

```powershell
Invoke-RestMethod -Uri "$baseUrl/accounts?userId=alice"
```

The user ID must match exactly. The response is an array of accounts with their
current balances, newest first. A user with no accounts receives `[]`. Missing,
blank, or longer than 64-character user IDs return `400`. This demo endpoint
returns all matching accounts without pagination. Like the other endpoints, it
has no authentication; a user ID filter does not verify ownership.

To retrieve a transfer later, use the `transactionId` returned when you created it:

```powershell
Invoke-RestMethod -Uri "$baseUrl/transfers/<transactionId>"
```

Replace `<transactionId>` with the actual ID. This returns the saved amount,
accounts, currency, status, and creation time without moving money again.
An unknown transfer returns `404`; an invalid UUID returns `400`.

Deposit and transfer amounts must be between `0.0001` and
`999999999999999.9999`, with at most four decimal places. Invalid amounts return
HTTP `400 Bad Request` rather than being rounded.

Transfers also require two different, existing accounts, matching currencies,
and enough money in the source account.

### Read an account's transfer history

After the transfer example above, you can read Alice's and Bob's ledger entries:

```powershell
Invoke-RestMethod -Uri "$baseUrl/accounts/$($alice.id)/ledger"
Invoke-RestMethod -Uri "$baseUrl/accounts/$($bob.id)/ledger"
```

Each entry contains `id`, `transactionId`, `type` (`DEBIT` or `CREDIT`), `amount`,
`balanceAfter`, and `createdAt`. Entries are returned as a JSON array with the
newest transfers first. The endpoint returns all entries for that account;
pagination is not yet supported.

An existing account with no transfers returns an empty array (`[]`), even if it
has demo funds. An unknown account returns HTTP `404 Not Found`. Retrying a
successful transfer with its original idempotency key does not add history entries.

## What the ledger records

The ledger records **transfers between accounts**. Each successful transfer
creates two entries in the same database transaction:

- A **debit** records money leaving the source account.
- A **credit** records money entering the target account.

For the example above:

| Account | Ledger entry | Balance after the transfer |
| --- | --- | ---: |
| Alice | DEBIT 25.0000 | 75.0000 |
| Bob | CREDIT 25.0000 | 25.0000 |

**Demo deposits are outside the ledger.** The deposit endpoint updates the account
balance directly. It creates no transfer or ledger entries and does not process
an external payment. Each repeated deposit request adds funds again, even if
you send an idempotency key.

Because deposits are not recorded, the ledger alone cannot reconstruct complete
account balances. Use `accounts.balance` for the current balance. A ledger entry's
`balance_after` is a snapshot that can include earlier demo deposits.

This is an account transfer simulation. A complete accounting ledger would also
need funding records and their balancing entries.

## Run the tests

With PostgreSQL and Redis running:

```powershell
.\mvnw.cmd test
```

The suite includes unit and integration tests for concurrent transfers,
duplicate requests, amount validation, ledger consistency, rollback recovery,
Redis failures, key expiry, and Redis key collisions.

To run only the tests that do not need PostgreSQL or Redis:

```powershell
.\mvnw.cmd test '-Dtest=MoneyAmountValidationTest,TransferServiceTest,TransferServiceRedisTest,RedisIdempotencyGatewayTest'
```

Integration tests use the configured local database and Redis instance and
create test data there. Use a local development environment when running them.

## Continuous integration (CI)

CI is an automatic check of the project when code changes. This project uses
GitHub Actions, with the recipe stored in [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

```text
Push code or open a pull request
    -> Set up Java, PostgreSQL, and Redis
    -> Build the project and run all tests
    -> Save test reports and the application JAR
```

The workflow runs on every push and when a pull request is opened, updated, or
reopened. You can also start it manually from GitHub's **Actions** tab.

### What the workflow file means

| Setting | Simple explanation |
| --- | --- |
| `on` | The events that start the workflow |
| `jobs` | The work GitHub should carry out |
| `runs-on` | The machine that does the work; this project uses Ubuntu |
| `services` | Temporary PostgreSQL and Redis containers used by the tests |
| `steps` | Instructions carried out in order |
| `uses` | A ready-made action, such as setting up Java |
| `run` | A terminal command to execute |

Health checks wait for PostgreSQL and Redis to be ready before the steps begin.
The service connection settings match the local configuration, so the tests
need no separate CI settings. The database password in the workflow is only for
its temporary test database; you do not need to create a GitHub secret for it.

The main command is:

```sh
sh ./mvnw --batch-mode --no-transfer-progress clean verify
```

`clean` removes old build output. `verify` compiles the code, runs all unit and
integration tests, and builds the executable JAR. The other options make the
command suitable for automation and keep download output short. Maven's
downloaded dependencies are cached to speed up later runs.

If a test fails, the build fails and GitHub marks the job with a red cross.
Test reports are uploaded even after a failure, if they were produced. The JAR
is uploaded only after a successful build. These downloadable files are called
**artifacts**, and this workflow keeps them for seven days.

### See your first CI run

1. Commit and push the workflow and README changes to GitHub.
2. Open the repository's **Actions** tab and select **CI**.
3. Open the latest run, then **Build and test**, to watch the steps.
4. A green check means the job passed. If it fails, open the failed step to read
   its error, fix the problem locally, and push the fix.
5. On the run's summary page, download `test-reports` or `payment-service-jar`
   from **Artifacts** when available.

After the workflow is on the default branch, you can also select **CI** and
choose **Run workflow** to start it manually.

CI builds and checks the application. Deployment to a running environment is a
separate step called CD. CI also checks that the Docker image builds. Enable
Railway's **Wait for CI** setting to deploy `main` after its CI checks pass.

## Run in Docker and deploy the demo

To run the application, PostgreSQL, and Redis together in containers:

```powershell
docker compose -f docker-compose.yml -f compose.app.yml up --build -d
```

The application remains available at `http://localhost:8080`. Stop any copy
already running on that port first.

The learning deployment uses a **Railway trial** for the application,
**Supabase Free** for PostgreSQL, and **Upstash Free** for Redis. Railway's trial
credits are limited; this is not a guarantee of ongoing free hosting. See the
[step-by-step deployment guide](docs/DEPLOYMENT.md) for account setup, connection
settings, verification, and automatic deployment after CI passes. The hosted
demo has no authentication; use fictional accounts and balances only.

Use `SPRING_PROFILES_ACTIVE=cloud` for Railway. The optional `render.yaml` and
`render` profile remain available for accounts that can use Render, but Render
may require card verification.

## Stop the application

Press `Ctrl+C` in the application terminal. To stop the supporting containers:

```powershell
docker compose stop
```
