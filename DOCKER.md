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
| PostgreSQL | localhost:5433, db `slate`, user `slate` / `slate` |

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
| `SLATE_DB_HOST_PORT` | 5433 | Postgres port on the host |
| `SLATE_DB_URL` | container Postgres | Use your PC's Postgres instead: `jdbc:postgresql://host.docker.internal:5432/slate` |
| `SLATE_FRONTEND_DIR` | `../Frontend` | Path to the Frontend checkout |
| `MAVEN_MIRROR` | (none) | Maven Central mirror if downloads fail |

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
