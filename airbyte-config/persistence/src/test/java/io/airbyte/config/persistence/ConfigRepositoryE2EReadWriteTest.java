/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;

import io.airbyte.commons.json.Jsons;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSourceDefinition.SourceType;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.split_secrets.MemorySecretPersistence;
import io.airbyte.config.persistence.split_secrets.NoOpSecretsHydrator;
import io.airbyte.db.Database;
import io.airbyte.db.instance.configs.ConfigsDatabaseInstance;
import io.airbyte.db.instance.configs.ConfigsDatabaseMigrator;
import io.airbyte.db.instance.development.DevDatabaseMigrator;
import io.airbyte.db.instance.development.MigrationDevHelper;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

public class ConfigRepositoryE2EReadWriteTest {

  private final StandardWorkspace workspace = new StandardWorkspace()
      .withWorkspaceId(UUID.randomUUID())
      .withName("Default workspace")
      .withSlug("default-workspace")
      .withInitialSetupComplete(true);
  private static PostgreSQLContainer<?> container;
  private Database database;
  private ConfigRepository configRepository;
  private DatabaseConfigPersistence configPersistence;

  @BeforeAll
  public static void dbSetup() {
    container = new PostgreSQLContainer<>("postgres:13-alpine")
        .withDatabaseName("airbyte")
        .withUsername("docker")
        .withPassword("docker");
    container.start();
  }

  @BeforeEach
  void setup() throws IOException, JsonValidationException {
    final var secretPersistence = new MemorySecretPersistence();
    database = new ConfigsDatabaseInstance(container.getUsername(), container.getPassword(), container.getJdbcUrl()).getAndInitialize();
    configPersistence = spy(new DatabaseConfigPersistence(database));
    configRepository =
        spy(new ConfigRepository(configPersistence, new NoOpSecretsHydrator(), Optional.of(secretPersistence), Optional.of(secretPersistence),
            database));
    final ConfigsDatabaseMigrator configsDatabaseMigrator =
        new ConfigsDatabaseMigrator(database, DatabaseConfigPersistenceLoadDataTest.class.getName());
    final DevDatabaseMigrator devDatabaseMigrator = new DevDatabaseMigrator(configsDatabaseMigrator);
    MigrationDevHelper.runLastMigration(devDatabaseMigrator);
    configRepository.writeStandardWorkspace(workspace);
  }

  @AfterAll
  public static void dbDown() {
    container.close();
  }

  @Test
  void testSimpleInsertActorCatalog() throws IOException, JsonValidationException {

    final StandardSourceDefinition sourceDefinition = new StandardSourceDefinition()
        .withSourceDefinitionId(UUID.randomUUID())
        .withSourceType(SourceType.DATABASE)
        .withDockerRepository("docker-repo")
        .withDockerImageTag("1.2.0")
        .withName("sourceDefinition");
    configRepository.writeStandardSourceDefinition(sourceDefinition);

    final SourceConnection source = new SourceConnection()
        .withSourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .withSourceId(UUID.randomUUID())
        .withName("SomeConnector")
        .withWorkspaceId(workspace.getWorkspaceId())
        .withConfiguration(Jsons.deserialize("{}"));
    final ConnectorSpecification specification = new ConnectorSpecification()
        .withConnectionSpecification(Jsons.deserialize("{}"));
    configRepository.writeSourceConnection(source, specification);

    final AirbyteCatalog actorCatalog = new AirbyteCatalog();
    configRepository.writeActorCatalogFetchEvent(
        actorCatalog, source.getSourceId(), "1.2.0", "ConfigHash");

    final Optional<AirbyteCatalog> catalog =
        configRepository.getActorCatalog(source.getSourceId(), "1.2.0", "ConfigHash");
    assertTrue(catalog.isPresent());
    assertEquals(actorCatalog, catalog.get());
    assertFalse(configRepository.getSourceCatalog(source.getSourceId(), "1.3.0", "ConfigHash").isPresent());
    assertFalse(configRepository.getSourceCatalog(source.getSourceId(), "1.2.0", "OtherConfigHash").isPresent());
  }

}
