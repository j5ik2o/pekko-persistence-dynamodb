# Release notes

## Unreleased

### Java 11 is the minimum supported version

The README and the overview claimed Java 1.8+, while CI tested Java 11, 17, and 19. The product decision is to support the tested versions only, so the documentation now names Java 11, 17, and 19. CI does not test Java 8.

The `schema-check` version check fails when the documented Java versions and the CI matrix disagree. To keep Java 8, add it to the matrix in `.github/workflows/ci.yml` and to both documents.
