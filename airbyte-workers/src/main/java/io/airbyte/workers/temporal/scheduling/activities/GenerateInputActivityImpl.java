/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling.activities;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.ATTEMPT_NUMBER_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ID_KEY;

import datadog.trace.api.Trace;
import io.airbyte.commons.docker.DockerUtils;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.temporal.TemporalWorkflowUtils;
import io.airbyte.commons.temporal.config.WorkerMode;
import io.airbyte.commons.temporal.exception.RetryableException;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobResetConnectionConfig;
import io.airbyte.config.JobSyncConfig;
import io.airbyte.config.ResetSourceConfiguration;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.persistence.job.JobPersistence;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.workers.WorkerConstants;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Singleton
@Requires(env = WorkerMode.CONTROL_PLANE)
public class GenerateInputActivityImpl implements GenerateInputActivity {

  private final JobPersistence jobPersistence;
  private final ConfigRepository configRepository;

  public GenerateInputActivityImpl(final JobPersistence jobPersistence,
                                   final ConfigRepository configRepository) {
    this.jobPersistence = jobPersistence;
    this.configRepository = configRepository;
  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public GeneratedJobInput getSyncWorkflowInput(final SyncInput input) {
    try {
      ApmTraceUtils.addTagsToTrace(Map.of(ATTEMPT_NUMBER_KEY, input.getAttemptId(), JOB_ID_KEY, input.getJobId()));
      final long jobId = input.getJobId();
      final int attempt = input.getAttemptId();
      final JobSyncConfig config;

      final Job job = jobPersistence.getJob(jobId);
      final ConfigType jobConfigType = job.getConfig().getConfigType();
      if (ConfigType.SYNC.equals(jobConfigType)) {
        config = job.getConfig().getSync();
      } else if (ConfigType.RESET_CONNECTION.equals(jobConfigType)) {
        final JobResetConnectionConfig resetConnection = job.getConfig().getResetConnection();
        final ResetSourceConfiguration resetSourceConfiguration = resetConnection.getResetSourceConfiguration();
        config = new JobSyncConfig()
            .withNamespaceDefinition(resetConnection.getNamespaceDefinition())
            .withNamespaceFormat(resetConnection.getNamespaceFormat())
            .withPrefix(resetConnection.getPrefix())
            .withSourceDockerImage(WorkerConstants.RESET_JOB_SOURCE_DOCKER_IMAGE_STUB)
            .withDestinationDockerImage(resetConnection.getDestinationDockerImage())
            .withDestinationProtocolVersion(resetConnection.getDestinationProtocolVersion())
            // null check for backwards compatibility with reset jobs that did not have a
            // resetSourceConfiguration
            .withSourceConfiguration(resetSourceConfiguration == null ? Jsons.emptyObject() : Jsons.jsonNode(resetSourceConfiguration))
            .withDestinationConfiguration(resetConnection.getDestinationConfiguration())
            .withConfiguredAirbyteCatalog(resetConnection.getConfiguredAirbyteCatalog())
            .withOperationSequence(resetConnection.getOperationSequence())
            .withResourceRequirements(resetConnection.getResourceRequirements())
            .withState(resetConnection.getState())
            .withIsSourceCustomConnector(resetConnection.getIsSourceCustomConnector())
            .withIsDestinationCustomConnector(resetConnection.getIsDestinationCustomConnector());
      } else {
        throw new IllegalStateException(
            String.format("Unexpected config type %s for job %d. The only supported config types for this activity are (%s)",
                jobConfigType,
                jobId,
                List.of(ConfigType.SYNC, ConfigType.RESET_CONNECTION)));
      }

      final JobRunConfig jobRunConfig = TemporalWorkflowUtils.createJobRunConfig(jobId, attempt);

      List<StandardDestinationDefinition> destinationDefinitionList = configRepository.listStandardDestinationDefinitions(true);
      Optional<StandardDestinationDefinition> optionalDestinationDefinition = destinationDefinitionList.stream()
          .filter(destinationDefinition -> config.getDestinationDockerImage()
              .equalsIgnoreCase(
                  DockerUtils.getTaggedImageName(destinationDefinition.getDockerRepository(), destinationDefinition.getDockerImageTag())))
          .findFirst();
      final String destinationNormalizationDockerImage = optionalDestinationDefinition
          .filter(standardDestinationDefinition -> Objects.nonNull(standardDestinationDefinition.getNormalizationConfig()))
          .map(standardDestinationDefinition -> String.format("%s:%s",
              standardDestinationDefinition.getNormalizationConfig().getNormalizationRepository(),
              standardDestinationDefinition.getNormalizationConfig().getNormalizationTag()))
          .orElse(null);
      final boolean supportstDbt = optionalDestinationDefinition.isPresent() && Objects.nonNull(optionalDestinationDefinition.get().getSupportsDbt())
          ? optionalDestinationDefinition.get().getSupportsDbt()
          : false;
      final String normalizationIntegrationType = optionalDestinationDefinition
          .filter(standardDestinationDefinition -> Objects.nonNull(standardDestinationDefinition.getNormalizationConfig()))
          .map(standardDestinationDefinition -> standardDestinationDefinition.getNormalizationConfig().getNormalizationIntegrationType())
          .orElse(null);

      final IntegrationLauncherConfig sourceLauncherConfig = new IntegrationLauncherConfig()
          .withJobId(String.valueOf(jobId))
          .withAttemptId((long) attempt)
          .withDockerImage(config.getSourceDockerImage())
          .withProtocolVersion(config.getSourceProtocolVersion())
          .withIsCustomConnector(config.getIsSourceCustomConnector());

      final IntegrationLauncherConfig destinationLauncherConfig = new IntegrationLauncherConfig()
          .withJobId(String.valueOf(jobId))
          .withAttemptId((long) attempt)
          .withDockerImage(config.getDestinationDockerImage())
          .withProtocolVersion(config.getDestinationProtocolVersion())
          .withIsCustomConnector(config.getIsDestinationCustomConnector())
          .withNormalizationDockerImage(destinationNormalizationDockerImage)
          .withSupportsDbt(supportstDbt)
          .withNormalizationIntegrationType(normalizationIntegrationType);

      final StandardSyncInput syncInput = new StandardSyncInput()
          .withNamespaceDefinition(config.getNamespaceDefinition())
          .withNamespaceFormat(config.getNamespaceFormat())
          .withPrefix(config.getPrefix())
          .withSourceConfiguration(config.getSourceConfiguration())
          .withDestinationConfiguration(config.getDestinationConfiguration())
          .withOperationSequence(config.getOperationSequence())
          .withWebhookOperationConfigs(config.getWebhookOperationConfigs())
          .withCatalog(config.getConfiguredAirbyteCatalog())
          .withState(config.getState())
          .withResourceRequirements(config.getResourceRequirements())
          .withSourceResourceRequirements(config.getSourceResourceRequirements())
          .withDestinationResourceRequirements(config.getDestinationResourceRequirements());

      return new GeneratedJobInput(jobRunConfig, sourceLauncherConfig, destinationLauncherConfig, syncInput);

    } catch (final Exception e) {
      throw new RetryableException(e);
    }
  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public GeneratedJobInput getSyncWorkflowInputWithAttemptNumber(final SyncInputWithAttemptNumber input) {
    ApmTraceUtils.addTagsToTrace(Map.of(JOB_ID_KEY, input.getJobId()));
    return getSyncWorkflowInput(new SyncInput(
        input.getAttemptNumber(),
        input.getJobId()));
  }

}
