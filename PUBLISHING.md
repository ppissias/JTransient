# Publishing To Maven Central

This project stages a Maven Central Portal upload bundle locally by publishing
to a signed Maven repository under `build/repos/central-staging` and then
zipping that staged repository for upload.

## One-Time Setup

1. Verify the `io.github.ppissias` namespace in the Central Publisher Portal.
2. Create an OpenPGP key pair and publish the public key to a public keyserver.
3. Configure `gradle.properties` in the project root or
   `%USERPROFILE%\.gradle\gradle.properties`.
4. Adjust the developer metadata values if needed.
5. Choose one signing mode:
   - set `signingKey` and `signingPassword`
   - or configure `signing.keyId`, `signing.password`, and `signing.secretKeyRingFile`

## Build The Release Bundle

Use the standard verification build first:

```powershell
.\gradlew.bat build
```

Then stage the Maven Central bundle:

```powershell
.\gradlew.bat mavenCentralBundle
```

You can also run the underlying task directly:

```powershell
.\gradlew.bat bundleCentralPortal
```

The bundle flow:

- validates that signing credentials are configured
- runs `check`
- publishes the signed artifacts to `build/repos/central-staging`
- creates a ZIP bundle in `build/distributions`

The ZIP file is ready for Central Portal upload.

## What Gets Published

The publication coordinates are:

- `groupId`: `io.github.ppissias.jtransient`
- `artifactId`: `jtransient`
- `version`: taken from `build.gradle`

The staged bundle contains:

- the main JAR
- `-sources.jar`
- `-javadoc.jar`
- the generated POM
- `.asc` signatures for published files

## Upload

After the bundle is created, upload the ZIP through the Central Publisher
Portal upload flow or the Portal API using a Portal token.

Central Portal guide:

- https://central.sonatype.org/publish/publish-portal-guide/
- https://central.sonatype.org/publish/publish-portal-gradle/
