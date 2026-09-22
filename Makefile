# FlowCatalyst (Java) — thin wrappers over the mvn lines we actually type.
#
# Target names mirror ../flowcatalyst-go/Makefile where the concepts map, so
# switching repos costs nothing. Every target is one literal command: the
# explanations live in docs/, not here, so the two cannot drift.
#
# The point of this file is JAVA_HOME. Every command needs it, a hardcoded
# path has already gone stale once, and the native build needs a *different*
# JDK from everything else.

SHELL := /bin/bash

# Resolved once, from mise — never hardcode a JDK path.
export JAVA_HOME := $(shell mise where java)

# GraalVM is a second toolchain, only for native-image. One place to bump.
GRAALVM_VERSION ?= oracle-graalvm-25.0.4.1
GRAALVM_HOME     = $(shell mise where java@$(GRAALVM_VERSION))

MVN := mvn
# Surefire fails the build when a -Dtest pattern matches nothing; that hides
# typos behind a red build instead of saying so.
NO_EMPTY := -Dsurefire.failIfNoSpecifiedTests=false

.DEFAULT_GOAL := help
.PHONY: help test test-router test-db test-one fnhost-smoke examples verify native native-server jar run stop init fresh clean toolchain frontend sdk-spec sdk-generate release-ts-sdk release-laravel-sdk release-java-sdk build-java-sdk

help: ## List targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk -F':.*?## ' '{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

toolchain: ## Show the JDKs these targets will use
	@echo "JAVA_HOME    $(JAVA_HOME)"
	@echo "GraalVM      $(GRAALVM_HOME)"
	@[ -x "$(GRAALVM_HOME)/bin/native-image" ] \
	  || echo "  (no native-image there — run: mise install java@$(GRAALVM_VERSION))"

# ── tests ────────────────────────────────────────────────────────────────
# No -q: it suppresses Maven's INFO lines, which is where the test summary is.

test: ## Full reactor (clean)
	$(MVN) clean test

verify: ## Full reactor without cleaning (faster; stale classes possible)
	$(MVN) test

test-router: ## Just the message router (45 classes)
	$(MVN) -pl server -am test -Dtest='io.flowcatalyst.router.**' $(NO_EMPTY)

test-db: ## Migrator, schema fingerprint and Go adoption (docs/database.md)
	$(MVN) -pl server -am test -Dtest='Migrator*,*Fingerprint*,*Adoption*' $(NO_EMPTY)

fnhost-smoke: ## Package the fc-fnhost exec jar, then run it for real (P9, docs/spec/function-host-process.md §4)
	$(MVN) -q -DskipTests -pl function-host -am package
	$(MVN) -pl function-api,function-host -am test -Dtest='FnHostSmokeTest' $(NO_EMPTY)

examples: ## Build, shrink and test the sample functions (examples/function-hello, examples/function-subscription-test)
	$(MVN) -q -B -Pexamples -pl examples/function-hello,examples/function-subscription-test -am verify -Dtest='ShrunkJarTest' -Dsurefire.failIfNoSpecifiedTests=false

test-one: ## One class or pattern: make test-one T=PoolTest
	@[ -n "$(T)" ] || { echo "usage: make test-one T=<ClassName|pattern>"; exit 2; }
	$(MVN) -pl server -am test -Dtest='$(T)' $(NO_EMPTY)

# ── binaries ─────────────────────────────────────────────────────────────
# native-image needs GraalVM, and `mvn clean` deletes the binary — so build
# it after any clean, not before.

native: ## Build fcdev as a native binary (GraalVM)
	JAVA_HOME=$(GRAALVM_HOME) $(MVN) -DskipTests -pl fcdev -am -Pnative package
	@ls -lh fcdev/target/fcdev

native-server: ## Build fc-server as a native binary (GraalVM)
	JAVA_HOME=$(GRAALVM_HOME) $(MVN) -DskipTests -pl server -am -Pnative package
	@ls -lh server/target/fc-server

