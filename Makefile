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
.PHONY: help test test-router test-db test-one verify native native-server jar run stop init fresh clean toolchain

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
FCDEV = java -jar "$$(ls fcdev/target/flowcatalyst-fcdev-*.jar 2>/dev/null | grep -v original | head -1)"
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
