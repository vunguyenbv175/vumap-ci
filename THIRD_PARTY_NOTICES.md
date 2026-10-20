# Third-party notices

This CI snapshot vendors the following third-party artifacts and data:

- `gradle/wrapper/gradle-wrapper.jar` — the Gradle Wrapper, copyright Gradle Inc. and contributors,
  licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). It is
  included unmodified solely to bootstrap the Gradle distribution pinned in
  `gradle/wrapper/gradle-wrapper.properties`.
- `feature/signs/fixtures/osm/hanoi_maxspeed_bbox_osm.json` — a compact four-way OpenStreetMap `maxspeed` extract,
  © OpenStreetMap contributors, licensed under the
  [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/). It was
  retrieved once through the Overpass API; the query, bounding box, and retrieval timestamps
  are recorded in the adjacent `.provenance.json`. It is included only as offline
  road-evidence adapter test input; it is not field-surveyed, not a legal speed verification,
  and is never built into a synthetic navigation pack.


All other build and runtime dependencies are declared in `gradle/libs.versions.toml` and resolved at
build time from Google Maven and Maven Central. They are not vendored in this snapshot; their own
licenses and notices apply. Those dependencies include MapLibre Native, AndroidX/Compose, Google Play
Services Location, OkHttp, Open Location Code, DataStore, JUnit, and SQLite JDBC. See the version
catalog for exact coordinates and versions.

The synthetic signs fixtures and bundled `.vupack` asset are synthetic test-only data created for this project.
They are not derived from a real navigation dataset and carry no field-safety claim.
