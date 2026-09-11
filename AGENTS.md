# Repository Guidelines

## Project Structure & Module Organization

ExAI is a Java 8 Maven plugin for Spigot servers. Production code is under
`src/main/java/com/exai/`; keep new classes in the package that matches their
responsibility, such as `listener`, `service`, `storage`, `gui`, or `web`.
`ExAI.java` is the plugin entry point. Runtime resources live in
`src/main/resources`: `plugin.yml` declares the plugin, `config.yml` contains
defaults, `lang/` holds locale YAML files, `knowledge/knowledge.yml` seeds the
knowledge base, and `web/` contains the admin UI. Local JAR dependencies are in
`libs/`. Maven output is generated in `target/` and must not be edited.

## Build, Test, and Development Commands

- `mvn compile` compiles the plugin against the configured Spigot API.
- `mvn package` compiles and creates the shaded deployable JAR in `target/`.
- `mvn -DskipTests package` is useful for a fast packaging check; this project
  currently has no automated test suite.

Use JDK 8 or a compatible JDK configured to target Java 8. To validate runtime
behavior, place the built JAR in a test Spigot server's `plugins/` directory,
configure `config.yml`, then check startup logs and `/exai` commands.

## Coding Style & Naming Conventions

Follow the existing Java style: four-space indentation, braces on the same line,
and one public top-level class per file. Use `PascalCase` for classes and enums,
`camelCase` for methods, fields, and local variables, and meaningful package
names in lowercase. Prefer existing abstractions (`DataStorage`, `Config`,
`Lang`) over duplicating storage, configuration, or message handling. Keep Bukkit
main-thread interactions safe; perform network and storage work asynchronously
where the surrounding code does so.

## Testing Guidelines

There is no JUnit configuration or `src/test` directory yet. For changes, at
minimum run `mvn compile`; run `mvn package` when resources or dependencies are
affected. Add focused tests under `src/test/java/com/exai/...` when introducing
logic that can run outside Bukkit, naming them `*Test` and keeping setup minimal.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit prefixes, including `feat:`,
`fix:`, `docs:`, `chore:`, and `release:`; use the same form, e.g.
`fix: guard empty knowledge entries`. Keep commits scoped to one change. Pull
requests should explain behavior and configuration changes, link related issues,
state verification commands, and include screenshots for GUI or web-admin edits.
Never commit API keys, database passwords, or server-specific configuration.
