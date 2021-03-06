package com.netflix.spinnaker.orca.echo.spring

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.echo.EchoService
import com.netflix.spinnaker.orca.front50.Front50Service
import com.netflix.spinnaker.orca.front50.model.ApplicationNotifications
import com.netflix.spinnaker.orca.listeners.ExecutionListener
import com.netflix.spinnaker.orca.listeners.Persister
import com.netflix.spinnaker.orca.pipeline.model.Execution
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
class EchoNotifyingExecutionListener implements ExecutionListener {

  private final EchoService echoService

  private final Front50Service front50Service

  private final ObjectMapper objectMapper

  private final ContextParameterProcessor contextParameterProcessor

  EchoNotifyingExecutionListener(EchoService echoService, Front50Service front50Service, ObjectMapper objectMapper, ContextParameterProcessor contextParameterProcessor) {
    this.echoService = echoService
    this.front50Service = front50Service
    this.objectMapper = objectMapper
    this.contextParameterProcessor = contextParameterProcessor
  }

  @Override
  void beforeExecution(Persister persister, Execution execution) {
    try {
      if (execution.status != ExecutionStatus.SUSPENDED) {
        if (execution instanceof Pipeline) {
          addApplicationNotifications(execution as Pipeline)
        }
        echoService.recordEvent(
          details: [
            source     : "orca",
            type       : "orca:${execution.getClass().simpleName.toLowerCase()}:starting".toString(),
            application: execution.application,
          ],
          content: [
            execution  : execution,
            executionId: execution.id
          ]
        )
      }
    } catch (Exception e) {
      log.error("Failed to send pipeline start event: ${execution?.id}")
    }
  }

  @Override
  void afterExecution(Persister persister,
                      Execution execution,
                      ExecutionStatus executionStatus,
                      boolean wasSuccessful) {
    try {
      if (execution.status != ExecutionStatus.SUSPENDED) {
        if (execution instanceof Pipeline) {
          addApplicationNotifications(execution as Pipeline)
        }
        echoService.recordEvent(
          details: [
            source     : "orca",
            type       : "orca:${execution.getClass().simpleName.toLowerCase()}:${wasSuccessful ? "complete" : "failed"}".toString(),
            application: execution.application,
          ],
          content: [
            execution  : execution,
            executionId: execution.id
          ]
        )
      }
    } catch (Exception e) {
      log.error("Failed to send pipeline end event: ${execution?.id}")
    }
  }

  /**
   * Adds any application-level notifications to the pipeline's notifications
   * If a notification exists on both with the same address and type, the pipeline's notification will be treated as an
   * override, and any "when" values in the application-level notification that are also in the pipeline's notification
   * will be removed from the application-level notification
   *
   * @param pipeline
   */
  private void addApplicationNotifications(Pipeline pipeline) {
    ApplicationNotifications notifications = front50Service.getApplicationNotifications(pipeline.application)

    if (notifications) {
      notifications.getPipelineNotifications().each { appNotification ->
        Map executionMap = objectMapper.convertValue(pipeline, Map)

        appNotification = contextParameterProcessor.process(appNotification, executionMap, false)

        Map<String, Object> targetMatch = pipeline.notifications.find { pipelineNotification ->
          pipelineNotification.address == appNotification.address && pipelineNotification.type == appNotification.type
        }
        if (!targetMatch) {
          pipeline.notifications.push(appNotification)
        } else {
          Collection<String> appWhen = ((Collection<String>) appNotification.when)
          Collection<String> pipelineWhen = (Collection<String>) targetMatch.when
          appWhen.removeAll(pipelineWhen)
          if (!appWhen.isEmpty()) {
            pipeline.notifications.push(appNotification)
          }
        }
      }
    }
  }
}
