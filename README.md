# MessageSearchBE
A back-end service for multilingual message search, proofreading, and collaboration.

## Highlights
- **Paragraph-first modeling:** Documents are composed of ordered paragraphs, enabling fine-grained collaboration, storage, and search results that cite the exact paragraph (and parent document) that matched.
- **Multilingual by default:** Every document version and paragraph carries a BCP‑47 language code so ingest, hybrid search, and RAG answering can filter or rank per language and host multiple translations concurrently.
- **CRDT collaboration:** Operation-based CRDT streams keep concurrent editors in sync while snapshotting state for downstream pipelines (e.g., search index backfills).

## Development Workflow
This project uses [Gradle](https://gradle.org/).
To build and run the application, use the *Gradle* tool window by clicking the Gradle icon in the right-hand toolbar,
or run it directly from the terminal:

* Run `./gradlew :backend:run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`).
This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

This project follows the suggested multi-module setup and consists of the `backend`, `core`, `infra`, and `utils` subprojects.
The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).

## Local JWTs
To call authenticated endpoints locally, issue a development JWT that matches `application.yaml`:

```bash
python3 scripts/issue-jwt.py
```

You can override defaults:

```bash
python3 scripts/issue-jwt.py --roles editor,reviewer --sub 00000000-0000-0000-0000-000000000000
```

Or via Gradle:

```bash
./gradlew :backend:issueJwt
```
