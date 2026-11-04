# Run SLATE in Docker (local)

Everything runs in Docker: the Fabric network (`fabric/docker-compose.yml`) and the app stack
(`docker-compose.app.yml`: PostgreSQL, Backend API, Frontend UI). Clone `Backend` and `Frontend`
side by side (`.../Backend`, `.../Frontend`).

## 1. Start Fabric (once)

```
cd Backend/fabric
docker compose up -d
docker compose logs setup        # wait for "SLATE Fabric network ready"
```

## 2. Start the app

```
cd Backend
docker compose -f docker-compose.app.yml up -d --build
```

| What | URL |
|---|---|
| UI | http://localhost:8080 |
| API | http://localhost:8081 (Swagger: /swagger-ui.html) |
| PostgreSQL 18 | localhost:5434, db `slate`, user `slate` / `slate` |

Flyway creates the schema and demo users on first start. The Backend joins the `slate_fabric`
network and talks to the peers by name, using the certificates in `fabric/organizations`.

## Keep the database and the ledger together

The Fabric ledger remembers every token and deed number it has recorded. A new, empty database
starts numbering again from 1 (`SLATE-TN-00000001`, `000001/2026`), so on a ledger that already
has those numbers every write is REJECTED (`DUPLICATE_TOKEN`, `DEED_EXISTS`). Pick one:

- **Use your existing database** (keeps your data, the ledger already matches it): set
  `SLATE_DB_URL=jdbc:postgresql://host.docker.internal:5432/slate` (and `SLATE_DB_USER`,
  `SLATE_DB_PASSWORD`). Postgres on your PC must accept connections from Docker: in
  `pg_hba.conf` allow the Docker network (e.g. `host all all 0.0.0.0/0 scram-sha-256`), make sure
  `listen_addresses = '*'`, then restart Postgres.
- **Move your existing database into Docker** (keeps your data, the ledger already matches it).
  Keep the Fabric network as it is and run these in `Backend` (Windows `cmd`):
  ```
  "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -h localhost -p 5432 -U postgres -d slate --no-owner --no-acl -f slate.sql
  docker compose -f docker-compose.app.yml down -v
  docker compose -f docker-compose.app.yml up -d postgres
  type slate.sql | docker compose -f docker-compose.app.yml exec -T postgres psql -q -U slate -d slate
  docker compose -f docker-compose.app.yml exec postgres psql -U slate -d slate -c "select count(*) from core.property"
  docker compose -f docker-compose.app.yml up -d --build
  ```
  Use the `pg_dump` from the same major version as your PC's Postgres server, and dump as `postgres`
  (user `slate` can't read every table). Linux/Mac: `pg_dump ... -f slate.sql` and `... psql ... < slate.sql`.
  `down -v` deletes the Docker database, so run it only before the first load.
- **Start both fresh** (test data only): reset Fabric, then the app.
  ```
  cd Backend/fabric
  docker compose down -v
  rmdir /s /q organizations channel-artifacts      (Windows; Linux/Mac: rm -rf organizations channel-artifacts)
  docker compose up -d
  cd ..
  docker compose -f docker-compose.app.yml down -v
  docker compose -f docker-compose.app.yml up -d
  ```

## Options (environment variables or a `.env` file next to the compose file)

| Variable | Default | Use |
|---|---|---|
| `SLATE_UI_PORT` | 8080 | UI port on the host |
| `SLATE_API_HOST_PORT` | 8081 | API port on the host (stop a Backend running in IntelliJ first, or change this) |
| `SLATE_DB_HOST_PORT` | 5434 | Postgres port on the host (5432 and 5433 are often taken by Postgres installed on the PC) |
| `SLATE_DB_URL` | container Postgres | Use your PC's Postgres instead: `jdbc:postgresql://host.docker.internal:5432/slate` |
| `SLATE_FRONTEND_DIR` | `../Frontend` | Path to the Frontend checkout |
| `MAVEN_MIRROR` | (none) | Maven Central mirror if downloads fail |
| `SLATE_CORS_ORIGINS` | localhost + `https://making-boots-moving.ngrok-free.dev` | Public addresses the UI is opened from; others get 403 |
| `SLATE_PUBLIC_URL` | `http://localhost:8080` | Address used in password-reset emails |

## Share it with ngrok

```
ngrok http --url=making-boots-moving.ngrok-free.dev 8080
```

Open https://making-boots-moving.ngrok-free.dev/login. For another ngrok address, set
`SLATE_CORS_ORIGINS=http://localhost:*,https://<your-address>` (and `SLATE_PUBLIC_URL`) and run
`docker compose -f docker-compose.app.yml up -d backend`.

## Everyday commands

```
docker compose -f docker-compose.app.yml ps
docker compose -f docker-compose.app.yml logs -f backend
docker compose -f docker-compose.app.yml up -d --build backend    # after pulling new code
docker compose -f docker-compose.app.yml down                     # stop (keeps the database)
docker compose -f docker-compose.app.yml down -v                  # stop and delete the database
```

If Fabric is reset (`docker compose down -v` in `fabric/`), it gets new certificates: restart the
Backend with `docker compose -f docker-compose.app.yml restart backend`.
