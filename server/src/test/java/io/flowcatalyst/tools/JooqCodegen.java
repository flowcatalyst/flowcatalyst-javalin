package io.flowcatalyst.tools;

import java.lang.reflect.Method;
import java.nio.file.Path;

import javax.sql.DataSource;

import io.flowcatalyst.platform.shared.database.Migrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/// jOOQ code generation against the migrated schema.
///
/// Starts an embedded PostgreSQL, runs {@link Migrator} (so the generated code
/// always reflects `db/migration`), then runs `org.jooq.codegen.GenerationTool`
/// and writes tables + records into `io.flowcatalyst.db.generated`.
///
/// Run via the `jooq-codegen` Maven profile (see docs/database.md):
///
///     mvn -pl server -Pjooq-codegen process-test-classes
///
/// `jooq-codegen` is not a project dependency — the profile puts it on the
/// exec classpath — which is why GenerationTool is invoked reflectively.
/// System properties: `jooq.target` (output directory, default
/// `src/main/java`), `jooq.package` (default `io.flowcatalyst.db.generated`).
public final class JooqCodegen {

    public static final String DEFAULT_PACKAGE = "io.flowcatalyst.db.generated";

    /// Excluded from generation: migration bookkeeping and the dated monthly
    /// (`<parent>_YYYY_MM`) and quarterly (`<parent>_YYYY_qN`) partitions, plus
    /// DEFAULT partitions (`<parent>_default`) — code targets the partitioned
    /// parents.
    public static final String EXCLUDES =
            "flyway_schema_history|goose_db_version|.*_\\d{4}_\\d{2}|.*_\\d{4}_q\\d|.*_default";

    private JooqCodegen() {
    }

    public static void main(String[] args) throws Exception {
        Path target = Path.of(System.getProperty("jooq.target", "src/main/java")).toAbsolutePath();
        String pkg = System.getProperty("jooq.package", DEFAULT_PACKAGE);

        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource ds = pg.getPostgresDatabase();
            Migrator.migrate(ds);
            String xml = configuration(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres", pkg, target);
            generate(xml);
        }
        System.out.println("jOOQ code generated into " + target.resolve(pkg.replace('.', '/')));
    }

    static void generate(String xml) throws Exception {
        Class<?> tool = Class.forName("org.jooq.codegen.GenerationTool");
        Method generate = tool.getMethod("generate", String.class);
        generate.invoke(null, xml);
    }

    static String configuration(String jdbcUrl, String user, String password, String pkg, Path target) {
        return """
                <configuration xmlns="http://www.jooq.org/xsd/jooq-codegen-3.21.0.xsd">
                  <jdbc>
                    <driver>org.postgresql.Driver</driver>
                    <url>%s</url>
                    <user>%s</user>
                    <password>%s</password>
                  </jdbc>
                  <generator>
                    <name>org.jooq.codegen.JavaGenerator</name>
                    <database>
                      <name>org.jooq.meta.postgres.PostgresDatabase</name>
                      <inputSchema>public</inputSchema>
                      <excludes>%s</excludes>
                      <includeRoutines>false</includeRoutines>
                      <includeUDTs>false</includeUDTs>
                      <includeDomains>false</includeDomains>
                      <includePackages>false</includePackages>
                    </database>
                    <generate>
                      <records>true</records>
                      <pojos>false</pojos>
                      <daos>false</daos>
                      <interfaces>false</interfaces>
                      <javaTimeTypes>true</javaTimeTypes>
                      <generatedAnnotation>false</generatedAnnotation>
                      <indexes>true</indexes>
                      <keys>true</keys>
                      <sequences>true</sequences>
                      <globalObjectReferences>true</globalObjectReferences>
                      <deprecated>false</deprecated>
                      <comments>false</comments>
                    </generate>
                    <target>
                      <packageName>%s</packageName>
                      <directory>%s</directory>
                      <clean>true</clean>
                    </target>
                  </generator>
                </configuration>
                """.formatted(jdbcUrl, user, password, EXCLUDES, pkg, target);
    }
}
