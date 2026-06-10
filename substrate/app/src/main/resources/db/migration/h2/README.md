# db/migration/h2

H2-only Flyway shims, resolved via the `{vendor}` placeholder in
`spring.flyway.locations` when the dev profile runs on H2 (MODE=PostgreSQL).

Intentionally empty (this README keeps the location present on the classpath;
Flyway ignores non-migration files). Per port-map §4.2: avoid H2-only SQL —
prefer ANSI-portable DDL in `db/migration/common`. Postgres-only DDL (partial
indexes etc.) goes in `db/migration/postgresql` with dot-versions (`Vn_m`).