# ── running fcdev ────────────────────────────────────────────────────────

# The shade plugin names it flowcatalyst-fcdev-<version>.jar and leaves an
# `original-` twin beside it, so resolve at recipe time rather than guessing.
# --enable-preview: the whole reactor compiles with preview features on (root
# pom), so the JVM that runs the jar needs the flag too — without it the first
# preview-compiled class fails to load with UnsupportedClassVersionError. The
# Docker entrypoints and the native build pass it the same way.
FCDEV = java --enable-preview -jar "$$(ls fcdev/target/flowcatalyst-fcdev-*.jar 2>/dev/null | grep -v original | head -1)"
NEED_JAR = @ls fcdev/target/flowcatalyst-fcdev-*.jar >/dev/null 2>&1 \
	  || { echo "no fcdev jar — run: make jar"; exit 2; }

jar: ## Build the fcdev executable jar
	$(MVN) -q -DskipTests -pl fcdev -am package
	@ls -lh fcdev/target/flowcatalyst-fcdev-*.jar | grep -v original

run: jar ## Run the dev monolith (embedded Postgres, SPA, all subsystems)
	$(FCDEV) start

stop: ## Stop a running fcdev (and its embedded Postgres)
	$(NEED_JAR)
	$(FCDEV) stop

init: ## Bootstrap admin user + default tenant + .env
	$(NEED_JAR)
	$(FCDEV) init

fresh: ## Truncate every FlowCatalyst table (keeps the schema)
	$(NEED_JAR)
	$(FCDEV) fresh

clean: ## Remove every target/
	$(MVN) clean

# ── frontend ─────────────────────────────────────────────────────────────

frontend: ## Rebuild the embedded SPA from ./frontend into server/src/main/resources/frontend
	tools/build-frontend.sh

# ── SDKs ─────────────────────────────────────────────────────────────────
# The TS + Laravel client SDKs (clients/) and the Java SDK (sdk/) all
# generate from one document: server/src/main/resources/openapi/openapi.lock.json
# IS the spec, so sdk-spec only copies it — no dump step, unlike Go's
# `go run ./tools/dump-spec`. Releases tag <sdk>/vX.Y.Z; the split-*-sdk
# workflows mirror TS/Laravel to their standalone repos (docs/sdk-release-plan.md).

sdk-spec: ## Copy the OpenAPI lockfile into each SDK's openapi/openapi.json
	cp server/src/main/resources/openapi/openapi.lock.json clients/typescript-sdk/openapi/openapi.json
	cp server/src/main/resources/openapi/openapi.lock.json clients/laravel-sdk/openapi/openapi.json
	cp server/src/main/resources/openapi/openapi.lock.json sdk/openapi/openapi.json

sdk-generate: sdk-spec ## Regenerate the TS + Laravel SDK clients from the spec
	cd clients/typescript-sdk && pnpm install --frozen-lockfile && pnpm run generate && pnpm run build
	cd clients/laravel-sdk && XDEBUG_MODE=off composer install --no-interaction && XDEBUG_MODE=off php scripts/prepare-openapi.php && XDEBUG_MODE=off vendor/bin/jane-openapi generate --config-file=jane-openapi.php

build-java-sdk: ## Build + test the Java SDK through the reactor (sdk/README.md)
	$(MVN) -q -pl sdk -am verify

release-ts-sdk: ## Cut a TypeScript SDK release: BUMP=… (bumps package.json, tags typescript-sdk/vX.Y.Z)
	scripts/release.sh ts "$(BUMP)"

release-laravel-sdk: ## Cut a Laravel SDK release: BUMP=… (tags laravel-sdk/vX.Y.Z)
	scripts/release.sh laravel "$(BUMP)"

release-java-sdk: ## Cut a Java SDK release: BUMP=… (bumps sdk/VERSION, tags java-sdk/vX.Y.Z)
	scripts/release.sh java "$(BUMP)"
