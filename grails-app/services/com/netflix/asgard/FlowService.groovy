package com.netflix.asgard

import com.amazonaws.services.simpleworkflow.AmazonSimpleWorkflow
import com.amazonaws.services.simpleworkflow.flow.ActivityWorker
import com.amazonaws.services.simpleworkflow.flow.ManualActivityCompletionClient
import com.amazonaws.services.simpleworkflow.flow.WorkerBase
import com.amazonaws.services.simpleworkflow.flow.WorkflowClientExternal
import com.amazonaws.services.simpleworkflow.flow.WorkflowWorker
import com.amazonaws.services.simpleworkflow.model.WorkflowExecution
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.netflix.asgard.deployment.DeploymentActivitiesImpl
import com.netflix.asgard.deployment.DeploymentWorkflow
import com.netflix.asgard.deployment.DeploymentWorkflowDescriptionTemplate
import com.netflix.asgard.deployment.DeploymentWorkflowImpl
import com.netflix.asgard.model.SwfWorkflowTags
import com.netflix.glisten.GlobalWorkflowAttributes
import com.netflix.glisten.InterfaceBasedWorkflowClient
import com.netflix.glisten.WorkflowClientFactory
import com.netflix.glisten.WorkflowDescriptionTemplate
import org.springframework.beans.factory.InitializingBean

/**
 * This service handles AWS SWF concerns at the level of the Flow framework. It primarily controls pollers (decision
 * and activity) as well as giving the means to execute or inspect workflows.
 */
class FlowService implements InitializingBean {

    AwsClientService awsClientService
    ConfigService configService

    WorkflowWorker workflowWorker
    ActivityWorker activityWorker

    WorkflowClientFactory workflowClientFactory

    // For every workflow the following data structures should be populated.
    /** Declares workflow implementations. */
    final ImmutableSet<Class<?>> workflowImplementationTypes = ImmutableSet.of(DeploymentWorkflowImpl)
    /* Declares workflow description templates. */
    final ImmutableMap<Class<?>, WorkflowDescriptionTemplate> workflowToDescriptionTemplate = ImmutableMap.copyOf([
            (DeploymentWorkflow): new DeploymentWorkflowDescriptionTemplate()
    ] as Map)
    /** Declares workflow activity implementations. */
    final ImmutableSet<Object> activityImplementations = ImmutableSet.of(new DeploymentActivitiesImpl())

    void afterPropertiesSet() {
        String domain = configService.simpleWorkflowDomain
        String taskList = configService.simpleWorkflowTaskList
        GlobalWorkflowAttributes.taskList = taskList
        AmazonSimpleWorkflow simpleWorkflow = awsClientService.create(AmazonSimpleWorkflow)
        workflowClientFactory = new WorkflowClientFactory(simpleWorkflow, domain, taskList)
        activityImplementations.each { Spring.autowire(it) }
        workflowWorker = new WorkflowWorker(simpleWorkflow, domain, taskList)
        workflowWorker.setWorkflowImplementationTypes(workflowImplementationTypes)
        workflowWorker.start()
        log.info(workerStartMessage('Workflow', workflowWorker))
        activityWorker = activityWorker ?: new ActivityWorker(simpleWorkflow, domain, taskList)
        activityWorker.addActivitiesImplementations(activityImplementations)
        activityWorker.start()
        log.info(workerStartMessage('Activity', activityWorker))
    }

    private String workerStartMessage(String workerType, WorkerBase workerBase) {
        "${workerType} worker started on '${workerBase.identity}' for Domain: '${workerBase.domain}' and Task List: \
'${workerBase.getTaskListToPoll()}'."
    }

    /**
     * Creates a workflow client that allows you to schedule a workflow by calling methods on the workflow interface.
     *
     * @param userContext who, where, why
     * @param workflow the interface for this workflow
     * @param link to the relevant object
     * @return the workflow client
     */
    public <T> InterfaceBasedWorkflowClient<T> getNewWorkflowClient(UserContext userContext,
            Class<T> workflow, Link link = null) {
        WorkflowDescriptionTemplate workflowDescriptionTemplate = workflowToDescriptionTemplate[workflow]
        SwfWorkflowTags tags = new SwfWorkflowTags(user: userContext, link: link)
        workflowClientFactory.getNewWorkflowClient(workflow, workflowDescriptionTemplate, tags)
    }

    /**
     * Gets an existing workflow. Useful for canceling, terminating or just looking at attributes of the workflow.
     *
     * @param workflowIdentification ids for a specific existing workflow execution
     * @return the workflow client
     */
    public WorkflowClientExternal getWorkflowClient(WorkflowExecution workflowIdentification) {
        workflowClientFactory.getWorkflowClient(workflowIdentification)
    }

    /**
     * Returns a client for the manual activity. Manual activities do not complete automatically. With a
     * ManualActivityCompletionClient you can revisit an executing manual activity and complete it as you see fit.
     *
     * @param taskToken a token generated by the manual activity
     * @return the workflow client
     */
    ManualActivityCompletionClient getManualActivityCompletionClient(String taskToken) {
        workflowClientFactory.getManualActivityCompletionClient(taskToken)
    }

}
